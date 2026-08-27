package co.candyhouse.app.lockgroup

import android.util.Log
import co.candyhouse.app.BuildConfig
import co.candyhouse.app.ext.userKey.getHistoryTag
import co.candyhouse.sesame.open.CHBleManager
import co.candyhouse.sesame.open.CHDeviceManager
import co.candyhouse.sesame.open.devices.CHSesame5StrictLock
import co.candyhouse.sesame.open.devices.base.CHDeviceLoginStatus
import co.candyhouse.sesame.open.devices.base.CHDevices
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal interface SesameGroupLockRuntime {
    suspend fun resolveDevice(deviceId: String): Result<CHDevices>
    suspend fun ensureScan(): String?
    suspend fun waitForBleLogin(device: CHDevices): String?
    suspend fun finishOperation()
    fun historyTag(): ByteArray
}

internal object ProductionSesameGroupLockRuntime : SesameGroupLockRuntime {
    override suspend fun resolveDevice(deviceId: String): Result<CHDevices> = suspendCancellableCoroutine { continuation ->
        try {
            CHDeviceManager.getCandyDeviceByUUID(deviceId) { device ->
                if (!continuation.isActive) return@getCandyDeviceByUUID
                if (device == null) continuation.resume(Result.failure(IllegalStateException("Device not found")))
                else continuation.resume(Result.success(device))
            }
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resume(Result.failure(error))
        }
    }

    override suspend fun ensureScan(): String? {
        if (CHBleManager.mScanning == true) return null
        return suspendCancellableCoroutine { continuation ->
            try {
                CHBleManager.enableScan { result ->
                    if (!continuation.isActive) return@enableScan
                    result.fold(
                        onSuccess = { continuation.resume(null) },
                        onFailure = { continuation.resume(it.message ?: "BLE scan failed") },
                    )
                }
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resume(error.message ?: "BLE scan failed")
            }
        }
    }

    override suspend fun waitForBleLogin(device: CHDevices): String? {
        if (device.deviceStatus.value == CHDeviceLoginStatus.logined) return null
        return withTimeoutOrNull(LOGIN_TIMEOUT_MS) {
            while (device.deviceStatus.value != CHDeviceLoginStatus.logined) delay(LOGIN_POLL_MS)
            null
        } ?: "BLE login timed out or disconnected"
    }

    override suspend fun finishOperation() {
        if (CHBleManager.mScanning == true) CHBleManager.disableScan {}
    }

    override fun historyTag(): ByteArray = getHistoryTag()

    private const val LOGIN_TIMEOUT_MS = 8_000L
    private const val LOGIN_POLL_MS = 100L
}

internal class SesameGroupLockGateway(
    private val runtime: SesameGroupLockRuntime = ProductionSesameGroupLockRuntime,
    private val commandTimeoutMs: Long = COMMAND_TIMEOUT_MS,
    private val stateObserveTimeoutMs: Long = STATE_OBSERVE_TIMEOUT_MS,
    private val statePollMs: Long = STATE_POLL_MS,
) : GroupLockGateway {
    override suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult {
        val opStart = nowMs()
        p2GroupLog(deviceId, action, "operation start")
        try {
            val resolveStart = nowMs()
            p2GroupLog(deviceId, action, "resolveDevice start")
            val device = runtime.resolveDevice(deviceId).getOrElse {
                p2GroupLog(deviceId, action, "resolveDevice fail elapsed=${elapsedMs(resolveStart)}ms error=${it::class.java.simpleName}")
                return DeviceOperationResult(deviceId, action, false, null, it.message ?: "Device not found")
            }
            p2GroupLog(deviceId, action, "resolveDevice end elapsed=${elapsedMs(resolveStart)}ms model=${device.productModel} status=${device.deviceStatus}")
            if (!isSupportedGroupLockDevice(device)) {
                return DeviceOperationResult(deviceId, action, false, lockStateOf(device), "Unsupported group lock device: ${device.productModel}")
            }
            val strictDevice = device as? CHSesame5StrictLock
                ?: return DeviceOperationResult(deviceId, action, false, lockStateOf(device), "Group lock device does not expose strict BLE commands")

            val scanStart = nowMs()
            p2GroupLog(deviceId, action, "ensureScan start")
            runtime.ensureScan()?.let {
                p2GroupLog(deviceId, action, "ensureScan fail elapsed=${elapsedMs(scanStart)}ms error=$it")
                return DeviceOperationResult(deviceId, action, false, lockStateOf(device), it)
            }
            p2GroupLog(deviceId, action, "ensureScan end elapsed=${elapsedMs(scanStart)}ms status=${device.deviceStatus}")

            val loginStart = nowMs()
            p2GroupLog(deviceId, action, "waitForBleLogin start status=${device.deviceStatus}")
            runtime.waitForBleLogin(device)?.let {
                p2GroupLog(deviceId, action, "waitForBleLogin fail elapsed=${elapsedMs(loginStart)}ms status=${device.deviceStatus} error=$it")
                return DeviceOperationResult(deviceId, action, false, lockStateOf(device), it)
            }
            p2GroupLog(deviceId, action, "waitForBleLogin end elapsed=${elapsedMs(loginStart)}ms status=${device.deviceStatus}")

            val commandStart = nowMs()
            p2GroupLog(deviceId, action, "strict${if (action == GroupLockAction.LOCK) "Lock" else "Unlock"} call status=${device.deviceStatus}")
            val commandResult = withTimeoutOrNull(commandTimeoutMs) {
                suspendCancellableCoroutine<Result<Unit>> { continuation ->
                    val callback: co.candyhouse.sesame.utils.CHResult<co.candyhouse.sesame.utils.CHEmpty> = { result ->
                        if (!continuation.isActive) return@let
                        p2GroupLog(deviceId, action, "strict callback elapsed=${elapsedMs(commandStart)}ms success=${result.isSuccess} status=${device.deviceStatus}")
                        continuation.resume(result.map { Unit })
                    }
                    if (action == GroupLockAction.LOCK) strictDevice.strictLock(runtime.historyTag(), callback)
                    else strictDevice.strictUnlock(runtime.historyTag(), callback)
                }
            } ?: run {
                p2GroupLog(deviceId, action, "timeout stage=BLE_COMMAND elapsed=${elapsedMs(commandStart)}ms status=${device.deviceStatus}")
                return DeviceOperationResult(deviceId, action, false, lockStateOf(device), "BLE command timed out")
            }
            commandResult.exceptionOrNull()?.let {
                p2GroupLog(deviceId, action, "strict command failure elapsed=${elapsedMs(commandStart)}ms error=${it::class.java.simpleName} status=${device.deviceStatus}")
                return DeviceOperationResult(deviceId, action, false, lockStateOf(device), it.message ?: "BLE command failed")
            }

            val confirmationStart = nowMs()
            p2GroupLog(deviceId, action, "target confirmation start target=$action status=${device.deviceStatus}")
            val confirmedState = waitForTargetState(device, action)
            if (confirmedState == null) {
                p2GroupLog(deviceId, action, "timeout stage=STATE_CONFIRM elapsed=${elapsedMs(confirmationStart)}ms final=${lockStateOf(device)} status=${device.deviceStatus}")
                return DeviceOperationResult(deviceId, action, false, lockStateOf(device), TARGET_NOT_CONFIRMED_ERROR)
            }
            p2GroupLog(deviceId, action, "target confirmation end elapsed=${elapsedMs(confirmationStart)}ms state=$confirmedState total=${elapsedMs(opStart)}ms")
            return DeviceOperationResult(deviceId, action, true, confirmedState, null)
        } finally {
            val finishStart = nowMs()
            p2GroupLog(deviceId, action, "finishOperation start total=${elapsedMs(opStart)}ms")
            runtime.finishOperation()
            p2GroupLog(deviceId, action, "finishOperation end elapsed=${elapsedMs(finishStart)}ms total=${elapsedMs(opStart)}ms")
        }
    }

    private suspend fun waitForTargetState(device: CHDevices, action: GroupLockAction): LockState? {
        val target = if (action == GroupLockAction.LOCK) LockState.LOCKED else LockState.UNLOCKED
        return withTimeoutOrNull(stateObserveTimeoutMs) {
            while (true) {
                val current = lockStateOf(device)
                if (current == target) return@withTimeoutOrNull current
                delay(statePollMs)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    private fun lockStateOf(device: CHDevices): LockState = lockStateFromStatus(device.deviceStatus)

    private fun nowMs(): Long = System.currentTimeMillis()
    private fun elapsedMs(start: Long): Long = nowMs() - start

    private fun p2GroupLog(deviceId: String, action: GroupLockAction, message: String) {
        if (!BuildConfig.DEBUG) return
        try {
            Log.d(P2_LOG_TAG, "dev=${shortDeviceId(deviceId)} action=$action $message")
        } catch (_: RuntimeException) {
            // Local JVM unit tests do not provide android.util.Log. Diagnostics must not alter behavior.
        }
    }

    private fun shortDeviceId(deviceId: String): String = deviceId.filter { it.isLetterOrDigit() }.takeLast(6).ifEmpty { "unknown" }

    companion object {
        internal const val TARGET_NOT_CONFIRMED_ERROR = "command accepted but target state was not confirmed"
        private const val COMMAND_TIMEOUT_MS = 8_000L
        private const val STATE_OBSERVE_TIMEOUT_MS = 1_500L
        private const val STATE_POLL_MS = 100L
        private const val P2_LOG_TAG = "P2GroupBLE"
    }
}

package co.candyhouse.app.lockgroup

import android.util.Log
import co.candyhouse.app.BuildConfig
import co.candyhouse.sesame.open.CHBleManager
import co.candyhouse.sesame.open.CHDeviceManager
import co.candyhouse.sesame.open.CHScanStatus
import co.candyhouse.sesame.open.devices.CHSesame5StrictLock
import co.candyhouse.sesame.open.devices.base.CHDeviceLoginStatus
import co.candyhouse.sesame.open.devices.base.CHDeviceStatus
import co.candyhouse.sesame.open.devices.base.CHDevices
import co.candyhouse.sesame.utils.CHEmpty
import co.candyhouse.sesame.utils.CHResult
import co.receiver.widget.AutoUnlockForegroundService
import co.utils.UserUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume

private const val P2_GROUP_TAG = "P2GroupBLE"
private fun p2GroupLog(message: String) {
    if (!BuildConfig.DEBUG) return
    try {
        Log.d(P2_GROUP_TAG, message)
    } catch (_: RuntimeException) {
        // Local JVM unit tests do not provide android.util.Log. Diagnostics must not alter behavior.
    }
}
private fun shortDeviceId(deviceId: String): String = deviceId.takeLast(6)

internal interface SesameGroupLockRuntime {
    suspend fun resolveDevice(deviceId: String): Result<CHDevices>
    suspend fun ensureScan(): String?
    suspend fun waitForBleLogin(device: CHDevices): String?
    suspend fun finishOperation()
    fun historyTag(): ByteArray?
}

internal class ProductionSesameGroupLockRuntime : SesameGroupLockRuntime {
    private var scanInitialized = false
    private var scanStartedByGateway = false
    private var scanError: String? = null

    override suspend fun resolveDevice(deviceId: String): Result<CHDevices> {
        val started = System.currentTimeMillis()
        p2GroupLog("dev=${shortDeviceId(deviceId)} resolveDevice start")
        return suspendCancellableCoroutine { continuation ->
            try {
                CHDeviceManager.getCandyDeviceByUUID(deviceId) { result ->
                    p2GroupLog("dev=${shortDeviceId(deviceId)} resolveDevice callback success=${result.isSuccess} elapsed=${System.currentTimeMillis() - started}ms")
                    result.onSuccess { state ->
                        if (continuation.isActive) continuation.resume(Result.success(state.data))
                    }
                    result.onFailure { error ->
                        if (continuation.isActive) continuation.resume(Result.failure(error))
                    }
                }
            } catch (error: Throwable) {
                p2GroupLog("dev=${shortDeviceId(deviceId)} resolveDevice exception=${error::class.java.simpleName} elapsed=${System.currentTimeMillis() - started}ms")
                if (continuation.isActive) continuation.resume(Result.failure(error))
            }
        }
    }

    override suspend fun ensureScan(): String? {
        val started = System.currentTimeMillis()
        p2GroupLog("ensureScan start initialized=$scanInitialized scanning=${CHBleManager.mScanning}")
        if (scanInitialized) {
            p2GroupLog("ensureScan cached error=$scanError elapsed=${System.currentTimeMillis() - started}ms")
            return scanError
        }
        val wasEnabled = CHBleManager.mScanning == CHScanStatus.Enable
        val error = awaitSimpleBleResult { callback -> CHBleManager.enableScan(false, callback) }
        scanInitialized = true
        scanStartedByGateway = error == null && !wasEnabled
        scanError = error?.message ?: error?.javaClass?.simpleName
        p2GroupLog("ensureScan end error=$scanError startedByGateway=$scanStartedByGateway scanning=${CHBleManager.mScanning} elapsed=${System.currentTimeMillis() - started}ms")
        return scanError
    }

    override suspend fun waitForBleLogin(device: CHDevices): String? {
        val id = shortDeviceId(device.deviceId.toString())
        val started = System.currentTimeMillis()
        p2GroupLog("dev=$id waitForBleLogin start logical=${device.deviceStatus} loginValue=${device.deviceStatus.value}")
        if (device.deviceStatus.value == CHDeviceLoginStatus.logined) {
            p2GroupLog("dev=$id waitForBleLogin immediate-logined elapsed=${System.currentTimeMillis() - started}ms")
            return null
        }
        val loggedIn = withTimeoutOrNull(LOGIN_TIMEOUT_MS) {
            var connectRequested = false
            while (device.deviceStatus.value != CHDeviceLoginStatus.logined) {
                if (device.deviceStatus == CHDeviceStatus.ReceivedAdV && !connectRequested) {
                    connectRequested = true
                    p2GroupLog("dev=$id waitForBleLogin connect requested logical=${device.deviceStatus}")
                    try {
                        device.connect { }
                    } catch (error: Throwable) {
                        p2GroupLog("dev=$id waitForBleLogin connect exception=${error::class.java.simpleName}")
                        return@withTimeoutOrNull false
                    }
                }
                if (device.deviceStatus == CHDeviceStatus.NoBleSignal) connectRequested = false
                delay(LOGIN_POLL_MS)
            }
            true
        } ?: false
        p2GroupLog("dev=$id waitForBleLogin end loggedIn=$loggedIn logical=${device.deviceStatus} elapsed=${System.currentTimeMillis() - started}ms")
        return if (loggedIn) null else "BLE login timed out or disconnected"
    }

    override suspend fun finishOperation() {
        val started = System.currentTimeMillis()
        val shouldDisableScan = scanStartedByGateway && !AutoUnlockForegroundService.isLive
        p2GroupLog("finishOperation start disableScan=$shouldDisableScan scanning=${CHBleManager.mScanning}")
        scanInitialized = false
        scanStartedByGateway = false
        scanError = null
        if (shouldDisableScan) {
            withTimeoutOrNull(SCAN_FINISH_TIMEOUT_MS) { awaitSimpleBleResult { callback -> CHBleManager.disableScan(callback) } }
        }
        p2GroupLog("finishOperation end scanning=${CHBleManager.mScanning} elapsed=${System.currentTimeMillis() - started}ms")
    }

    override fun historyTag(): ByteArray? = UserUtils.getEnvironmentIdWithByte()

    private suspend fun awaitSimpleBleResult(block: (CHResult<CHEmpty>) -> Unit): Throwable? =
        suspendCancellableCoroutine { continuation ->
            try {
                block(callback@{ result ->
                    if (!continuation.isActive) return@callback
                    continuation.resume(result.exceptionOrNull())
                })
            } catch (cancellation: CancellationException) {
                continuation.cancel(cancellation)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resume(error)
            }
        }

    companion object {
        private const val LOGIN_TIMEOUT_MS = 8_000L
        private const val LOGIN_POLL_MS = 100L
        private const val SCAN_FINISH_TIMEOUT_MS = 2_000L
    }
}

internal class SesameGroupLockGateway(
    private val runtime: SesameGroupLockRuntime = ProductionSesameGroupLockRuntime(),
    private val stateObserveTimeoutMs: Long = STATE_OBSERVE_TIMEOUT_MS,
    private val statePollMs: Long = STATE_POLL_MS,
) : GroupLockGateway {
    override suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult {
        val opStarted = System.currentTimeMillis()
        val id = shortDeviceId(deviceId)
        p2GroupLog("dev=$id action=$action operation start")
        val deviceResult = runtime.resolveDevice(deviceId)
        val device = deviceResult.getOrElse { error ->
            p2GroupLog("dev=$id action=$action operation fail stage=resolve elapsed=${System.currentTimeMillis() - opStarted}ms error=${error::class.java.simpleName}")
            return DeviceOperationResult(deviceId, false, error.message ?: "Device not found")
        }
        p2GroupLog("dev=$id action=$action resolveDevice end model=${device.productModel} logical=${device.deviceStatus} elapsed=${System.currentTimeMillis() - opStarted}ms")

        if (!isSupportedGroupLockDevice(device)) {
            return DeviceOperationResult(deviceId, false, "Unsupported group lock device: ${device.productModel}", effectiveState(device))
        }
        runtime.ensureScan()?.let { error ->
            p2GroupLog("dev=$id action=$action operation fail stage=scan elapsed=${System.currentTimeMillis() - opStarted}ms error=$error")
            return DeviceOperationResult(deviceId, false, error, effectiveState(device))
        }
        runtime.waitForBleLogin(device)?.let { error ->
            p2GroupLog("dev=$id action=$action operation fail stage=login logical=${device.deviceStatus} elapsed=${System.currentTimeMillis() - opStarted}ms error=$error")
            return DeviceOperationResult(deviceId, false, error, effectiveState(device))
        }

        val targetState = if (action == GroupLockAction.LOCK) LockState.LOCKED else LockState.UNLOCKED
        val historyTag = runtime.historyTag()
        val commandStarted = System.currentTimeMillis()
        p2GroupLog("dev=$id action=$action strict call logical=${device.deviceStatus} target=$targetState")
        val outcome = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            awaitCommand { callback ->
                if (device !is CHSesame5StrictLock) {
                    callback(Result.failure(UnsupportedOperationException("Strict BLE lock is not supported")))
                    return@awaitCommand
                }
                if (action == GroupLockAction.LOCK) device.strictLock(historytag = historyTag, result = callback)
                else device.strictUnlock(historytag = historyTag, result = callback)
            }
        } ?: CommandOutcome.Failure(TimeoutException("BLE command timed out"))
        p2GroupLog("dev=$id action=$action strict callback outcome=${outcome::class.java.simpleName} logical=${device.deviceStatus} commandElapsed=${System.currentTimeMillis() - commandStarted}ms totalElapsed=${System.currentTimeMillis() - opStarted}ms")

        return when (outcome) {
            CommandOutcome.Success -> {
                val confirmStarted = System.currentTimeMillis()
                p2GroupLog("dev=$id action=$action target confirmation start target=$targetState logical=${device.deviceStatus}")
                val observedState = awaitObservedState(device, targetState)
                p2GroupLog("dev=$id action=$action target confirmation end observed=$observedState logical=${device.deviceStatus} elapsed=${System.currentTimeMillis() - confirmStarted}ms totalElapsed=${System.currentTimeMillis() - opStarted}ms")
                if (observedState == targetState) DeviceOperationResult(deviceId, true, finalKnownState = observedState)
                else DeviceOperationResult(deviceId, false, TARGET_NOT_CONFIRMED_ERROR, observedState)
            }
            is CommandOutcome.Failure -> {
                p2GroupLog("dev=$id action=$action operation fail stage=command error=${outcome.error.message} logical=${device.deviceStatus} totalElapsed=${System.currentTimeMillis() - opStarted}ms")
                DeviceOperationResult(deviceId, false, outcome.error.message ?: outcome.error::class.java.simpleName, effectiveState(device))
            }
        }
    }

    override suspend fun getState(deviceId: String): LockState {
        val device = runtime.resolveDevice(deviceId).getOrNull() ?: return LockState.UNKNOWN
        if (!isSupportedGroupLockDevice(device)) return LockState.UNKNOWN
        return effectiveState(device)
    }

    override suspend fun finishOperation() { runtime.finishOperation() }

    private suspend fun awaitObservedState(device: CHDevices, target: LockState): LockState {
        withTimeoutOrNull(stateObserveTimeoutMs) {
            while (bleState(device) != target) delay(statePollMs)
        }
        return bleState(device)
    }

    private fun bleState(device: CHDevices): LockState = when (device.deviceStatus) {
        CHDeviceStatus.Locked -> LockState.LOCKED
        CHDeviceStatus.Unlocked, CHDeviceStatus.Moved -> LockState.UNLOCKED
        else -> LockState.UNKNOWN
    }

    private fun effectiveState(device: CHDevices): LockState {
        val local = bleState(device)
        if (local != LockState.UNKNOWN) return local
        return when (device.deviceShadowStatus) {
            CHDeviceStatus.Locked -> LockState.LOCKED
            CHDeviceStatus.Unlocked, CHDeviceStatus.Moved -> LockState.UNLOCKED
            else -> LockState.UNKNOWN
        }
    }

    private suspend fun awaitCommand(block: (CHResult<CHEmpty>) -> Unit): CommandOutcome =
        suspendCancellableCoroutine { continuation ->
            try {
                block(callback@{ result ->
                    if (!continuation.isActive) return@callback
                    result.fold(
                        onSuccess = { continuation.resume(CommandOutcome.Success) },
                        onFailure = { continuation.resume(CommandOutcome.Failure(it)) },
                    )
                })
            } catch (cancellation: CancellationException) {
                continuation.cancel(cancellation)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resume(CommandOutcome.Failure(error))
            }
        }

    private sealed interface CommandOutcome {
        data object Success : CommandOutcome
        data class Failure(val error: Throwable) : CommandOutcome
    }

    companion object {
        internal const val TARGET_NOT_CONFIRMED_ERROR = "command accepted but target state was not confirmed"
        private const val COMMAND_TIMEOUT_MS = 8_000L
        private const val STATE_OBSERVE_TIMEOUT_MS = 1_500L
        private const val STATE_POLL_MS = 50L
    }
}

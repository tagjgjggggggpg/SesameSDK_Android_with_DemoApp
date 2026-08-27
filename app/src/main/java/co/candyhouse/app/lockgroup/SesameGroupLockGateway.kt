package co.candyhouse.app.lockgroup

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

    override suspend fun resolveDevice(deviceId: String): Result<CHDevices> =
        suspendCancellableCoroutine { continuation ->
            try {
                CHDeviceManager.getCandyDeviceByUUID(deviceId) { result ->
                    result.onSuccess { state ->
                        if (continuation.isActive) continuation.resume(Result.success(state.data))
                    }
                    result.onFailure { error ->
                        if (continuation.isActive) continuation.resume(Result.failure(error))
                    }
                }
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resume(Result.failure(error))
            }
        }

    override suspend fun ensureScan(): String? {
        if (scanInitialized) return scanError

        val wasEnabled = CHBleManager.mScanning == CHScanStatus.Enable
        val error = awaitSimpleBleResult { callback -> CHBleManager.enableScan(false, callback) }
        scanInitialized = true
        scanStartedByGateway = error == null && !wasEnabled
        scanError = error?.message ?: error?.javaClass?.simpleName
        return scanError
    }

    override suspend fun waitForBleLogin(device: CHDevices): String? {
        if (device.deviceStatus.value == CHDeviceLoginStatus.logined) return null

        val loggedIn = withTimeoutOrNull(LOGIN_TIMEOUT_MS) {
            var connectRequested = false
            while (device.deviceStatus.value != CHDeviceLoginStatus.logined) {
                if (device.deviceStatus == CHDeviceStatus.ReceivedAdV && !connectRequested) {
                    connectRequested = true
                    try {
                        device.connect { }
                    } catch (_: Throwable) {
                        return@withTimeoutOrNull false
                    }
                }
                if (device.deviceStatus == CHDeviceStatus.NoBleSignal) {
                    connectRequested = false
                }
                delay(LOGIN_POLL_MS)
            }
            true
        } ?: false

        return if (loggedIn) null else "BLE login timed out or disconnected"
    }

    override suspend fun finishOperation() {
        val shouldDisableScan = scanStartedByGateway && !AutoUnlockForegroundService.isLive
        scanInitialized = false
        scanStartedByGateway = false
        scanError = null

        if (shouldDisableScan) {
            withTimeoutOrNull(SCAN_FINISH_TIMEOUT_MS) {
                awaitSimpleBleResult { callback -> CHBleManager.disableScan(callback) }
            }
        }
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
        val deviceResult = runtime.resolveDevice(deviceId)
        val device = deviceResult.getOrElse { error ->
            return DeviceOperationResult(
                deviceId = deviceId,
                success = false,
                error = error.message ?: "Device not found",
            )
        }

        if (!isSupportedGroupLockDevice(device)) {
            return DeviceOperationResult(
                deviceId = deviceId,
                success = false,
                error = "Unsupported group lock device: ${device.productModel}",
                finalKnownState = effectiveState(device),
            )
        }

        runtime.ensureScan()?.let { error ->
            return DeviceOperationResult(
                deviceId = deviceId,
                success = false,
                error = error,
                finalKnownState = effectiveState(device),
            )
        }

        runtime.waitForBleLogin(device)?.let { error ->
            return DeviceOperationResult(
                deviceId = deviceId,
                success = false,
                error = error,
                finalKnownState = effectiveState(device),
            )
        }

        val targetState = if (action == GroupLockAction.LOCK) LockState.LOCKED else LockState.UNLOCKED
        val historyTag = runtime.historyTag()
        val outcome = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            awaitCommand { callback ->
                if (device !is CHSesame5StrictLock) {
                    callback(Result.failure(UnsupportedOperationException("Strict BLE lock is not supported")))
                    return@awaitCommand
                }

                if (action == GroupLockAction.LOCK) {
                    device.strictLock(historytag = historyTag, result = callback)
                } else {
                    device.strictUnlock(historytag = historyTag, result = callback)
                }
            }
        } ?: CommandOutcome.Failure(TimeoutException("BLE command timed out"))

        return when (outcome) {
            CommandOutcome.Success -> {
                val observedState = awaitObservedState(device, targetState)
                if (observedState == targetState) {
                    DeviceOperationResult(
                        deviceId = deviceId,
                        success = true,
                        finalKnownState = observedState,
                    )
                } else {
                    DeviceOperationResult(
                        deviceId = deviceId,
                        success = false,
                        error = TARGET_NOT_CONFIRMED_ERROR,
                        finalKnownState = observedState,
                    )
                }
            }

            is CommandOutcome.Failure -> DeviceOperationResult(
                deviceId = deviceId,
                success = false,
                error = outcome.error.message ?: outcome.error::class.java.simpleName,
                finalKnownState = effectiveState(device),
            )
        }
    }

    override suspend fun getState(deviceId: String): LockState {
        val device = runtime.resolveDevice(deviceId).getOrNull() ?: return LockState.UNKNOWN
        if (!isSupportedGroupLockDevice(device)) return LockState.UNKNOWN
        return effectiveState(device)
    }

    override suspend fun finishOperation() {
        runtime.finishOperation()
    }

    private suspend fun awaitObservedState(device: CHDevices, target: LockState): LockState {
        withTimeoutOrNull(stateObserveTimeoutMs) {
            while (bleState(device) != target) {
                delay(statePollMs)
            }
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

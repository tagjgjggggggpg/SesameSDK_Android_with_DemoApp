package co.candyhouse.app.lockgroup

import co.candyhouse.sesame.open.CHBleManager
import co.candyhouse.sesame.open.CHDeviceManager
import co.candyhouse.sesame.open.CHScanStatus
import co.candyhouse.sesame.open.devices.CHSesame2
import co.candyhouse.sesame.open.devices.CHSesame5
import co.candyhouse.sesame.open.devices.base.CHDeviceLoginStatus
import co.candyhouse.sesame.open.devices.base.CHDeviceStatus
import co.candyhouse.sesame.open.devices.base.CHDevices
import co.candyhouse.sesame.open.devices.base.CHProductModel
import co.candyhouse.sesame.utils.CHEmpty
import co.candyhouse.sesame.utils.CHResult
import co.receiver.widget.AutoUnlockForegroundService
import co.utils.UserUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume

class SesameGroupLockGateway : GroupLockGateway {
    private var scanInitialized = false
    private var scanStartedByGateway = false
    private var scanError: String? = null

    override suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult {
        val deviceResult = resolveDevice(deviceId)
        val device = deviceResult.getOrElse { error ->
            return DeviceOperationResult(
                deviceId = deviceId,
                success = false,
                error = error.message ?: "Device not found",
            )
        }

        if (!isSupportedDoorLock(device)) {
            return DeviceOperationResult(
                deviceId = deviceId,
                success = false,
                error = "Unsupported group lock device: ${device.productModel}",
                finalKnownState = effectiveState(device),
            )
        }

        ensureScan()?.let { error ->
            return DeviceOperationResult(
                deviceId = deviceId,
                success = false,
                error = error,
                finalKnownState = effectiveState(device),
            )
        }

        waitForBleLogin(device)?.let { error ->
            return DeviceOperationResult(
                deviceId = deviceId,
                success = false,
                error = error,
                finalKnownState = effectiveState(device),
            )
        }

        val targetState = if (action == GroupLockAction.LOCK) LockState.LOCKED else LockState.UNLOCKED
        if (bleState(device) == targetState) {
            return DeviceOperationResult(
                deviceId = deviceId,
                success = true,
                finalKnownState = targetState,
            )
        }

        val historyTag = UserUtils.getEnvironmentIdWithByte()
        val outcome = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            awaitCommand { callback ->
                when (device) {
                    is CHSesame5 -> {
                        if (action == GroupLockAction.LOCK) {
                            device.strictLock(historytag = historyTag, result = callback)
                        } else {
                            device.strictUnlock(historytag = historyTag, result = callback)
                        }
                    }

                    is CHSesame2 -> {
                        if (action == GroupLockAction.LOCK) {
                            device.lock(historytag = historyTag, result = callback)
                        } else {
                            device.unlock(historytag = historyTag, result = callback)
                        }
                    }

                    else -> callback(Result.failure(IllegalStateException("Unsupported group lock device")))
                }
            }
        } ?: CommandOutcome.Failure(TimeoutException("BLE command timed out"))

        return when (outcome) {
            CommandOutcome.Success -> DeviceOperationResult(
                deviceId = deviceId,
                success = true,
                finalKnownState = awaitObservedState(device, targetState),
            )

            is CommandOutcome.Failure -> DeviceOperationResult(
                deviceId = deviceId,
                success = false,
                error = outcome.error.message ?: outcome.error::class.java.simpleName,
                finalKnownState = effectiveState(device),
            )
        }
    }

    override suspend fun getState(deviceId: String): LockState {
        return resolveDevice(deviceId).getOrNull()?.let(::effectiveState) ?: LockState.UNKNOWN
    }

    override suspend fun finishOperation() {
        if (scanStartedByGateway && !AutoUnlockForegroundService.isLive) {
            awaitSimpleBleResult { callback -> CHBleManager.disableScan(callback) }
        }
        scanInitialized = false
        scanStartedByGateway = false
        scanError = null
    }

    private suspend fun ensureScan(): String? {
        if (scanInitialized) return scanError

        val wasEnabled = CHBleManager.mScanning == CHScanStatus.Enable
        val error = awaitSimpleBleResult { callback -> CHBleManager.enableScan(false, callback) }
        scanInitialized = true
        scanStartedByGateway = error == null && !wasEnabled
        scanError = error?.message ?: error?.javaClass?.simpleName
        return scanError
    }

    private suspend fun waitForBleLogin(device: CHDevices): String? {
        if (device.deviceStatus.value == CHDeviceLoginStatus.logined) return null

        val loggedIn = withTimeoutOrNull(LOGIN_TIMEOUT_MS) {
            var connectRequested = false
            while (device.deviceStatus.value != CHDeviceLoginStatus.logined) {
                if (device.deviceStatus == CHDeviceStatus.ReceivedAdV && !connectRequested) {
                    connectRequested = true
                    try {
                        device.connect { }
                    } catch (error: Throwable) {
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

    private suspend fun awaitObservedState(device: CHDevices, target: LockState): LockState {
        withTimeoutOrNull(STATE_OBSERVE_TIMEOUT_MS) {
            while (bleState(device) != target) {
                delay(STATE_POLL_MS)
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

    private fun isSupportedDoorLock(device: CHDevices): Boolean = when (device.productModel) {
        CHProductModel.SS2,
        CHProductModel.SS4,
        CHProductModel.SS5,
        CHProductModel.SS5PRO,
        CHProductModel.SS5US,
        CHProductModel.SS6,
        CHProductModel.SS6Pro,
        CHProductModel.SS6ProSlidingDoor,
        CHProductModel.SSM_MIWA -> device is CHSesame2 || device is CHSesame5

        else -> false
    }

    private suspend fun resolveDevice(deviceId: String): Result<CHDevices> =
        suspendCancellableCoroutine { continuation ->
            CHDeviceManager.getCandyDeviceByUUID(deviceId) { result ->
                result.onSuccess { state ->
                    if (continuation.isActive) continuation.resume(Result.success(state.data))
                }
                result.onFailure { error ->
                    if (continuation.isActive) continuation.resume(Result.failure(error))
                }
            }
        }

    private suspend fun awaitSimpleBleResult(block: (CHResult<CHEmpty>) -> Unit): Throwable? =
        suspendCancellableCoroutine { continuation ->
            try {
                block(callback@{ result ->
                    if (!continuation.isActive) return@callback
                    continuation.resume(result.exceptionOrNull())
                })
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resume(error)
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
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resume(CommandOutcome.Failure(error))
            }
        }

    private sealed interface CommandOutcome {
        data object Success : CommandOutcome
        data class Failure(val error: Throwable) : CommandOutcome
    }

    companion object {
        private const val LOGIN_TIMEOUT_MS = 8_000L
        private const val LOGIN_POLL_MS = 100L
        private const val COMMAND_TIMEOUT_MS = 8_000L
        private const val STATE_OBSERVE_TIMEOUT_MS = 1_500L
        private const val STATE_POLL_MS = 50L
    }
}

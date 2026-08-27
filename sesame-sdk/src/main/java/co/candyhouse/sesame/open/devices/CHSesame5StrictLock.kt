package co.candyhouse.sesame.open.devices

import co.candyhouse.sesame.utils.CHEmpty
import co.candyhouse.sesame.utils.CHResult

/**
 * Latest state originating from an actual live BLE mechStatus publish.
 * The implementation invalidates it when the BLE transport is no longer live.
 */
data class CHSesame5StrictBleObservation(
    val isLocked: Boolean,
    val observedAt: Long,
    val batteryPercent: Int? = null,
)

/**
 * Optional capability for explicit BLE-only SESAME 5-family group operations.
 * strictLock/strictUnlock must never fall back to toggle or IoT.
 */
interface CHSesame5StrictLock {
    fun isStrictBleTransportReady(): Boolean = false

    fun latestStrictBleObservation(): CHSesame5StrictBleObservation? = null

    fun strictLock(historytag: ByteArray? = null, result: CHResult<CHEmpty>) {
        result.invoke(Result.failure(UnsupportedOperationException("Strict BLE lock is not supported")))
    }

    fun strictUnlock(historytag: ByteArray? = null, result: CHResult<CHEmpty>) {
        result.invoke(Result.failure(UnsupportedOperationException("Strict BLE unlock is not supported")))
    }
}

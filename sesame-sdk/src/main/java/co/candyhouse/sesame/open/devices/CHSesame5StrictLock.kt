package co.candyhouse.sesame.open.devices

import co.candyhouse.sesame.utils.CHEmpty
import co.candyhouse.sesame.utils.CHResult

/**
 * Optional capability for callers that require an explicit BLE-only SESAME 5-family lock path.
 * Implementations must never fall back to toggle or IoT from these methods.
 *
 * This capability is intentionally separate from CHSesame5 so the existing public
 * CHSesame5 superinterface hierarchy remains unchanged.
 */
interface CHSesame5StrictLock {
    /** True only when the authenticated BLE transport needed by strict commands is live. */
    fun isStrictBleTransportReady(): Boolean = false

    fun strictLock(historytag: ByteArray? = null, result: CHResult<CHEmpty>) {
        result.invoke(Result.failure(UnsupportedOperationException("Strict BLE lock is not supported")))
    }

    fun strictUnlock(historytag: ByteArray? = null, result: CHResult<CHEmpty>) {
        result.invoke(Result.failure(UnsupportedOperationException("Strict BLE unlock is not supported")))
    }
}

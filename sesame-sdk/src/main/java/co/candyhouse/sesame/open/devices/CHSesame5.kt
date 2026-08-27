package co.candyhouse.sesame.open.devices

import co.candyhouse.sesame.open.devices.base.CHSesameLock
import co.candyhouse.sesame.open.devices.base.CHSesameProtocolMechStatus
import co.candyhouse.sesame.utils.CHEmpty
import co.candyhouse.sesame.utils.CHResult
import co.candyhouse.sesame.utils.bytesToShort
import co.candyhouse.sesame.utils.bytesToUShort

/**
 * Optional capability for callers that require an explicit BLE-only lock path.
 * Implementations must never fall back to toggle or IoT from these methods.
 *
 * The default implementation fails closed so adding this capability does not
 * force third-party CHSesame5 implementations to change their existing
 * lock/unlock behavior.
 */
interface CHSesame5StrictLock {
    fun strictLock(historytag: ByteArray? = null, result: CHResult<CHEmpty>) {
        result.invoke(Result.failure(UnsupportedOperationException("Strict BLE lock is not supported")))
    }

    fun strictUnlock(historytag: ByteArray? = null, result: CHResult<CHEmpty>) {
        result.invoke(Result.failure(UnsupportedOperationException("Strict BLE unlock is not supported")))
    }
}

interface CHSesame5 : CHSesameLock, CHSesame5StrictLock {
    var mechSetting: CHSesame5MechSettings?
    var opsSetting: CHSesame5OpsSettings?
    fun lock(historytag: ByteArray? = null, result: CHResult<CHEmpty>)
    fun unlock(historytag: ByteArray? = null, result: CHResult<CHEmpty>)
    fun toggle(historytag: ByteArray? = null, result: CHResult<CHEmpty>)
    fun magnet(result: CHResult<CHEmpty>)
    fun configureLockPosition(lockTarget: Short, unlockTarget: Short, result: CHResult<CHEmpty>)
    fun autolock(delay: Int, result: CHResult<Int>)
    fun opSensorControl(isEnable: Int, result: CHResult<Int>)
    fun onHistoryReceived(historyData: ByteArray) {}
    fun sendAdvProductTypeCommand(data: ByteArray, result: CHResult<CHEmpty>) {}
}

class CHSesame5MechStatus(override val data: ByteArray) : CHSesameProtocolMechStatus {
    override val position: Short = bytesToShort(data[4], data[5])
    override val target: Short? = if ((bytesToShort(data[2], data[3]).toInt() == -32768)) null else bytesToShort(data[2], data[3])
    private val flags = data[6].toInt()
    override var isInLockRange: Boolean = flags and 2 > 0
    override var isCritical: Boolean? = flags and 8 > 0
    override var isStop: Boolean? = flags and 16 > 0
    override var isBatteryCritical: Boolean = flags and 32 > 0
}

class CHSesame5MechSettings(data: ByteArray) {
    var lockPosition: Short = bytesToShort(data[0], data[1])
    var unlockPosition: Short = bytesToShort(data[2], data[3])
    var autoLockSecond: Short = bytesToShort(data[4], data[5])
}

class CHSesame5OpsSettings(data: ByteArray) {
    var opsLockSecond: UShort = bytesToUShort(data[0], data[1])
}

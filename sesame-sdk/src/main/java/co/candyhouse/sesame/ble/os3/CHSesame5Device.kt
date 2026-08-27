package co.candyhouse.sesame.ble.os3

import android.annotation.SuppressLint
import co.candyhouse.sesame.BuildConfig
import co.candyhouse.sesame.ble.DeviceSegmentType
import co.candyhouse.sesame.ble.SSM3PublishPayload
import co.candyhouse.sesame.ble.SSM3ResponsePayload
import co.candyhouse.sesame.ble.SesameItemCode
import co.candyhouse.sesame.ble.SesameResultCode
import co.candyhouse.sesame.ble.isBleAvailable
import co.candyhouse.sesame.ble.os3.base.SesameOS3Payload
import co.candyhouse.sesame.db.model.historyTagBLE
import co.candyhouse.sesame.db.model.historyTagIOT
import co.candyhouse.sesame.open.devices.CHSesame2MechStatus
import co.candyhouse.sesame.open.devices.CHSesame5
import co.candyhouse.sesame.open.devices.CHSesame5LiveBatterySample
import co.candyhouse.sesame.open.devices.CHSesame5LiveBleSnapshot
import co.candyhouse.sesame.open.devices.CHSesame5LiveLockState
import co.candyhouse.sesame.open.devices.CHSesame5MechSettings
import co.candyhouse.sesame.open.devices.CHSesame5MechStatus
import co.candyhouse.sesame.open.devices.CHSesame5OpsSettings
import co.candyhouse.sesame.open.devices.CHSesame5StrictLock
import co.candyhouse.sesame.open.devices.base.CHDeviceLoginStatus
import co.candyhouse.sesame.open.devices.base.CHDeviceStatus
import co.candyhouse.sesame.open.devices.base.CHSesameOS3LockBase
import co.candyhouse.sesame.open.devices.base.NSError
import co.candyhouse.sesame.server.CHAPIClientBiz
import co.candyhouse.sesame.server.CHIotManager
import co.candyhouse.sesame.utils.*

internal class Sesame5LiveBleStateTracker {
    @Volatile private var lockState: CHSesame5LiveLockState? = null
    @Volatile private var stateObservedAtMillis: Long = 0L
    @Volatile private var batterySample: CHSesame5LiveBatterySample? = null

    fun invalidate() {
        lockState = null
        stateObservedAtMillis = 0L
        batterySample = null
    }

    fun onLiveMechStatus(state: CHSesame5LiveLockState, observedAtMillis: Long) {
        lockState = state
        stateObservedAtMillis = observedAtMillis
        batterySample = null
    }

    fun onBatteryPercentage(percentage: Int, sourceStateObservedAtMillis: Long) {
        if (lockState == null || stateObservedAtMillis != sourceStateObservedAtMillis) return
        batterySample = CHSesame5LiveBatterySample(percentage, sourceStateObservedAtMillis)
    }

    fun snapshot(transportReady: Boolean): CHSesame5LiveBleSnapshot? {
        if (!transportReady) return null
        val state = lockState ?: return null
        return CHSesame5LiveBleSnapshot(state, stateObservedAtMillis, batterySample)
    }
}

@SuppressLint("MissingPermission")
internal class CHSesame5Device(
    private val strictBleAvailability: ((CHResult<CHEmpty>) -> Boolean)? = null,
    private val strictCommandSink: ((SesameItemCode, ByteArray?, CHResult<CHEmpty>) -> Unit)? = null,
    private val strictBleTransportSink: ((SesameOS3Payload, DeviceSegmentType, (SSM3ResponsePayload) -> Unit) -> Unit)? = null,
) : CHSesameOS3LockBase(), CHSesame5, CHSesame5StrictLock {
    override var mechSetting: CHSesame5MechSettings? = null
    override var opsSetting: CHSesame5OpsSettings? = null
    private val liveBleState = Sesame5LiveBleStateTracker()
    private fun diagId(): String = p2DiagnosticDeviceId(deviceId?.toString())
    private fun diag(message: String) {
        if (!BuildConfig.DEBUG) return
        P2DiagnosticLog.append("P2StrictBLE", "dev=${diagId()} $message")
        try { L.d("P2StrictBLE", "dev=${diagId()} $message") } catch (_: RuntimeException) {}
    }
    override fun connect(result: CHResult<CHEmpty>) {
        if (mBluetoothGatt == null) liveBleState.invalidate()
        super.connect(result)
    }
    override fun isStrictBleTransportReady(): Boolean =
        deviceStatus.value == CHDeviceLoginStatus.logined && mBluetoothGatt != null && mCharacteristic != null
    override fun liveBleSnapshot(): CHSesame5LiveBleSnapshot? = liveBleState.snapshot(isStrictBleTransportReady())
    override fun goIOT() { L.d("hcia", "[ss5]goIOT:$deviceId"); CHIotManager.subscribeSesame2Shadow(this) { result -> result.onSuccess { resource -> resource.data.state.reported.wm2s?.let { wm2s -> isConnectedByWM2 = wm2s.map { it.value.hexStringToByteArray().first().toInt() }.contains(1) }; if (isConnectedByWM2) { resource.data.state.reported.mechst?.let { mechShadow -> val res: CHResult<CHEmpty> = { }; if (!isBleAvailable(res)) mechStatus = CHSesame5MechStatus(CHSesame2MechStatus(mechShadow.hexStringToByteArray()).ss5Adapter()) }; deviceShadowStatus = if (mechStatus!!.isInLockRange) CHDeviceStatus.Locked else CHDeviceStatus.Unlocked } else deviceShadowStatus = null } } }
    override fun configureLockPosition(lockTarget: Short, unlockTarget: Short, result: CHResult<CHEmpty>) { val cmd = SesameOS3Payload(SesameItemCode.mechSetting.value, lockTarget.toReverseBytes() + unlockTarget.toReverseBytes()); sendCommand(cmd, DeviceSegmentType.cipher) { res -> if (res.cmdResultCode == SesameResultCode.success.value) { mechSetting?.lockPosition = lockTarget; mechSetting?.unlockPosition = unlockTarget; result.invoke(Result.success(CHResultState.CHResultStateBLE(CHEmpty()))) } else result.invoke(Result.failure(NSError(res.cmdResultCode.toString(), "CBCentralManager", res.cmdResultCode.toInt()))) } }
    override fun sendAdvProductTypeCommand(data: ByteArray, result: CHResult<CHEmpty>) { sendCommand(SesameOS3Payload(SesameItemCode.SS3_ITEM_CODE_SET_ADV_PRODUCT_TYPE.value, data), DeviceSegmentType.cipher) { res -> if (res.cmdResultCode == SesameResultCode.success.value) result.invoke(Result.success(CHResultState.CHResultStateBLE(CHEmpty()))) else result.invoke(Result.failure(NSError(res.cmdResultCode.toString(), "CBCentralManager", res.cmdResultCode.toInt()))) } }
    override fun autolock(delay: Int, result: CHResult<Int>) { if (!isBleAvailable(result)) return; sendCommand(SesameOS3Payload(SesameItemCode.autolock.value, delay.toShort().toReverseBytes()), DeviceSegmentType.cipher) { mechSetting?.autoLockSecond = delay.toShort(); result.invoke(Result.success(CHResultState.CHResultStateBLE(delay))) } }
    override fun opSensorControl(isEnable: Int, result: CHResult<Int>) { if (!isBleAvailable(result)) return; sendCommand(SesameOS3Payload(SesameItemCode.OPS_CONTROL.value, isEnable.toShort().toReverseBytes()), DeviceSegmentType.cipher) { opsSetting?.opsLockSecond = isEnable.toUShort(); result.invoke(Result.success(CHResultState.CHResultStateBLE(isEnable))) } }
    override fun magnet(result: CHResult<CHEmpty>) { if (!isBleAvailable(result)) return; sendCommand(SesameOS3Payload(SesameItemCode.magnet.value, byteArrayOf()), DeviceSegmentType.cipher) { result.invoke(Result.success(CHResultState.CHResultStateBLE(CHEmpty()))) } }
    override fun toggle(historytag: ByteArray?, result: CHResult<CHEmpty>) { if (deviceStatus.value == CHDeviceLoginStatus.logined && isBleAvailable(result)) { if (deviceStatus == CHDeviceStatus.Locked) unlock(historytag, result) else lock(historytag, result) } else sesame2KeyData?.apply { CHAPIClientBiz.cmdSesame(SesameItemCode.toggle, this@CHSesame5Device, this.historyTagIOT(historytag), result) } }
    override fun unlock(historytag: ByteArray?, result: CHResult<CHEmpty>) { if (deviceStatus.value == CHDeviceLoginStatus.unlogined && deviceShadowStatus != null) { CHAPIClientBiz.cmdSesame(SesameItemCode.unlock, this, sesame2KeyData!!.historyTagIOT(historytag), result); return }; if (!isBleAvailable(result)) { CHAPIClientBiz.cmdSesame(SesameItemCode.toggle, this, sesame2KeyData!!.historyTagIOT(historytag), result); return }; sendCommand(SesameOS3Payload(SesameItemCode.unlock.value, sesame2KeyData!!.historyTagBLE(historytag)), DeviceSegmentType.cipher) { res -> if (res.cmdResultCode == SesameResultCode.success.value) result.invoke(Result.success(CHResultState.CHResultStateBLE(CHEmpty()))) else result.invoke(Result.failure(NSError(res.cmdResultCode.toString(), "CBCentralManager", res.cmdResultCode.toInt()))) } }
    override fun lock(historytag: ByteArray?, result: CHResult<CHEmpty>) { if (deviceStatus.value == CHDeviceLoginStatus.unlogined && deviceShadowStatus != null) { CHAPIClientBiz.cmdSesame(SesameItemCode.lock, this, sesame2KeyData!!.historyTagIOT(historytag), result); return }; if (!isBleAvailable(result)) { CHAPIClientBiz.cmdSesame(SesameItemCode.toggle, this, sesame2KeyData!!.historyTagIOT(historytag), result); return }; sendCommand(SesameOS3Payload(SesameItemCode.lock.value, sesame2KeyData!!.historyTagBLE(historytag)), DeviceSegmentType.cipher) { res -> if (res.cmdResultCode == SesameResultCode.success.value) result.invoke(Result.success(CHResultState.CHResultStateBLE(CHEmpty()))) else result.invoke(Result.failure(NSError(res.cmdResultCode.toString(), "CBCentralManager", res.cmdResultCode.toInt()))) } }
    override fun strictUnlock(historytag: ByteArray?, result: CHResult<CHEmpty>) { diag("strictUnlock call logical=$deviceStatus"); StrictSesame5CommandExecutor(isBleAvailable = { strictBleAvailability?.invoke(result) ?: isBleAvailable(result) }, sendCommand = { itemCode -> dispatchStrictBleCommand(itemCode, historytag, result) }).unlock() }
    override fun strictLock(historytag: ByteArray?, result: CHResult<CHEmpty>) { diag("strictLock call logical=$deviceStatus"); StrictSesame5CommandExecutor(isBleAvailable = { strictBleAvailability?.invoke(result) ?: isBleAvailable(result) }, sendCommand = { itemCode -> dispatchStrictBleCommand(itemCode, historytag, result) }).lock() }
    private fun dispatchStrictBleCommand(itemCode: SesameItemCode, historytag: ByteArray?, result: CHResult<CHEmpty>) { diag("dispatchStrictBleCommand item=$itemCode logical=$deviceStatus"); strictCommandSink?.let { it(itemCode, historytag, result); return }; sendStrictBleCommand(itemCode, historytag, result) }
    private fun sendStrictBleCommand(itemCode: SesameItemCode, historytag: ByteArray?, result: CHResult<CHEmpty>) {
        check(itemCode == SesameItemCode.lock || itemCode == SesameItemCode.unlock)
        diag("sendStrictBleCommand start item=$itemCode logical=$deviceStatus")
        val keyData = sesame2KeyData ?: run { diag("fail no-key-data"); result.invoke(Result.failure(IllegalStateException("SESAME key data is unavailable"))); return }
        val command = SesameOS3Payload(itemCode.value, keyData.historyTagBLE(historytag))
        val responseHandler: (SSM3ResponsePayload) -> Unit = { res -> diag("strict response item=$itemCode resultCode=${res.cmdResultCode} logical=$deviceStatus at=${System.currentTimeMillis()}"); if (res.cmdResultCode == SesameResultCode.success.value) result.invoke(Result.success(CHResultState.CHResultStateBLE(CHEmpty()))) else result.invoke(Result.failure(NSError(res.cmdResultCode.toString(), "CBCentralManager", res.cmdResultCode.toInt()))) }
        strictBleTransportSink?.let { diag("strict transport sink item=$itemCode"); it(command, DeviceSegmentType.cipher, responseHandler); return }
        val existing = cmdCallBack.containsKey(command.itemCode); val at = System.currentTimeMillis()
        diag("callback register item=$itemCode existing=$existing at=$at logical=$deviceStatus gatt=${mBluetoothGatt != null} characteristic=${mCharacteristic != null}")
        if (existing) diag("WARNING callback overwrite item=$itemCode at=$at")
        diag("sendCommand enqueue item=$itemCode")
        sendCommand(command, DeviceSegmentType.cipher, responseHandler)
        diag("callback registered item=$itemCode present=${cmdCallBack.containsKey(command.itemCode)} at=${System.currentTimeMillis()}")
    }
    override fun onHistoryReceived(historyData: ByteArray) {}
    override fun onHistoryReceivedInternal(historyData: ByteArray) { onHistoryReceived(historyData) }
    override fun handleRegisterResponse(registerRes: SSM3ResponsePayload, serverSecret: String, result: CHResult<CHEmpty>) { mechStatus = CHSesame5MechStatus(registerRes.payload.sliceArray(0..6)); mechSetting = CHSesame5MechSettings(registerRes.payload.sliceArray(7..12)); val eccPublicKeyFromSS5 = registerRes.payload.sliceArray(13..76); val ecdhSecret = EccKey.ecdh(eccPublicKeyFromSS5); val deviceSecret = ecdhSecret.sliceArray(0..15).toHexString(); saveDeviceAndCipher(deviceSecret, serverSecret, result); deviceStatus = if (mechStatus?.isInLockRange == true) CHDeviceStatus.Locked else CHDeviceStatus.Unlocked }
    override fun handleDevicePublish(receivePayload: SSM3PublishPayload) { when (receivePayload.cmdItCode) { SesameItemCode.mechStatus.value -> { val before = deviceStatus; val observedAt = System.currentTimeMillis(); diag("mechStatus publish logicalBefore=$before at=$observedAt"); mechStatus = CHSesame5MechStatus(receivePayload.payload); deviceStatus = if (mechStatus!!.isInLockRange) CHDeviceStatus.Locked else CHDeviceStatus.Unlocked; liveBleState.onLiveMechStatus(if (mechStatus!!.isInLockRange) CHSesame5LiveLockState.LOCKED else CHSesame5LiveLockState.UNLOCKED, observedAt); diag("mechStatus applied logicalAfter=$deviceStatus at=${System.currentTimeMillis()}"); reportBatteryData(receivePayload.payload.sliceArray(0..1).toHexString()) { percentage -> liveBleState.onBatteryPercentage(percentage, observedAt) } }; SesameItemCode.mechSetting.value -> mechSetting = CHSesame5MechSettings(receivePayload.payload); SesameItemCode.OPS_CONTROL.value -> { opsSetting = CHSesame5OpsSettings(receivePayload.payload); L.d("switch", "[ss5][opsSecond]:${opsSetting!!.opsLockSecond}") } } }
}

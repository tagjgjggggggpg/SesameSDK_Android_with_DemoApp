package co.candyhouse.app.lockgroup

import android.bluetooth.BluetoothDevice
import co.candyhouse.sesame.open.devices.CHSesame5
import co.candyhouse.sesame.open.devices.CHSesame5LiveBleSnapshot
import co.candyhouse.sesame.open.devices.CHSesame5LiveLockState
import co.candyhouse.sesame.open.devices.CHSesame5MechSettings
import co.candyhouse.sesame.open.devices.CHSesame5OpsSettings
import co.candyhouse.sesame.open.devices.CHSesame5StrictLock
import co.candyhouse.sesame.open.devices.CHWifiModule2Delegate
import co.candyhouse.sesame.open.devices.base.CHDeviceStatus
import co.candyhouse.sesame.open.devices.base.CHDeviceStatusDelegate
import co.candyhouse.sesame.open.devices.base.CHDevices
import co.candyhouse.sesame.open.devices.base.CHProductModel
import co.candyhouse.sesame.open.devices.base.CHSesameProtocolMechStatus
import co.candyhouse.sesame.utils.CHEmpty
import co.candyhouse.sesame.utils.CHMulticastDelegate
import co.candyhouse.sesame.utils.CHResult
import co.candyhouse.sesame.utils.CHResultState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SesameGroupLockGatewayTest {
    @Test fun lock_usesStrictCapabilityAndNeverLegacyOrToggle() = runBlocking { val d=FakeStrictSesame5Device(CHDeviceStatus.Unlocked); val r=gateway(mapOf("device-a" to d)).operate("device-a",GroupLockAction.LOCK); assertTrue(r.success); assertEquals(1,d.strictLockCalls); assertNoUnsafeFallback(d) }
    @Test fun unlock_usesStrictCapabilityAndNeverLegacyOrToggle() = runBlocking { val d=FakeStrictSesame5Device(CHDeviceStatus.Locked); val r=gateway(mapOf("device-a" to d)).operate("device-a",GroupLockAction.UNLOCK); assertTrue(r.success); assertEquals(1,d.strictUnlockCalls); assertNoUnsafeFallback(d) }
    @Test fun mixedState_lock_dispatchesExplicitStrictLockToBothDevices() = runBlocking { val a=FakeStrictSesame5Device(CHDeviceStatus.Locked); val b=FakeStrictSesame5Device(CHDeviceStatus.Unlocked); val c=GroupLockController(gateway(mapOf("a" to a,"b" to b))); val r=c.lockGroup(LockGroup("e","玄関",listOf("a","b"))); assertEquals(GroupOperationStatus.SUCCESS,r.status); assertEquals(1,a.strictLockCalls); assertEquals(1,b.strictLockCalls); assertNoUnsafeFallback(a); assertNoUnsafeFallback(b) }
    @Test fun mixedState_unlock_dispatchesExplicitStrictUnlockToBothDevices() = runBlocking { val a=FakeStrictSesame5Device(CHDeviceStatus.Locked); val b=FakeStrictSesame5Device(CHDeviceStatus.Unlocked); val c=GroupLockController(gateway(mapOf("a" to a,"b" to b))); val r=c.unlockGroup(LockGroup("e","玄関",listOf("a","b"))); assertEquals(GroupOperationStatus.SUCCESS,r.status); assertEquals(1,a.strictUnlockCalls); assertEquals(1,b.strictUnlockCalls); assertNoUnsafeFallback(a); assertNoUnsafeFallback(b) }
    @Test fun shadowOnly_isNeverLiveOrFresh()=runBlocking{val d=FakeStrictSesame5Device(CHDeviceStatus.NoBleSignal).apply{liveValid=false;deviceShadowStatus=CHDeviceStatus.Locked};val s=gateway(mapOf("a" to d)).getDeviceSnapshot("a");assertEquals(LockState.UNKNOWN,s.state);assertFalse(s.fresh)}
    @Test fun disconnectedLiveSnapshot_isUnknown()=runBlocking{val d=FakeStrictSesame5Device(CHDeviceStatus.Locked).apply{liveValid=false};val s=gateway(mapOf("a" to d)).getDeviceSnapshot("a");assertEquals(LockState.UNKNOWN,s.state);assertFalse(s.fresh)}

    @Test fun refresh_readsLiveStateOnly_andSendsZeroCommands() = runBlocking {
        val a=FakeStrictSesame5Device(CHDeviceStatus.Locked);val b=FakeStrictSesame5Device(CHDeviceStatus.Unlocked);val rt=FakeRuntime(mapOf("a" to a,"b" to b),transportReady=false);val g=SesameGroupLockGateway(rt,5L,1L)
        val s=g.refreshEntranceStateSnapshot(LockGroup("e","玄関",listOf("a","b")))
        assertEquals(LockState.LOCKED,s.deviceA.state);assertEquals(LockState.UNLOCKED,s.deviceB.state);assertEquals(SnapshotConfirmation.FULL,s.confirmation);assertEquals(0,a.strictLockCalls+a.strictUnlockCalls+b.strictLockCalls+b.strictUnlockCalls);assertNoUnsafeFallback(a);assertNoUnsafeFallback(b);assertEquals(0,rt.historyTagCalls);assertEquals(1,rt.finishCalls)
    }
    @Test fun refresh_partialLiveState_marksOtherDeviceUnknown_andSendsZeroCommands() = runBlocking {
        val a=FakeStrictSesame5Device(CHDeviceStatus.Locked);val b=FakeStrictSesame5Device(CHDeviceStatus.NoBleSignal).apply{liveValid=false};val rt=FakeRuntime(mapOf("a" to a,"b" to b),transportReady=false,loginError="not available");val g=SesameGroupLockGateway(rt,5L,1L)
        val s=g.refreshEntranceStateSnapshot(LockGroup("e","玄関",listOf("a","b")))
        assertEquals(LockState.LOCKED,s.deviceA.state);assertEquals(LockState.UNKNOWN,s.deviceB.state);assertEquals(SnapshotConfirmation.PARTIAL,s.confirmation);assertEquals(0,a.strictLockCalls+a.strictUnlockCalls+b.strictLockCalls+b.strictUnlockCalls);assertNoUnsafeFallback(a);assertNoUnsafeFallback(b)
    }
    @Test fun refresh_failure_returnsUnknownWithoutLockUnlockToggleOrIoT() = runBlocking {
        val a=FakeStrictSesame5Device(CHDeviceStatus.NoBleSignal).apply{liveValid=false};val b=FakeStrictSesame5Device(CHDeviceStatus.NoBleSignal).apply{liveValid=false};val rt=FakeRuntime(mapOf("a" to a,"b" to b),transportReady=false,loginError="not available");val g=SesameGroupLockGateway(rt,5L,1L)
        val s=g.refreshEntranceStateSnapshot(LockGroup("e","玄関",listOf("a","b")))
        assertEquals(SnapshotConfirmation.NONE,s.confirmation);assertEquals(LockState.UNKNOWN,s.deviceA.state);assertEquals(LockState.UNKNOWN,s.deviceB.state);assertEquals(0,a.strictLockCalls+a.strictUnlockCalls+b.strictLockCalls+b.strictUnlockCalls);assertNoUnsafeFallback(a);assertNoUnsafeFallback(b);assertEquals(0,rt.historyTagCalls)
    }

    @Test fun transportReady_skipsScanAndLoginAndStillUsesStrictCommand() = runBlocking {
        val d=FakeStrictSesame5Device(CHDeviceStatus.Unlocked); val rt=FakeRuntime(mapOf("a" to d),transportReady=true,scanError="must not scan",loginError="must not login")
        val r=SesameGroupLockGateway(rt,5L,1L).operate("a",GroupLockAction.LOCK)
        assertTrue(r.success); assertEquals(0,rt.scanCalls); assertEquals(0,rt.loginCalls); assertEquals(1,d.strictLockCalls); assertNoUnsafeFallback(d)
    }
    @Test fun transportNotReady_scansThenWaitsForLoginBeforeStrictCommand() = runBlocking {
        val d=FakeStrictSesame5Device(CHDeviceStatus.Unlocked); val rt=FakeRuntime(mapOf("a" to d),transportReady=false)
        val r=SesameGroupLockGateway(rt,5L,1L).operate("a",GroupLockAction.UNLOCK)
        assertTrue(r.success); assertEquals(1,rt.scanCalls); assertEquals(1,rt.loginCalls); assertEquals(1,d.strictUnlockCalls); assertNoUnsafeFallback(d)
    }
    @Test fun scanFailure_failsClosedBeforeAnyCommand() = runBlocking {
        val d=FakeStrictSesame5Device(CHDeviceStatus.Unlocked); val rt=FakeRuntime(mapOf("a" to d),transportReady=false,scanError="BLE scan start timed out")
        val r=SesameGroupLockGateway(rt,5L,1L).operate("a",GroupLockAction.LOCK)
        assertFalse(r.success); assertEquals("BLE scan start timed out",r.error); assertEquals(1,rt.scanCalls); assertEquals(0,rt.loginCalls); assertEquals(0,d.strictLockCalls); assertNoUnsafeFallback(d)
    }
    @Test fun bleUnavailable_failsBeforeSendingStrictOrLegacyCommand() = runBlocking { val d=FakeStrictSesame5Device(CHDeviceStatus.Unlocked); val rt=FakeRuntime(mapOf("a" to d),loginError="BLE login timed out or disconnected"); val r=SesameGroupLockGateway(rt,5L,1L).operate("a",GroupLockAction.LOCK); assertFalse(r.success); assertEquals(0,d.strictLockCalls); assertNoUnsafeFallback(d) }
    @Test fun loginRace_strictFailureNeverFallsBack() = runBlocking { val d=FakeStrictSesame5Device(CHDeviceStatus.Unlocked).apply{strictFailure=IllegalStateException("BLE disconnected");dropBleBeforeStrictResult=true}; val r=gateway(mapOf("a" to d)).operate("a",GroupLockAction.LOCK); assertFalse(r.success); assertEquals(1,d.strictLockCalls); assertNoUnsafeFallback(d) }
    @Test fun commandAcceptedButTargetStateNotConfirmed_isFailure() = runBlocking { val d=FakeStrictSesame5Device(CHDeviceStatus.Unlocked).apply{confirmTargetState=false}; val r=gateway(mapOf("a" to d),5L).operate("a",GroupLockAction.LOCK); assertFalse(r.success); assertEquals(SesameGroupLockGateway.TARGET_NOT_CONFIRMED_ERROR,r.error); assertNoUnsafeFallback(d) }
    @Test fun unsupportedCHSesame5WithoutStrictCapability_failsClosed() = runBlocking { val d=FakeLegacySesame5(CHDeviceStatus.Unlocked); val r=gateway(mapOf("a" to d)).operate("a",GroupLockAction.LOCK); assertFalse(r.success); assertNoUnsafeFallback(d) }
    @Test fun os2Product_isExcluded() = runBlocking { val d=FakeStrictSesame5Device(CHDeviceStatus.Unlocked).apply{productModel=CHProductModel.SS2}; assertFalse(isSupportedGroupLockDevice(d)); val r=gateway(mapOf("a" to d)).operate("a",GroupLockAction.LOCK); assertFalse(r.success); assertEquals(0,d.strictLockCalls); assertNoUnsafeFallback(d) }
    @Test fun corruptedAndDeletedDeviceIds_failClosedWithoutAnyCommand() = runBlocking { val rt=FakeRuntime(emptyMap(),resolutionErrors=mapOf("bad" to IllegalArgumentException("Invalid UUID"),"gone" to IllegalStateException("Device not found"))); val g=SesameGroupLockGateway(rt,5L,1L); assertFalse(g.operate("bad",GroupLockAction.LOCK).success); assertFalse(g.operate("gone",GroupLockAction.UNLOCK).success); assertEquals(0,rt.historyTagCalls) }

    private fun gateway(devices:Map<String,CHDevices>,stateObserveTimeoutMs:Long=20L)=SesameGroupLockGateway(FakeRuntime(devices),stateObserveTimeoutMs,1L)
    private fun assertNoUnsafeFallback(d:FakeLegacySesame5){assertEquals(0,d.legacyLockCalls);assertEquals(0,d.legacyUnlockCalls);assertEquals(0,d.toggleCalls);assertEquals(0,d.iotToggleCalls)}

    private class FakeRuntime(private val devices:Map<String,CHDevices>,private val resolutionErrors:Map<String,Throwable> = emptyMap(),private val transportReady:Boolean=false,private val scanError:String?=null,private val loginError:String?=null):SesameGroupLockRuntime{
        var historyTagCalls=0; var finishCalls=0; var scanCalls=0; var loginCalls=0
        override suspend fun resolveDevice(deviceId:String):Result<CHDevices>{resolutionErrors[deviceId]?.let{return Result.failure(it)};return devices[deviceId]?.let{Result.success(it)}?:Result.failure(IllegalStateException("Device not found"))}
        override fun isStrictBleTransportReady(device:CHDevices)=transportReady
        override suspend fun ensureScan():String?{scanCalls++;return scanError}
        override suspend fun waitForBleLogin(device:CHDevices):String?{loginCalls++;return loginError}
        override suspend fun finishOperation(){finishCalls++}
        override fun historyTag():ByteArray{historyTagCalls++;return byteArrayOf(1)}
    }

    private open class FakeLegacySesame5(initialStatus:CHDeviceStatus):CHSesame5{
        override var mechStatus:CHSesameProtocolMechStatus?=null; override var deviceTimestamp:Long?=null; override var loginTimestamp:Long?=null; override var delegate:CHDeviceStatusDelegate?=null; override val multicastDelegate=CHMulticastDelegate<CHWifiModule2Delegate>(); override var deviceStatus=initialStatus; override var deviceShadowStatus:CHDeviceStatus?=null; override var rssi:Int?=0; override var bleTxPower:Byte=0; override var sensorDetectIntervalMs:Short=-1; override var lockUnlockSwitchPoint:Short=45; override var hasLockUnlockSwitchPointSetting=false; override var deviceId:UUID?=UUID.randomUUID(); override var isRegistered=true; override var productModel=CHProductModel.SS5; override var batteryPercentage:Int?=null; override var mechSetting:CHSesame5MechSettings?=null; override var opsSetting:CHSesame5OpsSettings?=null
        var legacyLockCalls=0;var legacyUnlockCalls=0;var toggleCalls=0;var iotToggleCalls=0
        override fun connect(result:CHResult<CHEmpty>){};override fun disconnect(result:CHResult<CHEmpty>){};override fun dropKey(result:CHResult<CHEmpty>){};override fun getVersionTag(result:CHResult<String>){};override fun register(result:CHResult<CHEmpty>){};override fun reset(result:CHResult<CHEmpty>){};override fun updateFirmware(onResponse:CHResult<BluetoothDevice>){}
        override fun lock(historytag:ByteArray?,result:CHResult<CHEmpty>){legacyLockCalls++;iotToggleCalls++;result(Result.failure(IllegalStateException("legacy lock path must not be used")))}
        override fun unlock(historytag:ByteArray?,result:CHResult<CHEmpty>){legacyUnlockCalls++;iotToggleCalls++;result(Result.failure(IllegalStateException("legacy unlock path must not be used")))}
        override fun toggle(historytag:ByteArray?,result:CHResult<CHEmpty>){toggleCalls++;iotToggleCalls++;result(Result.failure(IllegalStateException("toggle path must not be used")))}
        override fun magnet(result:CHResult<CHEmpty>){};override fun configureLockPosition(lockTarget:Short,unlockTarget:Short,result:CHResult<CHEmpty>){};override fun autolock(delay:Int,result:CHResult<Int>){};override fun opSensorControl(isEnable:Int,result:CHResult<Int>){}
    }
    private class FakeStrictSesame5Device(initialStatus:CHDeviceStatus):FakeLegacySesame5(initialStatus),CHSesame5StrictLock{
        var strictLockCalls=0;var strictUnlockCalls=0;var confirmTargetState=true;var strictFailure:Throwable?=null;var dropBleBeforeStrictResult=false;var liveValid=true
        private var liveLockState=if(initialStatus==CHDeviceStatus.Locked)CHSesame5LiveLockState.LOCKED else CHSesame5LiveLockState.UNLOCKED
        override fun isStrictBleTransportReady()=liveValid
        override fun liveBleSnapshot()=if(liveValid)CHSesame5LiveBleSnapshot(liveLockState,1L)else null
        override fun strictLock(historytag:ByteArray?,result:CHResult<CHEmpty>){strictLockCalls++;completeStrict(GroupLockAction.LOCK,result)}
        override fun strictUnlock(historytag:ByteArray?,result:CHResult<CHEmpty>){strictUnlockCalls++;completeStrict(GroupLockAction.UNLOCK,result)}
        private fun completeStrict(action:GroupLockAction,result:CHResult<CHEmpty>){if(dropBleBeforeStrictResult){deviceStatus=CHDeviceStatus.NoBleSignal;liveValid=false};strictFailure?.let{result(Result.failure(it));return};if(confirmTargetState){deviceStatus=if(action==GroupLockAction.LOCK)CHDeviceStatus.Locked else CHDeviceStatus.Unlocked;liveLockState=if(action==GroupLockAction.LOCK)CHSesame5LiveLockState.LOCKED else CHSesame5LiveLockState.UNLOCKED};result(Result.success(CHResultState.CHResultStateBLE(CHEmpty())))}
    }
}

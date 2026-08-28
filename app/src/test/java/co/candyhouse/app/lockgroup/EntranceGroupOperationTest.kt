package co.candyhouse.app.lockgroup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EntranceGroupOperationTest {
 @Test fun lockedLocked_selectsUnlock(){assertDecision(LockState.LOCKED,LockState.LOCKED,GroupLockAction.UNLOCK)}
 @Test fun unlockedUnlocked_selectsLock(){assertDecision(LockState.UNLOCKED,LockState.UNLOCKED,GroupLockAction.LOCK)}
 @Test fun lockedUnlocked_selectsUnlock(){assertDecision(LockState.LOCKED,LockState.UNLOCKED,GroupLockAction.UNLOCK)}
 @Test fun unlockedLocked_selectsUnlock(){assertDecision(LockState.UNLOCKED,LockState.LOCKED,GroupLockAction.UNLOCK)}
 @Test fun oneInvalid_selectsNothing(){val s=EntranceGroupStateSnapshot(EntranceDeviceSnapshot(LockState.LOCKED,fresh=true),EntranceDeviceSnapshot(LockState.UNLOCKED,fresh=false),0);assertNull(resolveEntranceExplicitActionFromFreshState(s))}
 @Test fun bothInvalid_selectsNothing(){val s=EntranceGroupStateSnapshot(EntranceDeviceSnapshot(LockState.LOCKED,fresh=false),EntranceDeviceSnapshot(LockState.UNLOCKED,fresh=false),0);assertNull(resolveEntranceExplicitActionFromFreshState(s))}
 @Test fun unknownNeverSelectsUnlock(){assertNull(resolveEntranceExplicitActionFromFreshState(EntranceGroupStateSnapshot(EntranceDeviceSnapshot(LockState.UNKNOWN,fresh=false),EntranceDeviceSnapshot(LockState.LOCKED,fresh=true),0)))}
 @Test fun liveObservationTime_isUsedAsConfirmationTime()=runBlocking{val g=RecordingGateway(mapOf("a" to snap(LockState.LOCKED,1000),"b" to snap(LockState.UNLOCKED,1234)));val s=GroupLockController(g).getEntranceStateSnapshot(LockGroup("e","玄関",listOf("a","b")));assertEquals(1234,s.capturedAt);assertEquals(SnapshotConfirmation.FULL,s.confirmation)}
 @Test fun noLiveObservation_doesNotInventConfirmationTime()=runBlocking{val g=RecordingGateway(mapOf("a" to EntranceDeviceSnapshot(LockState.UNKNOWN,fresh=false),"b" to EntranceDeviceSnapshot(LockState.UNKNOWN,fresh=false)));val s=GroupLockController(g).getEntranceStateSnapshot(LockGroup("e","玄関",listOf("a","b")));assertEquals(0,s.capturedAt);assertEquals(SnapshotConfirmation.NONE,s.confirmation)}
 @Test fun staleUnlocked_neverSelectsLockOrUnlock(){val s=EntranceGroupStateSnapshot(EntranceDeviceSnapshot(LockState.UNLOCKED,fresh=false),EntranceDeviceSnapshot(LockState.UNLOCKED,fresh=false),1);assertNull(resolveEntranceExplicitActionFromFreshState(s))}
 @Test fun cachedBatteryWithoutFreshMarker_isNotNewLiveSample(){assertFalse(isNewLiveBatterySample(EntranceDeviceSnapshot(LockState.LOCKED,batteryPercent=18,fresh=false),0))}
 @Test fun freshBattery20_isNewAndLow(){val d=EntranceDeviceSnapshot(LockState.LOCKED,20,true,10,10);assertTrue(isNewLiveBatterySample(d,0));assertTrue(shouldSendLowBatteryAlert(d.batteryPercent,false))}
 @Test fun sameBatterySample_isNotProcessedTwice(){val d=EntranceDeviceSnapshot(LockState.LOCKED,18,true,10,10);assertFalse(isNewLiveBatterySample(d,10));assertFalse(shouldSendLowBatteryAlert(d.batteryPercent,true))}
 @Test fun battery21to24_keepsAlertState(){assertFalse(shouldSendLowBatteryAlert(21,true));assertTrue(nextLowBatteryAlerted(24,true))}
 @Test fun battery25_resets(){assertFalse(nextLowBatteryAlerted(25,true))}
 @Test fun freshMixed_executesUnlockForBothDevices()=runBlocking{val g=RecordingGateway(mapOf("a" to snap(LockState.LOCKED,1),"b" to snap(LockState.UNLOCKED,2)));val c=GroupLockController(g,delayBetweenDevices={});val(_,r)=resolveAndExecuteEntranceOneButton(c,LockGroup("e","玄関",listOf("a","b")));assertNotNull(r);assertEquals(GroupLockAction.UNLOCK,r!!.action);assertEquals(listOf(GroupLockAction.UNLOCK,GroupLockAction.UNLOCK),g.actions)}
 @Test fun freshUnlocked_executesLockForBothDevices()=runBlocking{val g=RecordingGateway(mapOf("a" to snap(LockState.UNLOCKED,1),"b" to snap(LockState.UNLOCKED,2)));val c=GroupLockController(g,delayBetweenDevices={});val(_,r)=resolveAndExecuteEntranceOneButton(c,LockGroup("e","玄関",listOf("a","b")));assertNotNull(r);assertEquals(GroupLockAction.LOCK,r!!.action);assertEquals(listOf(GroupLockAction.LOCK,GroupLockAction.LOCK),g.actions)}
 @Test fun invalidState_executesZeroCommands()=runBlocking{val g=RecordingGateway(mapOf("a" to snap(LockState.LOCKED,1),"b" to EntranceDeviceSnapshot(LockState.UNLOCKED,fresh=false)));val c=GroupLockController(g,delayBetweenDevices={});val(_,r)=resolveAndExecuteEntranceOneButton(c,LockGroup("e","玄関",listOf("a","b")));assertNull(r);assertTrue(g.actions.isEmpty())}
 @Test fun busyGate_preventsSecondCommands()=runBlocking{val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val g=BlockingGateway(entered,release);val group=LockGroup("e","玄関",listOf("a"));val first=async{executeEntranceGroupAction(GroupLockController(g),group,GroupLockAction.LOCK)};entered.await();val second=executeEntranceGroupAction(GroupLockController(g),group,GroupLockAction.UNLOCK);assertEquals(GroupOperationStatus.BUSY,second.status);assertEquals(1,g.actions.size);release.complete(Unit);first.await();Unit}
 private fun assertDecision(a:LockState,b:LockState,e:GroupLockAction){val s=EntranceGroupStateSnapshot(snap(a,1),snap(b,2),2);assertEquals(e,resolveEntranceExplicitActionFromFreshState(s))}
 private fun snap(state:LockState,at:Long)=EntranceDeviceSnapshot(state,fresh=true,stateObservedAtMillis=at)
 private class RecordingGateway(private val snapshots:Map<String,EntranceDeviceSnapshot>):GroupLockGateway{val actions=mutableListOf<GroupLockAction>();override suspend fun operate(deviceId:String,action:GroupLockAction):DeviceOperationResult{actions+=action;return DeviceOperationResult(deviceId,true)};override suspend fun getState(deviceId:String)=snapshots[deviceId]?.state?:LockState.UNKNOWN;override suspend fun getDeviceSnapshot(deviceId:String)=snapshots[deviceId]?:EntranceDeviceSnapshot(LockState.UNKNOWN,fresh=false)}
 private class BlockingGateway(private val entered:CompletableDeferred<Unit>,private val release:CompletableDeferred<Unit>):GroupLockGateway{val actions=mutableListOf<GroupLockAction>();override suspend fun operate(deviceId:String,action:GroupLockAction):DeviceOperationResult{actions+=action;entered.complete(Unit);release.await();return DeviceOperationResult(deviceId,true)};override suspend fun getState(deviceId:String)=LockState.UNKNOWN;override suspend fun getDeviceSnapshot(deviceId:String)=EntranceDeviceSnapshot(LockState.UNKNOWN,fresh=false)}
}

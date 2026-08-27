package co.candyhouse.app.lockgroup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EntranceGroupOperationTest {
 @Test fun lockedLocked_selectsUnlock() { assertDecision(LockState.LOCKED,LockState.LOCKED,GroupLockAction.UNLOCK) }
 @Test fun unlockedUnlocked_selectsLock() { assertDecision(LockState.UNLOCKED,LockState.UNLOCKED,GroupLockAction.LOCK) }
 @Test fun lockedUnlocked_selectsUnlock() { assertDecision(LockState.LOCKED,LockState.UNLOCKED,GroupLockAction.UNLOCK) }
 @Test fun unlockedLocked_selectsUnlock() { assertDecision(LockState.UNLOCKED,LockState.LOCKED,GroupLockAction.UNLOCK) }
 @Test fun unknownUnknown_selectsUnlock() { assertDecision(LockState.UNKNOWN,LockState.UNKNOWN,GroupLockAction.UNLOCK) }
 @Test fun lockedUnknown_selectsUnlock() { assertDecision(LockState.LOCKED,LockState.UNKNOWN,GroupLockAction.UNLOCK) }
 @Test fun unlockedUnknown_selectsUnlock() { assertDecision(LockState.UNLOCKED,LockState.UNKNOWN,GroupLockAction.UNLOCK) }
 @Test fun fullFreshSnapshot_hasCapturedTime()=runBlocking{val c=GroupLockController(RecordingGateway(mapOf("a" to LockState.LOCKED,"b" to LockState.UNLOCKED)),nowMillis={1234});val s=c.getEntranceStateSnapshot(LockGroup("e","玄関",listOf("a","b")));assertEquals(1234,s.capturedAt);assertEquals(SnapshotConfirmation.FULL,s.confirmation)}
 @Test fun oneUnknown_isPartial(){val s=EntranceGroupStateSnapshot(EntranceDeviceSnapshot(LockState.LOCKED,fresh=true),EntranceDeviceSnapshot(LockState.UNKNOWN,fresh=false),99);assertEquals(SnapshotConfirmation.PARTIAL,s.confirmation)}
 @Test fun bothUnavailable_isNone(){val s=EntranceGroupStateSnapshot(EntranceDeviceSnapshot(LockState.UNKNOWN,fresh=false),EntranceDeviceSnapshot(LockState.UNKNOWN,fresh=false),99);assertEquals(SnapshotConfirmation.NONE,s.confirmation)}
 @Test fun staleUnlocked_neverSelectsLock(){val s=EntranceGroupStateSnapshot(EntranceDeviceSnapshot(LockState.UNLOCKED,fresh=false),EntranceDeviceSnapshot(LockState.UNLOCKED,fresh=false),1);assertEquals(GroupLockAction.UNLOCK,resolveEntranceExplicitActionFromFreshState(s))}
 @Test fun battery20_isLow(){assertTrue(shouldSendLowBatteryAlert(20,false))}
 @Test fun battery21_isNotLow(){assertFalse(shouldSendLowBatteryAlert(21,false))}
 @Test fun repeatedLow_doesNotAlert(){assertFalse(shouldSendLowBatteryAlert(18,true))}
 @Test fun battery25_resets(){assertFalse(nextLowBatteryAlerted(25,true))}
 @Test fun resetAllowsLaterAlert(){val reset=nextLowBatteryAlerted(25,true);assertTrue(shouldSendLowBatteryAlert(20,reset))}
 @Test fun nullBattery_neverAlerts(){assertFalse(shouldSendLowBatteryAlert(null,false));assertFalse(nextLowBatteryAlerted(null,false))}
 @Test fun freshMixed_executesUnlockForBothDevices()=runBlocking{val g=RecordingGateway(mapOf("a" to LockState.LOCKED,"b" to LockState.UNLOCKED));val c=GroupLockController(g,delayBetweenDevices={});val(_,r)=resolveAndExecuteEntranceOneButton(c,LockGroup("e","玄関",listOf("a","b")));assertEquals(GroupLockAction.UNLOCK,r.action);assertEquals(listOf(GroupLockAction.UNLOCK,GroupLockAction.UNLOCK),g.actions)}
 @Test fun freshUnlocked_executesLockForBothDevices()=runBlocking{val g=RecordingGateway(mapOf("a" to LockState.UNLOCKED,"b" to LockState.UNLOCKED));val c=GroupLockController(g,delayBetweenDevices={});val(_,r)=resolveAndExecuteEntranceOneButton(c,LockGroup("e","玄関",listOf("a","b")));assertEquals(GroupLockAction.LOCK,r.action);assertEquals(listOf(GroupLockAction.LOCK,GroupLockAction.LOCK),g.actions)}
 @Test fun busyGate_preventsSecondCommands()=runBlocking{val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val g=BlockingGateway(entered,release);val group=LockGroup("e","玄関",listOf("a"));val first=async{executeEntranceGroupAction(GroupLockController(g),group,GroupLockAction.LOCK)};entered.await();val second=executeEntranceGroupAction(GroupLockController(g),group,GroupLockAction.UNLOCK);assertEquals(GroupOperationStatus.BUSY,second.status);assertEquals(1,g.actions.size);release.complete(Unit);first.await()}
 private fun assertDecision(a:LockState,b:LockState,e:GroupLockAction){assertEquals(e,resolveEntranceExplicitActionFromFreshState(EntranceGroupStateSnapshot(a,b)))}
 private class RecordingGateway(private val states:Map<String,LockState>):GroupLockGateway{val actions=mutableListOf<GroupLockAction>();override suspend fun operate(deviceId:String,action:GroupLockAction):DeviceOperationResult{actions+=action;return DeviceOperationResult(deviceId,true)};override suspend fun getState(deviceId:String)=states[deviceId]?:LockState.UNKNOWN;override suspend fun getDeviceSnapshot(deviceId:String):EntranceDeviceSnapshot{val s=getState(deviceId);return EntranceDeviceSnapshot(s,fresh=s!=LockState.UNKNOWN)}}
 private class BlockingGateway(private val entered:CompletableDeferred<Unit>,private val release:CompletableDeferred<Unit>):GroupLockGateway{val actions=mutableListOf<GroupLockAction>();override suspend fun operate(deviceId:String,action:GroupLockAction):DeviceOperationResult{actions+=action;entered.complete(Unit);release.await();return DeviceOperationResult(deviceId,true)};override suspend fun getState(deviceId:String)=LockState.UNKNOWN}
}

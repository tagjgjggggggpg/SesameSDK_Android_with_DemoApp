package co.candyhouse.app.lockgroup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class WearPhoneSafetyTest {
    private val group = LockGroup("entrance", "玄関", listOf("a", "b"))

    @Test fun watchFreshUnlockedUnlocked_usesExistingResolverAndExplicitLockTwice() = runBlocking {
        val gateway = RecordingGateway(mapOf("a" to live(LockState.UNLOCKED), "b" to live(LockState.UNLOCKED)))
        val (_, result) = resolveAndExecuteEntranceOneButton(GroupLockController(gateway), group)
        assertEquals(GroupLockAction.LOCK, result!!.action)
        assertEquals(listOf(GroupLockAction.LOCK, GroupLockAction.LOCK), gateway.actions.toList())
    }

    @Test fun watchFreshLockedLocked_usesExistingResolverAndExplicitUnlockTwice() = runBlocking {
        val gateway = RecordingGateway(mapOf("a" to live(LockState.LOCKED), "b" to live(LockState.LOCKED)))
        val (_, result) = resolveAndExecuteEntranceOneButton(GroupLockController(gateway), group)
        assertEquals(GroupLockAction.UNLOCK, result!!.action)
        assertEquals(listOf(GroupLockAction.UNLOCK, GroupLockAction.UNLOCK), gateway.actions.toList())
    }

    @Test fun watchFreshMixed_usesExplicitUnlockTwice() = runBlocking {
        val gateway = RecordingGateway(mapOf("a" to live(LockState.LOCKED), "b" to live(LockState.UNLOCKED)))
        val (_, result) = resolveAndExecuteEntranceOneButton(GroupLockController(gateway), group)
        assertEquals(GroupLockAction.UNLOCK, result!!.action)
        assertEquals(listOf(GroupLockAction.UNLOCK, GroupLockAction.UNLOCK), gateway.actions.toList())
    }

    @Test fun watchUnknown_executesZeroCommands() = runBlocking {
        val gateway = RecordingGateway(mapOf("a" to live(LockState.LOCKED), "b" to EntranceDeviceSnapshot(LockState.UNKNOWN, fresh = false)))
        val (_, result) = resolveAndExecuteEntranceOneButton(GroupLockController(gateway), group)
        assertNull(result)
        assertTrue(gateway.actions.isEmpty())
    }

    @Test fun watchBusy_returnsBusyAndDispatchesNoAdditionalBleOperation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val firstGateway = BlockingGateway(entered, release)
        val first = async { GroupLockController(firstGateway).lockGroup(LockGroup("first", "first", listOf("x"))) }
        entered.await()

        val secondGateway = RecordingGateway(mapOf("a" to live(LockState.UNLOCKED), "b" to live(LockState.UNLOCKED)))
        val (_, secondResult) = resolveAndExecuteEntranceOneButton(GroupLockController(secondGateway), group)

        assertEquals(GroupOperationStatus.BUSY, secondResult!!.status)
        assertTrue(secondGateway.actions.isEmpty())
        assertEquals("BUSY", wearResponseFromOperation("busy-id", "entrance", secondResult).overallStatus)
        release.complete(Unit)
        assertEquals(GroupOperationStatus.SUCCESS, first.await().status)
    }

    @Test fun phoneStateSyncCopiesDisplayStateOnly() {
        val snapshot = EntranceGroupStateSnapshot(live(LockState.LOCKED, 100L), live(LockState.UNLOCKED, 200L), 200L)
        val synced = WearGroupStateSync.fromSnapshot(snapshot)
        assertEquals("MIXED", synced.groupState)
        assertEquals("LOCKED", synced.deviceAState)
        assertEquals("UNLOCKED", synced.deviceBState)
        assertEquals(200L, synced.confirmedAt)
        assertEquals(GroupLockAction.UNLOCK, resolveEntranceExplicitActionFromFreshState(snapshot))
        val cachedEquivalent = snapshot.copy(
            deviceA = snapshot.deviceA.copy(fresh = false),
            deviceB = snapshot.deviceB.copy(fresh = false),
        )
        assertNull(resolveEntranceExplicitActionFromFreshState(cachedEquivalent))
    }

    private fun live(state: LockState, at: Long = 1L) = EntranceDeviceSnapshot(state, fresh = true, stateObservedAtMillis = at)

    private class RecordingGateway(private val snapshots: Map<String, EntranceDeviceSnapshot>) : GroupLockGateway {
        val actions = Collections.synchronizedList(mutableListOf<GroupLockAction>())
        override suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult {
            actions += action
            return DeviceOperationResult(deviceId, true, finalKnownState = if (action == GroupLockAction.LOCK) LockState.LOCKED else LockState.UNLOCKED)
        }
        override suspend fun getState(deviceId: String) = snapshots[deviceId]?.state ?: LockState.UNKNOWN
        override suspend fun getDeviceSnapshot(deviceId: String) = snapshots[deviceId] ?: EntranceDeviceSnapshot(LockState.UNKNOWN, fresh = false)
    }

    private class BlockingGateway(
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : GroupLockGateway {
        override suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult {
            entered.complete(Unit)
            release.await()
            return DeviceOperationResult(deviceId, true)
        }
        override suspend fun getState(deviceId: String) = LockState.UNKNOWN
        override suspend fun getDeviceSnapshot(deviceId: String) = EntranceDeviceSnapshot(LockState.UNKNOWN, fresh = false)
    }
}

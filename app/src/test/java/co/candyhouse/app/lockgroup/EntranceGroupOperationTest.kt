package co.candyhouse.app.lockgroup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EntranceGroupOperationTest {
    @Test fun lockedLocked_selectsUnlock() = assertDecision(LockState.LOCKED, LockState.LOCKED, GroupLockAction.UNLOCK)
    @Test fun unlockedUnlocked_selectsLock() = assertDecision(LockState.UNLOCKED, LockState.UNLOCKED, GroupLockAction.LOCK)
    @Test fun lockedUnlocked_selectsUnlock() = assertDecision(LockState.LOCKED, LockState.UNLOCKED, GroupLockAction.UNLOCK)
    @Test fun unlockedLocked_selectsUnlock() = assertDecision(LockState.UNLOCKED, LockState.LOCKED, GroupLockAction.UNLOCK)
    @Test fun unknownUnknown_selectsUnlock() = assertDecision(LockState.UNKNOWN, LockState.UNKNOWN, GroupLockAction.UNLOCK)
    @Test fun lockedUnknown_selectsUnlock() = assertDecision(LockState.LOCKED, LockState.UNKNOWN, GroupLockAction.UNLOCK)
    @Test fun unlockedUnknown_selectsUnlock() = assertDecision(LockState.UNLOCKED, LockState.UNKNOWN, GroupLockAction.UNLOCK)

    @Test fun cachedUnlocked_doesNotOverrideFreshUnknown() {
        val cached = EntranceGroupStateSnapshot(LockState.UNLOCKED, LockState.UNLOCKED)
        val fresh = EntranceGroupStateSnapshot(LockState.UNKNOWN, LockState.UNKNOWN)
        assertEquals(GroupLockAction.LOCK, resolveEntranceExplicitActionFromFreshState(cached))
        assertEquals(GroupLockAction.UNLOCK, resolveEntranceExplicitActionFromFreshState(fresh))
    }

    @Test fun cachedLocked_doesNotOverrideFreshUnlocked() {
        val cached = EntranceGroupStateSnapshot(LockState.LOCKED, LockState.LOCKED)
        val fresh = EntranceGroupStateSnapshot(LockState.UNLOCKED, LockState.UNLOCKED)
        assertEquals(GroupLockAction.UNLOCK, resolveEntranceExplicitActionFromFreshState(cached))
        assertEquals(GroupLockAction.LOCK, resolveEntranceExplicitActionFromFreshState(fresh))
    }

    @Test fun freshMixed_executesUnlockForBothDevices() = runBlocking {
        val gateway = RecordingGateway(mapOf("a" to LockState.LOCKED, "b" to LockState.UNLOCKED))
        val controller = GroupLockController(gateway, delayBetweenDevices = {})
        val group = LockGroup("entrance", "玄関", listOf("a", "b"))
        val (fresh, result) = resolveAndExecuteEntranceOneButton(controller, group)
        assertEquals(GroupState.MIXED, fresh.groupState)
        assertEquals(GroupLockAction.UNLOCK, result.action)
        assertEquals(listOf(GroupLockAction.UNLOCK, GroupLockAction.UNLOCK), gateway.actions)
    }

    @Test fun freshUnknown_executesUnlockForBothDevices() = runBlocking {
        val gateway = RecordingGateway(mapOf("a" to LockState.UNKNOWN, "b" to LockState.LOCKED))
        val controller = GroupLockController(gateway, delayBetweenDevices = {})
        val group = LockGroup("entrance", "玄関", listOf("a", "b"))
        val (_, result) = resolveAndExecuteEntranceOneButton(controller, group)
        assertEquals(GroupLockAction.UNLOCK, result.action)
        assertEquals(listOf(GroupLockAction.UNLOCK, GroupLockAction.UNLOCK), gateway.actions)
    }

    @Test fun freshUnlocked_executesLockForBothDevices() = runBlocking {
        val gateway = RecordingGateway(mapOf("a" to LockState.UNLOCKED, "b" to LockState.UNLOCKED))
        val controller = GroupLockController(gateway, delayBetweenDevices = {})
        val group = LockGroup("entrance", "玄関", listOf("a", "b"))
        val (_, result) = resolveAndExecuteEntranceOneButton(controller, group)
        assertEquals(GroupLockAction.LOCK, result.action)
        assertEquals(listOf(GroupLockAction.LOCK, GroupLockAction.LOCK), gateway.actions)
    }

    @Test fun busyGate_preventsSecondEntryPointFromSendingCommands() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val gateway = BlockingGateway(entered, release)
        val group = LockGroup("entrance", "玄関", listOf("a"))
        val first = async { executeEntranceGroupAction(GroupLockController(gateway), group, GroupLockAction.LOCK) }
        entered.await()
        val second = executeEntranceGroupAction(GroupLockController(gateway), group, GroupLockAction.UNLOCK)
        assertEquals(GroupOperationStatus.BUSY, second.status)
        assertEquals(listOf(GroupLockAction.LOCK), gateway.actions)
        release.complete(Unit); first.await()
    }

    private fun assertDecision(a: LockState, b: LockState, expected: GroupLockAction) = assertEquals(expected, resolveEntranceExplicitActionFromFreshState(EntranceGroupStateSnapshot(a, b)))

    private class RecordingGateway(private val states: Map<String, LockState>) : GroupLockGateway {
        val actions = mutableListOf<GroupLockAction>()
        override suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult { actions += action; return DeviceOperationResult(deviceId, true, finalKnownState = if (action == GroupLockAction.LOCK) LockState.LOCKED else LockState.UNLOCKED) }
        override suspend fun getState(deviceId: String) = states[deviceId] ?: LockState.UNKNOWN
    }
    private class BlockingGateway(private val entered: CompletableDeferred<Unit>, private val release: CompletableDeferred<Unit>) : GroupLockGateway {
        val actions = mutableListOf<GroupLockAction>()
        override suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult { actions += action; entered.complete(Unit); release.await(); return DeviceOperationResult(deviceId, true) }
        override suspend fun getState(deviceId: String) = LockState.UNKNOWN
    }
}

package co.candyhouse.app.lockgroup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EntranceGroupOperationTest {
    @Test fun quickSettingsLock_isAlwaysExplicitLock() { assertEquals(GroupLockAction.LOCK, EntranceGroupCommand.resolve(EntranceGroupCommand.QS_LOCK)) }
    @Test fun quickSettingsUnlock_isAlwaysExplicitUnlock() { assertEquals(GroupLockAction.UNLOCK, EntranceGroupCommand.resolve(EntranceGroupCommand.QS_UNLOCK)) }
    @Test fun widgetLock_isAlwaysExplicitLock() { assertEquals(GroupLockAction.LOCK, EntranceGroupCommand.resolve(EntranceGroupCommand.WIDGET_LOCK)) }
    @Test fun widgetUnlock_isAlwaysExplicitUnlock() { assertEquals(GroupLockAction.UNLOCK, EntranceGroupCommand.resolve(EntranceGroupCommand.WIDGET_UNLOCK)) }

    @Test fun mixedAndUnknownState_doNotChangeExplicitTarget() = runBlocking {
        val gateway = RecordingGateway(mapOf("a" to LockState.LOCKED, "b" to LockState.UNLOCKED, "c" to LockState.UNKNOWN))
        val controller = GroupLockController(gateway, delayBetweenDevices = {})
        val group = LockGroup("entrance", "玄関", listOf("a", "b", "c"))
        executeEntranceGroupAction(controller, group, GroupLockAction.LOCK)
        assertEquals(listOf(GroupLockAction.LOCK, GroupLockAction.LOCK, GroupLockAction.LOCK), gateway.actions)
        gateway.actions.clear()
        executeEntranceGroupAction(controller, group, GroupLockAction.UNLOCK)
        assertEquals(listOf(GroupLockAction.UNLOCK, GroupLockAction.UNLOCK, GroupLockAction.UNLOCK), gateway.actions)
    }

    @Test fun busyGate_preventsSecondEntryPointFromSendingCommands() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gateway = BlockingGateway(entered, release)
            val group = LockGroup("entrance", "玄関", listOf("a"))
            val first = async { executeEntranceGroupAction(GroupLockController(gateway), group, GroupLockAction.LOCK) }
            entered.await()
            val second = executeEntranceGroupAction(GroupLockController(gateway), group, GroupLockAction.UNLOCK)
            assertEquals(GroupOperationStatus.BUSY, second.status)
            assertEquals(listOf(GroupLockAction.LOCK), gateway.actions)
            release.complete(Unit)
            first.await()
        }
    }

    private class RecordingGateway(private val states: Map<String, LockState>) : GroupLockGateway {
        val actions = mutableListOf<GroupLockAction>()
        override suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult {
            actions += action
            return DeviceOperationResult(deviceId, true, finalKnownState = if (action == GroupLockAction.LOCK) LockState.LOCKED else LockState.UNLOCKED)
        }
        override suspend fun getState(deviceId: String) = states[deviceId] ?: LockState.UNKNOWN
    }

    private class BlockingGateway(private val entered: CompletableDeferred<Unit>, private val release: CompletableDeferred<Unit>) : GroupLockGateway {
        val actions = mutableListOf<GroupLockAction>()
        override suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult {
            actions += action
            entered.complete(Unit)
            release.await()
            return DeviceOperationResult(deviceId, true)
        }
        override suspend fun getState(deviceId: String) = LockState.UNKNOWN
    }
}

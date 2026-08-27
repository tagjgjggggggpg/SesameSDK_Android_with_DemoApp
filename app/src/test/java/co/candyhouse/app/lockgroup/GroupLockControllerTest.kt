package co.candyhouse.app.lockgroup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupLockControllerTest {
    private val group = LockGroup("entrance", "玄関", listOf("device-a", "device-b"))

    @Test
    fun twoDevices_lockAndUnlock_allSuccess() = runBlocking {
        val gateway = FakeGateway(
            initialStates = mutableMapOf(
                "device-a" to LockState.UNLOCKED,
                "device-b" to LockState.UNLOCKED,
            )
        )
        val controller = controller(gateway)

        val lockResult = controller.lockGroup(group)
        val unlockResult = controller.unlockGroup(group)

        assertEquals(GroupOperationStatus.SUCCESS, lockResult.status)
        assertEquals(GroupOperationStatus.SUCCESS, unlockResult.status)
        assertEquals(4, gateway.commandCalls.size)
        assertEquals(LockState.UNLOCKED, gateway.states["device-a"])
        assertEquals(LockState.UNLOCKED, gateway.states["device-b"])
    }

    @Test
    fun partialFailure_isReportedPerDevice() = runBlocking {
        val gateway = FakeGateway(
            initialStates = mutableMapOf(
                "device-a" to LockState.UNLOCKED,
                "device-b" to LockState.UNLOCKED,
            ),
            failures = mutableMapOf("device-b" to "BLE disconnected"),
        )
        val result = controller(gateway).lockGroup(group)

        assertEquals(GroupOperationStatus.PARTIAL, result.status)
        assertTrue(result.deviceResults.first { it.deviceId == "device-a" }.success)
        assertEquals("BLE disconnected", result.deviceResults.first { it.deviceId == "device-b" }.error)
    }

    @Test
    fun alreadyLockedOrUnlocked_succeedsWithoutSendingAnotherCommand() = runBlocking {
        val gateway = FakeGateway(
            initialStates = mutableMapOf(
                "device-a" to LockState.LOCKED,
                "device-b" to LockState.LOCKED,
            )
        )
        val controller = controller(gateway)

        val locked = controller.lockGroup(group)
        assertEquals(GroupOperationStatus.SUCCESS, locked.status)
        assertTrue(gateway.commandCalls.isEmpty())

        gateway.states["device-a"] = LockState.UNLOCKED
        gateway.states["device-b"] = LockState.UNLOCKED
        val unlocked = controller.unlockGroup(group)
        assertEquals(GroupOperationStatus.SUCCESS, unlocked.status)
        assertTrue(gateway.commandCalls.isEmpty())
    }

    @Test
    fun bleDisconnected_deviceFailsClosed() = runBlocking {
        val gateway = FakeGateway(
            initialStates = mutableMapOf("device-a" to LockState.UNKNOWN),
            failures = mutableMapOf("device-a" to "BLE disconnected"),
        )
        val result = controller(gateway).unlockGroup(LockGroup("entrance", "玄関", listOf("device-a")))

        assertEquals(GroupOperationStatus.FAILURE, result.status)
        assertEquals("BLE disconnected", result.deviceResults.single().error)
        assertTrue(gateway.commandCalls.isEmpty())
    }

    @Test
    fun missingDeviceId_isExplicitFailure() = runBlocking {
        val gateway = FakeGateway(
            initialStates = mutableMapOf(),
            failures = mutableMapOf("missing-id" to "Device not found"),
        )
        val result = controller(gateway).lockGroup(LockGroup("entrance", "玄関", listOf("missing-id")))

        assertEquals(GroupOperationStatus.FAILURE, result.status)
        assertEquals("missing-id", result.deviceResults.single().deviceId)
        assertEquals("Device not found", result.deviceResults.single().error)
    }

    @Test
    fun deletedStoredDeviceId_isNotSilentlySkipped() = runBlocking {
        val gateway = FakeGateway(
            initialStates = mutableMapOf("device-a" to LockState.UNLOCKED),
            failures = mutableMapOf("deleted-id" to "Device not found"),
        )
        val storedGroup = LockGroup("entrance", "玄関", listOf("device-a", "deleted-id"))
        val result = controller(gateway).lockGroup(storedGroup)

        assertEquals(GroupOperationStatus.PARTIAL, result.status)
        assertEquals(2, result.deviceResults.size)
        assertEquals("deleted-id", result.deviceResults[1].deviceId)
        assertEquals("Device not found", result.deviceResults[1].error)
    }

    @Test
    fun secondTapWhileOperationIsInFlight_returnsBusyWithoutSecondDispatch() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            initialStates = mutableMapOf("device-a" to LockState.UNLOCKED),
            block = release,
            entered = entered,
        )
        val controller = controller(gateway)
        val oneDeviceGroup = LockGroup("entrance", "玄関", listOf("device-a"))

        val first = async { controller.lockGroup(oneDeviceGroup) }
        entered.await()
        val second = controller.unlockGroup(oneDeviceGroup)

        assertEquals(GroupOperationStatus.BUSY, second.status)
        assertEquals(0, second.deviceResults.size)

        release.complete(Unit)
        assertEquals(GroupOperationStatus.SUCCESS, first.await().status)
        assertEquals(1, gateway.commandCalls.size)
    }

    @Test
    fun aggregateState_distinguishesLockedUnlockedMixedAndUnknown() {
        assertEquals(GroupState.LOCKED, aggregateGroupState(listOf(LockState.LOCKED, LockState.LOCKED)))
        assertEquals(GroupState.UNLOCKED, aggregateGroupState(listOf(LockState.UNLOCKED, LockState.UNLOCKED)))
        assertEquals(GroupState.MIXED, aggregateGroupState(listOf(LockState.LOCKED, LockState.UNLOCKED)))
        assertEquals(GroupState.MIXED, aggregateGroupState(listOf(LockState.LOCKED, LockState.UNKNOWN)))
        assertEquals(GroupState.UNKNOWN, aggregateGroupState(listOf(LockState.UNKNOWN, LockState.UNKNOWN)))
    }

    private fun controller(gateway: GroupLockGateway) = GroupLockController(
        gateway = gateway,
        interDeviceDelayMs = 500L,
        delayBetweenDevices = { },
    )

    private class FakeGateway(
        initialStates: MutableMap<String, LockState>,
        private val failures: MutableMap<String, String> = mutableMapOf(),
        private val block: CompletableDeferred<Unit>? = null,
        private val entered: CompletableDeferred<Unit>? = null,
    ) : GroupLockGateway {
        val states = initialStates
        val commandCalls = mutableListOf<Pair<String, GroupLockAction>>()

        override suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult {
            entered?.complete(Unit)
            block?.await()

            failures[deviceId]?.let { error ->
                return DeviceOperationResult(
                    deviceId = deviceId,
                    success = false,
                    error = error,
                    finalKnownState = states[deviceId] ?: LockState.UNKNOWN,
                )
            }

            val target = if (action == GroupLockAction.LOCK) LockState.LOCKED else LockState.UNLOCKED
            if (states[deviceId] == target) {
                return DeviceOperationResult(deviceId, true, finalKnownState = target)
            }

            commandCalls += deviceId to action
            states[deviceId] = target
            return DeviceOperationResult(deviceId, true, finalKnownState = target)
        }

        override suspend fun getState(deviceId: String): LockState = states[deviceId] ?: LockState.UNKNOWN
    }
}

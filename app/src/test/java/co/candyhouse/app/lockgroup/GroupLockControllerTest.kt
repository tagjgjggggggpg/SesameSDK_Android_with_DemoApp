package co.candyhouse.app.lockgroup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupLockControllerTest {
    private val twoDeviceGroup = LockGroup("entrance", "玄関", listOf("device-a", "device-b"))
    private val threeDeviceGroup = LockGroup("entrance", "玄関", listOf("device-a", "device-b", "device-c"))

    @Test
    fun twoDevices_lockAndUnlock_allSuccessAndAlwaysDispatchExplicitAction() = runBlocking {
        val gateway = FakeGateway(
            initialStates = mutableMapOf(
                "device-a" to LockState.UNLOCKED,
                "device-b" to LockState.UNLOCKED,
            )
        )
        val controller = controller(gateway)

        val lockResult = controller.lockGroup(twoDeviceGroup)
        val unlockResult = controller.unlockGroup(twoDeviceGroup)

        assertEquals(GroupOperationStatus.SUCCESS, lockResult.status)
        assertEquals(GroupOperationStatus.SUCCESS, unlockResult.status)
        assertEquals(
            listOf(
                "device-a" to GroupLockAction.LOCK,
                "device-b" to GroupLockAction.LOCK,
                "device-a" to GroupLockAction.UNLOCK,
                "device-b" to GroupLockAction.UNLOCK,
            ),
            gateway.operationCalls,
        )
    }

    @Test
    fun mixedState_lock_dispatchesExplicitLockToEveryDeviceIncludingAlreadyLocked() = runBlocking {
        val gateway = FakeGateway(
            initialStates = mutableMapOf(
                "device-a" to LockState.LOCKED,
                "device-b" to LockState.UNLOCKED,
            )
        )

        val result = controller(gateway).lockGroup(twoDeviceGroup)

        assertEquals(GroupOperationStatus.SUCCESS, result.status)
        assertEquals(
            listOf(
                "device-a" to GroupLockAction.LOCK,
                "device-b" to GroupLockAction.LOCK,
            ),
            gateway.operationCalls,
        )
    }

    @Test
    fun mixedState_unlock_dispatchesExplicitUnlockToEveryDeviceIncludingAlreadyUnlocked() = runBlocking {
        val gateway = FakeGateway(
            initialStates = mutableMapOf(
                "device-a" to LockState.LOCKED,
                "device-b" to LockState.UNLOCKED,
            )
        )

        val result = controller(gateway).unlockGroup(twoDeviceGroup)

        assertEquals(GroupOperationStatus.SUCCESS, result.status)
        assertEquals(
            listOf(
                "device-a" to GroupLockAction.UNLOCK,
                "device-b" to GroupLockAction.UNLOCK,
            ),
            gateway.operationCalls,
        )
    }

    @Test
    fun emptyGroup_failsWithoutDispatch() = runBlocking {
        val gateway = FakeGateway(mutableMapOf())

        val result = controller(gateway).lockGroup(LockGroup("entrance", "玄関", emptyList()))

        assertEquals(GroupOperationStatus.FAILURE, result.status)
        assertTrue(result.operationResultsAreEmpty())
        assertTrue(gateway.operationCalls.isEmpty())
        assertEquals(1, gateway.finishCalls)
    }

    @Test
    fun threeDevices_preservesUuidOrder_andUses500msBetweenDevices() = runBlocking {
        val gateway = FakeGateway(
            mutableMapOf(
                "device-a" to LockState.UNLOCKED,
                "device-b" to LockState.UNLOCKED,
                "device-c" to LockState.UNLOCKED,
            )
        )
        val delays = mutableListOf<Long>()
        val controller = GroupLockController(
            gateway = gateway,
            interDeviceDelayMs = 500L,
            delayBetweenDevices = { delays += it },
        )

        val result = controller.lockGroup(threeDeviceGroup)

        assertEquals(GroupOperationStatus.SUCCESS, result.status)
        assertEquals(
            listOf("device-a", "device-b", "device-c"),
            gateway.operationCalls.map { it.first },
        )
        assertEquals(listOf(500L, 500L), delays)
    }

    @Test
    fun firstDeviceFailure_doesNotStopRemainingDevices() = runBlocking {
        val gateway = FakeGateway(
            initialStates = mutableMapOf(
                "device-a" to LockState.UNLOCKED,
                "device-b" to LockState.UNLOCKED,
                "device-c" to LockState.UNLOCKED,
            ),
            failures = mapOf("device-a" to "BLE disconnected"),
        )

        val result = controller(gateway).lockGroup(threeDeviceGroup)

        assertEquals(GroupOperationStatus.PARTIAL, result.status)
        assertEquals(listOf("device-a", "device-b", "device-c"), gateway.operationCalls.map { it.first })
        assertEquals("BLE disconnected", result.deviceResults[0].error)
        assertTrue(result.deviceResults[1].success)
        assertTrue(result.deviceResults[2].success)
    }

    @Test
    fun middleDeviceFailure_doesNotStopRemainingDevices() = runBlocking {
        val gateway = FakeGateway(
            initialStates = mutableMapOf(
                "device-a" to LockState.UNLOCKED,
                "device-b" to LockState.UNLOCKED,
                "device-c" to LockState.UNLOCKED,
            ),
            failures = mapOf("device-b" to "BLE disconnected"),
        )

        val result = controller(gateway).lockGroup(threeDeviceGroup)

        assertEquals(GroupOperationStatus.PARTIAL, result.status)
        assertEquals(listOf("device-a", "device-b", "device-c"), gateway.operationCalls.map { it.first })
        assertTrue(result.deviceResults[0].success)
        assertEquals("BLE disconnected", result.deviceResults[1].error)
        assertTrue(result.deviceResults[2].success)
    }

    @Test
    fun gatewayException_isPerDeviceFailureAndRemainingDevicesContinue() = runBlocking {
        val gateway = FakeGateway(
            initialStates = mutableMapOf(
                "device-a" to LockState.UNLOCKED,
                "device-b" to LockState.UNLOCKED,
                "device-c" to LockState.UNLOCKED,
            ),
            throwOn = setOf("device-b"),
        )

        val result = controller(gateway).lockGroup(threeDeviceGroup)

        assertEquals(GroupOperationStatus.PARTIAL, result.status)
        assertEquals(listOf("device-a", "device-b", "device-c"), gateway.operationCalls.map { it.first })
        assertEquals("boom-device-b", result.deviceResults[1].error)
        assertTrue(result.deviceResults[2].success)
    }

    @Test
    fun separateControllerInstanceWhileOperationInFlight_returnsBusyWithoutSecondDispatch() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val firstGateway = FakeGateway(
            initialStates = mutableMapOf("device-a" to LockState.UNLOCKED),
            block = release,
            entered = entered,
        )
        val secondGateway = FakeGateway(mutableMapOf("device-a" to LockState.LOCKED))
        val firstController = controller(firstGateway)
        val secondController = controller(secondGateway)
        val group = LockGroup("entrance", "玄関", listOf("device-a"))

        val first = async { firstController.lockGroup(group) }
        entered.await()

        val busy = secondController.unlockGroup(group)

        assertEquals(GroupOperationStatus.BUSY, busy.status)
        assertTrue(secondGateway.operationCalls.isEmpty())

        release.complete(Unit)
        assertEquals(GroupOperationStatus.SUCCESS, first.await().status)

        val afterRelease = secondController.unlockGroup(group)
        assertEquals(GroupOperationStatus.SUCCESS, afterRelease.status)
        assertEquals(listOf("device-a" to GroupLockAction.UNLOCK), secondGateway.operationCalls)
    }

    @Test
    fun cancellation_releasesProcessWideGate() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val blockingGateway = FakeGateway(
            initialStates = mutableMapOf("device-a" to LockState.UNLOCKED),
            block = release,
            entered = entered,
        )
        val nextGateway = FakeGateway(mutableMapOf("device-a" to LockState.UNLOCKED))
        val group = LockGroup("entrance", "玄関", listOf("device-a"))

        val first = async { controller(blockingGateway).lockGroup(group) }
        entered.await()
        first.cancelAndJoin()

        val next = controller(nextGateway).lockGroup(group)

        assertEquals(GroupOperationStatus.SUCCESS, next.status)
        assertEquals(1, blockingGateway.finishCalls)
        assertEquals(1, nextGateway.operationCalls.size)
    }

    @Test
    fun finishOperationException_stillReleasesProcessWideGate() = runBlocking {
        val failingFinishGateway = FakeGateway(
            initialStates = mutableMapOf("device-a" to LockState.UNLOCKED),
            finishError = IllegalStateException("finish failed"),
        )
        val nextGateway = FakeGateway(mutableMapOf("device-a" to LockState.UNLOCKED))
        val group = LockGroup("entrance", "玄関", listOf("device-a"))

        var finishFailed = false
        try {
            controller(failingFinishGateway).lockGroup(group)
        } catch (error: IllegalStateException) {
            finishFailed = error.message == "finish failed"
        }
        assertTrue(finishFailed)

        val next = controller(nextGateway).lockGroup(group)
        assertEquals(GroupOperationStatus.SUCCESS, next.status)
        assertEquals(1, nextGateway.operationCalls.size)
    }

    @Test
    fun aggregateState_isConservativeWhenUnknownIsPresent() {
        assertEquals(GroupState.UNKNOWN, aggregateGroupState(emptyList()))
        assertEquals(GroupState.LOCKED, aggregateGroupState(listOf(LockState.LOCKED, LockState.LOCKED)))
        assertEquals(GroupState.UNLOCKED, aggregateGroupState(listOf(LockState.UNLOCKED, LockState.UNLOCKED)))
        assertEquals(GroupState.MIXED, aggregateGroupState(listOf(LockState.LOCKED, LockState.UNLOCKED)))
        assertEquals(GroupState.UNKNOWN, aggregateGroupState(listOf(LockState.LOCKED, LockState.UNKNOWN)))
        assertEquals(GroupState.UNKNOWN, aggregateGroupState(listOf(LockState.UNLOCKED, LockState.UNKNOWN)))
        assertEquals(
            GroupState.UNKNOWN,
            aggregateGroupState(listOf(LockState.LOCKED, LockState.UNLOCKED, LockState.UNKNOWN)),
        )
        assertEquals(GroupState.UNKNOWN, aggregateGroupState(listOf(LockState.UNKNOWN, LockState.UNKNOWN)))
    }

    private fun controller(gateway: GroupLockGateway) = GroupLockController(
        gateway = gateway,
        interDeviceDelayMs = 500L,
        delayBetweenDevices = { },
    )

    private fun GroupOperationResult.operationResultsAreEmpty(): Boolean = deviceResults.isEmpty()

    private class FakeGateway(
        initialStates: MutableMap<String, LockState>,
        private val failures: Map<String, String> = emptyMap(),
        private val throwOn: Set<String> = emptySet(),
        private val block: CompletableDeferred<Unit>? = null,
        private val entered: CompletableDeferred<Unit>? = null,
        private val finishError: Throwable? = null,
    ) : GroupLockGateway {
        val states = initialStates
        val operationCalls = mutableListOf<Pair<String, GroupLockAction>>()
        var finishCalls = 0

        override suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult {
            entered?.complete(Unit)
            block?.await()
            operationCalls += deviceId to action

            if (deviceId in throwOn) {
                throw IllegalStateException("boom-$deviceId")
            }

            failures[deviceId]?.let { error ->
                return DeviceOperationResult(
                    deviceId = deviceId,
                    success = false,
                    error = error,
                    finalKnownState = states[deviceId] ?: LockState.UNKNOWN,
                )
            }

            val target = if (action == GroupLockAction.LOCK) LockState.LOCKED else LockState.UNLOCKED
            states[deviceId] = target
            return DeviceOperationResult(deviceId, true, finalKnownState = target)
        }

        override suspend fun getState(deviceId: String): LockState = states[deviceId] ?: LockState.UNKNOWN

        override suspend fun finishOperation() {
            finishCalls += 1
            finishError?.let { throw it }
        }
    }
}

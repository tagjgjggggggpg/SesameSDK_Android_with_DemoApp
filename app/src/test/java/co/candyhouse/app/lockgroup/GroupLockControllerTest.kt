package co.candyhouse.app.lockgroup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(4, gateway.operationCalls.size)
        assertEquals(2, gateway.operationCalls.count { it.second == GroupLockAction.LOCK })
        assertEquals(2, gateway.operationCalls.count { it.second == GroupLockAction.UNLOCK })
        assertEquals(setOf("device-a", "device-b"), gateway.operationCalls.take(2).map { it.first }.toSet())
        assertEquals(setOf("device-a", "device-b"), gateway.operationCalls.drop(2).map { it.first }.toSet())
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
        assertEquals(setOf("device-a", "device-b"), gateway.operationCalls.map { it.first }.toSet())
        assertTrue(gateway.operationCalls.all { it.second == GroupLockAction.LOCK })
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
        assertEquals(setOf("device-a", "device-b"), gateway.operationCalls.map { it.first }.toSet())
        assertTrue(gateway.operationCalls.all { it.second == GroupLockAction.UNLOCK })
    }

    @Test
    fun twoDevices_startInParallelWithoutSequentialDelay() = runBlocking {
        val gateway = ParallelStartGateway(expectedStarts = 2)
        val operation = async { controller(gateway).lockGroup(twoDeviceGroup) }

        withTimeout(1_000L) { gateway.allStarted.await() }
        assertEquals(setOf("device-a", "device-b"), gateway.started.toSet())

        gateway.release.complete(Unit)
        assertEquals(GroupOperationStatus.SUCCESS, operation.await().status)
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
    fun firstDeviceFailure_doesNotCancelSecondDevice() = runBlocking {
        val gateway = FailureAndBlockingPeerGateway(failingDevice = "device-a")
        val operation = async { controller(gateway).lockGroup(twoDeviceGroup) }

        withTimeout(1_000L) { gateway.peerStarted.await() }
        gateway.releasePeer.complete(Unit)
        val result = operation.await()

        assertTrue(gateway.peerCompleted)
        assertEquals(GroupOperationStatus.PARTIAL, result.status)
        assertEquals(1, result.deviceResults.count { it.success })
    }

    @Test
    fun secondDeviceFailure_doesNotCancelFirstDevice() = runBlocking {
        val gateway = FailureAndBlockingPeerGateway(failingDevice = "device-b")
        val operation = async { controller(gateway).unlockGroup(twoDeviceGroup) }

        withTimeout(1_000L) { gateway.peerStarted.await() }
        gateway.releasePeer.complete(Unit)
        val result = operation.await()

        assertTrue(gateway.peerCompleted)
        assertEquals(GroupOperationStatus.PARTIAL, result.status)
        assertEquals(1, result.deviceResults.count { it.success })
    }

    @Test
    fun bothDeviceFailures_returnFailure() = runBlocking {
        val gateway = FakeGateway(
            initialStates = mutableMapOf("device-a" to LockState.UNLOCKED, "device-b" to LockState.UNLOCKED),
            failures = mapOf("device-a" to "A failed", "device-b" to "B failed"),
        )

        val result = controller(gateway).lockGroup(twoDeviceGroup)

        assertEquals(GroupOperationStatus.FAILURE, result.status)
        assertEquals(0, result.deviceResults.count { it.success })
        assertEquals(2, gateway.operationCalls.size)
    }

    @Test
    fun threeDeviceGatewayException_isPerDeviceFailureAndOtherDevicesComplete() = runBlocking {
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
        assertEquals(setOf("device-a", "device-b", "device-c"), gateway.operationCalls.map { it.first }.toSet())
        assertEquals("boom-device-b", result.deviceResults.first { it.deviceId == "device-b" }.error)
        assertTrue(result.deviceResults.first { it.deviceId == "device-a" }.success)
        assertTrue(result.deviceResults.first { it.deviceId == "device-c" }.success)
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
    fun ordinaryStateGateway_isExplicitlyNonFreshForEntranceSnapshot() = runBlocking {
        val gateway = FakeGateway(
            mutableMapOf(
                "device-a" to LockState.LOCKED,
                "device-b" to LockState.UNLOCKED,
            )
        )

        val snapshot = controller(gateway).getEntranceStateSnapshot(twoDeviceGroup)

        assertFalse(snapshot.deviceA.fresh)
        assertFalse(snapshot.deviceB.fresh)
        assertEquals(SnapshotConfirmation.NONE, snapshot.confirmation)
    }

    @Test
    fun aggregateState_isConservativeWhenUnknownIsPresent() {
        assertEquals(GroupState.UNKNOWN, aggregateGroupState(emptyList()))
        assertEquals(GroupState.LOCKED, aggregateGroupState(listOf(LockState.LOCKED, LockState.LOCKED)))
        assertEquals(GroupState.UNLOCKED, aggregateGroupState(listOf(LockState.UNLOCKED, LockState.UNLOCKED)))
        assertEquals(GroupState.MIXED, aggregateGroupState(listOf(LockState.LOCKED, LockState.UNLOCKED)))
        assertEquals(GroupState.UNKNOWN, aggregateGroupState(listOf(LockState.LOCKED, LockState.UNKNOWN)))
        assertEquals(GroupState.UNKNOWN, aggregateGroupState(listOf(LockState.UNLOCKED, LockState.UNKNOWN)))
        assertEquals(GroupState.UNKNOWN, aggregateGroupState(listOf(LockState.LOCKED, LockState.UNLOCKED, LockState.UNKNOWN)))
        assertEquals(GroupState.UNKNOWN, aggregateGroupState(listOf(LockState.UNKNOWN, LockState.UNKNOWN)))
    }

    private fun controller(gateway: GroupLockGateway) = GroupLockController(gateway)

    private fun GroupOperationResult.operationResultsAreEmpty(): Boolean = deviceResults.isEmpty()

    private class ParallelStartGateway(private val expectedStarts: Int) : GroupLockGateway {
        val started = mutableListOf<String>()
        val allStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult {
            started += deviceId
            if (started.size == expectedStarts) allStarted.complete(Unit)
            allStarted.await()
            release.await()
            return DeviceOperationResult(deviceId, true)
        }

        override suspend fun getState(deviceId: String) = LockState.UNKNOWN
        override suspend fun getDeviceSnapshot(deviceId: String) = EntranceDeviceSnapshot(LockState.UNKNOWN, fresh = false)
    }

    private class FailureAndBlockingPeerGateway(private val failingDevice: String) : GroupLockGateway {
        val peerStarted = CompletableDeferred<Unit>()
        val releasePeer = CompletableDeferred<Unit>()
        var peerCompleted = false

        override suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult {
            if (deviceId == failingDevice) return DeviceOperationResult(deviceId, false, "failed")
            peerStarted.complete(Unit)
            releasePeer.await()
            peerCompleted = true
            return DeviceOperationResult(deviceId, true)
        }

        override suspend fun getState(deviceId: String) = LockState.UNKNOWN
        override suspend fun getDeviceSnapshot(deviceId: String) = EntranceDeviceSnapshot(LockState.UNKNOWN, fresh = false)
    }

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

            if (deviceId in throwOn) throw IllegalStateException("boom-$deviceId")

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
        override suspend fun getDeviceSnapshot(deviceId: String): EntranceDeviceSnapshot =
            EntranceDeviceSnapshot(states[deviceId] ?: LockState.UNKNOWN, fresh = false)

        override suspend fun finishOperation() {
            finishCalls += 1
            finishError?.let { throw it }
        }
    }
}

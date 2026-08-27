package co.candyhouse.app.lockgroup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface GroupLockGateway {
    suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult
    suspend fun getState(deviceId: String): LockState
    suspend fun finishOperation() {}
}

private object ProcessWideGroupOperationGate {
    private val inFlight = AtomicBoolean(false)

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() {
        inFlight.set(false)
    }
}

internal object GroupOperationTrace {
    private val sequence = AtomicLong(0)
    @Volatile private var currentId: String? = null

    fun begin(): String = "op${sequence.incrementAndGet()}".also { currentId = it }
    fun current(): String = currentId ?: "op-none"
    fun end(id: String) {
        if (currentId == id) currentId = null
    }
}

class GroupLockController(
    private val gateway: GroupLockGateway,
    private val interDeviceDelayMs: Long = 500L,
    private val delayBetweenDevices: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun lockGroup(group: LockGroup): GroupOperationResult = operate(group, GroupLockAction.LOCK)

    suspend fun unlockGroup(group: LockGroup): GroupOperationResult = operate(group, GroupLockAction.UNLOCK)

    suspend fun getGroupState(group: LockGroup): GroupState {
        val states = group.deviceIds.map { gateway.getState(it) }
        return aggregateGroupState(states)
    }

    private suspend fun operate(group: LockGroup, action: GroupLockAction): GroupOperationResult {
        if (!ProcessWideGroupOperationGate.tryAcquire()) {
            return GroupOperationResult(
                groupId = group.id,
                action = action,
                status = GroupOperationStatus.BUSY,
                deviceResults = emptyList(),
                error = "A group operation is already in progress",
            )
        }

        val operationId = GroupOperationTrace.begin()
        try {
            if (group.deviceIds.isEmpty()) {
                return GroupOperationResult(
                    groupId = group.id,
                    action = action,
                    status = GroupOperationStatus.FAILURE,
                    deviceResults = emptyList(),
                    error = "The group has no devices",
                )
            }

            val results = mutableListOf<DeviceOperationResult>()
            group.deviceIds.forEachIndexed { index, deviceId ->
                results += try {
                    gateway.operate(deviceId, action)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    DeviceOperationResult(
                        deviceId = deviceId,
                        success = false,
                        error = error.message ?: error::class.java.simpleName,
                    )
                }

                if (index < group.deviceIds.lastIndex) {
                    delayBetweenDevices(interDeviceDelayMs)
                }
            }

            val successCount = results.count { it.success }
            val status = when {
                successCount == results.size -> GroupOperationStatus.SUCCESS
                successCount == 0 -> GroupOperationStatus.FAILURE
                else -> GroupOperationStatus.PARTIAL
            }

            return GroupOperationResult(
                groupId = group.id,
                action = action,
                status = status,
                deviceResults = results,
            )
        } finally {
            try {
                gateway.finishOperation()
            } finally {
                GroupOperationTrace.end(operationId)
                ProcessWideGroupOperationGate.release()
            }
        }
    }
}

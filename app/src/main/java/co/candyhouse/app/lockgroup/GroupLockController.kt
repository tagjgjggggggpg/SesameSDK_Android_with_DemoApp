package co.candyhouse.app.lockgroup

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

interface GroupLockGateway {
    suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult
    suspend fun getState(deviceId: String): LockState
    suspend fun finishOperation() {}
}

class GroupLockController(
    private val gateway: GroupLockGateway,
    private val interDeviceDelayMs: Long = 500L,
    private val delayBetweenDevices: suspend (Long) -> Unit = { delay(it) },
) {
    private val inFlight = AtomicBoolean(false)

    suspend fun lockGroup(group: LockGroup): GroupOperationResult = operate(group, GroupLockAction.LOCK)

    suspend fun unlockGroup(group: LockGroup): GroupOperationResult = operate(group, GroupLockAction.UNLOCK)

    suspend fun getGroupState(group: LockGroup): GroupState {
        val states = group.deviceIds.map { gateway.getState(it) }
        return aggregateGroupState(states)
    }

    private suspend fun operate(group: LockGroup, action: GroupLockAction): GroupOperationResult {
        if (!inFlight.compareAndSet(false, true)) {
            return GroupOperationResult(
                groupId = group.id,
                action = action,
                status = GroupOperationStatus.BUSY,
                deviceResults = emptyList(),
                error = "A group operation is already in progress",
            )
        }

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
                inFlight.set(false)
            }
        }
    }
}

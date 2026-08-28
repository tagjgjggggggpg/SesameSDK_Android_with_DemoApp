package co.candyhouse.app.lockgroup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface GroupLockGateway {
    suspend fun operate(deviceId: String, action: GroupLockAction): DeviceOperationResult
    suspend fun getState(deviceId: String): LockState
    suspend fun getDeviceSnapshot(deviceId: String): EntranceDeviceSnapshot
    suspend fun finishOperation() {}
}

private object ProcessWideGroupOperationGate {
    private val inFlight = AtomicBoolean(false)
    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)
    fun release() { inFlight.set(false) }
}

internal object GroupOperationTrace {
    private val sequence = AtomicLong(0)
    @Volatile private var currentId: String? = null
    fun begin(): String = "op${sequence.incrementAndGet()}".also { currentId = it }
    fun current(): String = currentId ?: "op-none"
    fun end(id: String) { if (currentId == id) currentId = null }
}

class GroupLockController(
    private val gateway: GroupLockGateway,
    private val interDeviceDelayMs: Long = 500L,
    private val delayBetweenDevices: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun lockGroup(group: LockGroup): GroupOperationResult = operate(group, GroupLockAction.LOCK)
    suspend fun unlockGroup(group: LockGroup): GroupOperationResult = operate(group, GroupLockAction.UNLOCK)
    suspend fun getGroupState(group: LockGroup): GroupState = aggregateGroupState(group.deviceIds.map { gateway.getState(it) })

    suspend fun getEntranceStateSnapshot(group: LockGroup): EntranceGroupStateSnapshot {
        val devices = group.deviceIds.take(2).map { id ->
            try { gateway.getDeviceSnapshot(id) } catch (_: Throwable) { EntranceDeviceSnapshot(LockState.UNKNOWN, fresh = false) }
        }
        val a = devices.getOrNull(0) ?: EntranceDeviceSnapshot(LockState.UNKNOWN, fresh = false)
        val b = devices.getOrNull(1) ?: EntranceDeviceSnapshot(LockState.UNKNOWN, fresh = false)
        val capturedAt = listOf(a, b)
            .mapNotNull { it.stateObservedAtMillis.takeIf { _ -> it.fresh && it.state != LockState.UNKNOWN } }
            .maxOrNull() ?: 0L
        return EntranceGroupStateSnapshot(a, b, capturedAt)
    }

    private suspend fun operate(group: LockGroup, action: GroupLockAction): GroupOperationResult {
        if (!ProcessWideGroupOperationGate.tryAcquire()) return GroupOperationResult(group.id, action, GroupOperationStatus.BUSY, emptyList(), "A group operation is already in progress")
        val operationId = GroupOperationTrace.begin()
        try {
            if (group.deviceIds.isEmpty()) return GroupOperationResult(group.id, action, GroupOperationStatus.FAILURE, emptyList(), "The group has no devices")
            val results = mutableListOf<DeviceOperationResult>()
            group.deviceIds.forEachIndexed { index, deviceId ->
                results += try { gateway.operate(deviceId, action) }
                catch (cancellation: CancellationException) { throw cancellation }
                catch (error: Throwable) { DeviceOperationResult(deviceId, false, error.message ?: error::class.java.simpleName) }
                if (index < group.deviceIds.lastIndex) delayBetweenDevices(interDeviceDelayMs)
            }
            val successCount = results.count { it.success }
            val status = when { successCount == results.size -> GroupOperationStatus.SUCCESS; successCount == 0 -> GroupOperationStatus.FAILURE; else -> GroupOperationStatus.PARTIAL }
            return GroupOperationResult(group.id, action, status, results)
        } finally {
            try { gateway.finishOperation() } finally { GroupOperationTrace.end(operationId); ProcessWideGroupOperationGate.release() }
        }
    }
}

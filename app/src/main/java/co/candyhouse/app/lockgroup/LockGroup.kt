package co.candyhouse.app.lockgroup

data class LockGroup(
    val id: String,
    val name: String,
    val deviceIds: List<String>,
)

enum class GroupLockAction { LOCK, UNLOCK }
enum class LockState { LOCKED, UNLOCKED, UNKNOWN }
enum class GroupState { LOCKED, UNLOCKED, MIXED, UNKNOWN }
enum class GroupOperationStatus { SUCCESS, PARTIAL, FAILURE, BUSY }
enum class SnapshotConfirmation { FULL, PARTIAL, NONE }

data class EntranceDeviceSnapshot(
    val state: LockState,
    val batteryPercent: Int? = null,
    val fresh: Boolean = false,
)

data class EntranceGroupStateSnapshot(
    val deviceA: EntranceDeviceSnapshot,
    val deviceB: EntranceDeviceSnapshot,
    val capturedAt: Long,
) {
    constructor(deviceA: LockState, deviceB: LockState) : this(
        EntranceDeviceSnapshot(deviceA, fresh = deviceA != LockState.UNKNOWN),
        EntranceDeviceSnapshot(deviceB, fresh = deviceB != LockState.UNKNOWN),
        0L,
    )

    val groupState: GroupState get() = aggregateGroupState(listOf(deviceA.state, deviceB.state))
    val confirmation: SnapshotConfirmation get() = when (listOf(deviceA, deviceB).count { it.fresh && it.state != LockState.UNKNOWN }) {
        2 -> SnapshotConfirmation.FULL
        1 -> SnapshotConfirmation.PARTIAL
        else -> SnapshotConfirmation.NONE
    }
}

fun resolveEntranceExplicitActionFromFreshState(snapshot: EntranceGroupStateSnapshot): GroupLockAction =
    if (snapshot.deviceA.fresh && snapshot.deviceB.fresh && snapshot.deviceA.state == LockState.UNLOCKED && snapshot.deviceB.state == LockState.UNLOCKED) GroupLockAction.LOCK
    else GroupLockAction.UNLOCK

const val LOW_BATTERY_THRESHOLD_PERCENT = 20
const val LOW_BATTERY_RESET_PERCENT = 25

internal fun shouldSendLowBatteryAlert(freshBatteryPercent: Int?, alreadyAlerted: Boolean): Boolean =
    freshBatteryPercent != null && freshBatteryPercent <= LOW_BATTERY_THRESHOLD_PERCENT && !alreadyAlerted

internal fun nextLowBatteryAlerted(freshBatteryPercent: Int?, alreadyAlerted: Boolean): Boolean = when {
    freshBatteryPercent == null -> alreadyAlerted
    freshBatteryPercent <= LOW_BATTERY_THRESHOLD_PERCENT -> true
    freshBatteryPercent >= LOW_BATTERY_RESET_PERCENT -> false
    else -> alreadyAlerted
}

data class DeviceOperationResult(
    val deviceId: String,
    val success: Boolean,
    val error: String? = null,
    val finalKnownState: LockState = LockState.UNKNOWN,
)

data class GroupOperationResult(
    val groupId: String,
    val action: GroupLockAction,
    val status: GroupOperationStatus,
    val deviceResults: List<DeviceOperationResult>,
    val error: String? = null,
)

fun aggregateGroupState(states: List<LockState>): GroupState = when {
    states.isEmpty() -> GroupState.UNKNOWN
    states.any { it == LockState.UNKNOWN } -> GroupState.UNKNOWN
    states.all { it == LockState.LOCKED } -> GroupState.LOCKED
    states.all { it == LockState.UNLOCKED } -> GroupState.UNLOCKED
    states.any { it == LockState.LOCKED } && states.any { it == LockState.UNLOCKED } -> GroupState.MIXED
    else -> GroupState.UNKNOWN
}

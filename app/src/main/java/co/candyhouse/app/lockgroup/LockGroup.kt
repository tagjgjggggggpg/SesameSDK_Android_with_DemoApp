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

data class EntranceGroupStateSnapshot(
    val deviceA: LockState,
    val deviceB: LockState,
) {
    val groupState: GroupState
        get() = aggregateGroupState(listOf(deviceA, deviceB))
}

fun resolveEntranceExplicitActionFromFreshState(snapshot: EntranceGroupStateSnapshot): GroupLockAction =
    if (snapshot.deviceA == LockState.UNLOCKED && snapshot.deviceB == LockState.UNLOCKED) GroupLockAction.LOCK
    else GroupLockAction.UNLOCK

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

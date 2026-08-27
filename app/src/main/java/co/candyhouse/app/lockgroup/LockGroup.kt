package co.candyhouse.app.lockgroup

data class LockGroup(
    val id: String,
    val name: String,
    val deviceIds: List<String>,
)

enum class GroupLockAction {
    LOCK,
    UNLOCK,
}

enum class LockState {
    LOCKED,
    UNLOCKED,
    UNKNOWN,
}

enum class GroupState {
    LOCKED,
    UNLOCKED,
    MIXED,
    UNKNOWN,
}

enum class GroupOperationStatus {
    SUCCESS,
    PARTIAL,
    FAILURE,
    BUSY,
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
    states.isEmpty() || states.all { it == LockState.UNKNOWN } -> GroupState.UNKNOWN
    states.all { it == LockState.LOCKED } -> GroupState.LOCKED
    states.all { it == LockState.UNLOCKED } -> GroupState.UNLOCKED
    else -> GroupState.MIXED
}

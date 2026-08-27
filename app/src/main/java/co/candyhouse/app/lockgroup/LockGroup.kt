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

data class EntranceDeviceSnapshot(
    val state: LockState,
    val live: Boolean = false,
    val observedAt: Long? = null,
    val batteryPercent: Int? = null,
)

data class EntranceGroupStateSnapshot(
    val deviceA: EntranceDeviceSnapshot,
    val deviceB: EntranceDeviceSnapshot,
) {
    val groupState: GroupState
        get() = aggregateGroupState(listOf(deviceA.state, deviceB.state))

    val liveCount: Int
        get() = listOf(deviceA, deviceB).count { it.live && it.state != LockState.UNKNOWN }

    val latestObservedAt: Long?
        get() = listOfNotNull(
            deviceA.observedAt.takeIf { deviceA.live },
            deviceB.observedAt.takeIf { deviceB.live },
        ).maxOrNull()
}

/**
 * One-button decision uses only state originating from a currently-valid live BLE mechStatus.
 * No cache, shadow, or plain deviceStatus value can select an action.
 */
fun resolveEntranceExplicitActionFromLiveState(
    snapshot: EntranceGroupStateSnapshot,
): GroupLockAction? {
    if (!snapshot.deviceA.live ||
        !snapshot.deviceB.live ||
        snapshot.deviceA.state == LockState.UNKNOWN ||
        snapshot.deviceB.state == LockState.UNKNOWN
    ) {
        return null
    }

    return if (
        snapshot.deviceA.state == LockState.UNLOCKED &&
        snapshot.deviceB.state == LockState.UNLOCKED
    ) {
        GroupLockAction.LOCK
    } else {
        GroupLockAction.UNLOCK
    }
}

internal const val STATE_CONFIRMATION_UNAVAILABLE = "STATE_CONFIRMATION_UNAVAILABLE"

const val LOW_BATTERY_THRESHOLD_PERCENT = 20
const val LOW_BATTERY_RESET_PERCENT = 25

internal fun shouldSendLowBatteryAlert(
    liveBatteryPercent: Int?,
    alreadyAlerted: Boolean,
): Boolean =
    liveBatteryPercent != null &&
        liveBatteryPercent <= LOW_BATTERY_THRESHOLD_PERCENT &&
        !alreadyAlerted

internal fun nextLowBatteryAlerted(
    liveBatteryPercent: Int?,
    alreadyAlerted: Boolean,
): Boolean = when {
    liveBatteryPercent == null -> alreadyAlerted
    liveBatteryPercent <= LOW_BATTERY_THRESHOLD_PERCENT -> true
    liveBatteryPercent >= LOW_BATTERY_RESET_PERCENT -> false
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

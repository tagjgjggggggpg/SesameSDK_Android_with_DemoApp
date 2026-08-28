package co.candyhouse.wear.phase3

internal enum class WearVisualLockState {
    LOCKED,
    UNLOCKED,
    UNKNOWN,
}

internal sealed interface WearVisualState {
    data class Single(val state: WearVisualLockState) : WearVisualState
    data class Split(val top: WearVisualLockState, val bottom: WearVisualLockState) : WearVisualState
    data object Unknown : WearVisualState
}

internal fun resolveWearVisualState(deviceAState: String, deviceBState: String): WearVisualState {
    val top = deviceAState.toWearVisualLockState()
    val bottom = deviceBState.toWearVisualLockState()
    return when {
        top == WearVisualLockState.LOCKED && bottom == WearVisualLockState.LOCKED ->
            WearVisualState.Single(WearVisualLockState.LOCKED)
        top == WearVisualLockState.UNLOCKED && bottom == WearVisualLockState.UNLOCKED ->
            WearVisualState.Single(WearVisualLockState.UNLOCKED)
        top == WearVisualLockState.UNKNOWN && bottom == WearVisualLockState.UNKNOWN ->
            WearVisualState.Unknown
        else -> WearVisualState.Split(top, bottom)
    }
}

internal fun WearVisualState.groupLabel(): String = when (this) {
    is WearVisualState.Single -> when (state) {
        WearVisualLockState.LOCKED -> "施錠"
        WearVisualLockState.UNLOCKED -> "解錠"
        WearVisualLockState.UNKNOWN -> "不明"
    }
    is WearVisualState.Split -> if (
        (top == WearVisualLockState.LOCKED && bottom == WearVisualLockState.UNLOCKED) ||
        (top == WearVisualLockState.UNLOCKED && bottom == WearVisualLockState.LOCKED)
    ) "混在" else "不明"
    WearVisualState.Unknown -> "不明"
}

private fun String.toWearVisualLockState(): WearVisualLockState = when (this) {
    "LOCKED" -> WearVisualLockState.LOCKED
    "UNLOCKED" -> WearVisualLockState.UNLOCKED
    else -> WearVisualLockState.UNKNOWN
}

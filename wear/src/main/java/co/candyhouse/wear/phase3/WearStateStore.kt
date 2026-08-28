package co.candyhouse.wear.phase3

import android.content.Context

internal data class WearDisplayState(
    val groupState: String = "UNKNOWN",
    val deviceAState: String = "UNKNOWN",
    val deviceBState: String = "UNKNOWN",
    val confirmedAt: Long = 0L,
    val status: String = "",
)

internal class WearStateStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): WearDisplayState = WearDisplayState(
        groupState = prefs.getString(KEY_GROUP, "UNKNOWN") ?: "UNKNOWN",
        deviceAState = prefs.getString(KEY_A, "UNKNOWN") ?: "UNKNOWN",
        deviceBState = prefs.getString(KEY_B, "UNKNOWN") ?: "UNKNOWN",
        confirmedAt = prefs.getLong(KEY_CONFIRMED, 0L),
        status = prefs.getString(KEY_STATUS, "") ?: "",
    )

    fun saveState(groupState: String, deviceAState: String, deviceBState: String, confirmedAt: Long) {
        prefs.edit()
            .putString(KEY_GROUP, groupState)
            .putString(KEY_A, deviceAState)
            .putString(KEY_B, deviceBState)
            .putLong(KEY_CONFIRMED, confirmedAt)
            .apply()
    }

    fun saveStatus(status: String) {
        prefs.edit().putString(KEY_STATUS, status).apply()
    }

    companion object {
        const val PREFS = "phase3_wear_display_state"
        private const val KEY_GROUP = "group_state"
        private const val KEY_A = "device_a_state"
        private const val KEY_B = "device_b_state"
        private const val KEY_CONFIRMED = "confirmed_at"
        private const val KEY_STATUS = "status"
    }
}

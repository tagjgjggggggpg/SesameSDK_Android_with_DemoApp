package co.candyhouse.app.lockgroup

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import co.candyhouse.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EntranceGroupWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, manager, appWidgetIds)
        val snapshot = cachedSnapshot(context)
        appWidgetIds.forEach { updateOne(context, manager, it, snapshot, null) }
    }

    companion object {
        fun updateAll(context: Context, snapshot: EntranceGroupStateSnapshot, status: String?) {
            val display = failClosedDisplaySnapshot(snapshot)
            val fullyConfirmed = display.confirmation == SnapshotConfirmation.FULL
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val edit = prefs.edit()
                .putString(KEY_A, display.deviceA.state.name)
                .putString(KEY_B, display.deviceB.state.name)
                .putBoolean(KEY_STATE_CONFIRMED, fullyConfirmed)
            snapshot.deviceA.batteryPercent?.let { edit.putInt(KEY_BATTERY_A, it) }
            snapshot.deviceB.batteryPercent?.let { edit.putInt(KEY_BATTERY_B, it) }
            confirmationTimeToPersist(display)?.let { edit.putLong(KEY_CONFIRMED_AT, it) }
                ?: edit.remove(KEY_CONFIRMED_AT)
            edit.apply()
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, EntranceGroupWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateOne(context, manager, it, display, status) }
        }

        fun showStatus(context: Context, status: String) {
            val snapshot = cachedSnapshot(context)
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, EntranceGroupWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateOne(context, manager, it, snapshot, status) }
        }

        internal fun failClosedDisplaySnapshot(snapshot: EntranceGroupStateSnapshot): EntranceGroupStateSnapshot =
            if (snapshot.confirmation == SnapshotConfirmation.FULL) snapshot else neutralDisplaySnapshot(snapshot)

        internal fun failClosedPostOperationSnapshot(
            result: GroupOperationResult?,
            snapshot: EntranceGroupStateSnapshot,
        ): EntranceGroupStateSnapshot =
            if (result?.status == GroupOperationStatus.SUCCESS) failClosedDisplaySnapshot(snapshot) else neutralDisplaySnapshot(snapshot)

        private fun neutralDisplaySnapshot(snapshot: EntranceGroupStateSnapshot) = EntranceGroupStateSnapshot(
            deviceA = snapshot.deviceA.copy(state = LockState.UNKNOWN, fresh = false, stateObservedAtMillis = null),
            deviceB = snapshot.deviceB.copy(state = LockState.UNKNOWN, fresh = false, stateObservedAtMillis = null),
            capturedAt = 0L,
        )

        internal fun confirmationTimeToPersist(snapshot: EntranceGroupStateSnapshot): Long? =
            snapshot.capturedAt.takeIf { snapshot.confirmation == SnapshotConfirmation.FULL && it > 0L }

        internal fun cachedSnapshot(context: Context): EntranceGroupStateSnapshot {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val confirmed = p.getBoolean(KEY_STATE_CONFIRMED, false)
            fun state(key: String): LockState {
                if (!confirmed) return LockState.UNKNOWN
                return runCatching {
                    LockState.valueOf(p.getString(key, LockState.UNKNOWN.name) ?: LockState.UNKNOWN.name)
                }.getOrDefault(LockState.UNKNOWN)
            }
            fun battery(key: String) = p.getInt(key, -1).takeIf { it >= 0 }
            return EntranceGroupStateSnapshot(
                EntranceDeviceSnapshot(state(KEY_A), battery(KEY_BATTERY_A), fresh = false),
                EntranceDeviceSnapshot(state(KEY_B), battery(KEY_BATTERY_B), fresh = false),
                if (confirmed) p.getLong(KEY_CONFIRMED_AT, 0L) else 0L,
            )
        }

        internal fun individualStateLabel(snapshot: EntranceGroupStateSnapshot) =
            "1:${stateLabel(snapshot.deviceA.state)} 2:${stateLabel(snapshot.deviceB.state)}"

        internal fun batteryWarningLabel(snapshot: EntranceGroupStateSnapshot): String? {
            val lows = listOf(snapshot.deviceA.batteryPercent, snapshot.deviceB.batteryPercent)
                .mapIndexedNotNull { index, battery ->
                    battery?.takeIf { it <= LOW_BATTERY_THRESHOLD_PERCENT }?.let { "${index + 1}:$it%" }
                }
            return lows.takeIf { it.isNotEmpty() }?.joinToString(" / ", prefix = "⚠ ")
        }

        private fun updateOne(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
            snapshot: EntranceGroupStateSnapshot,
            status: String?,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_entrance_group)
            when (snapshot.groupState) {
                GroupState.LOCKED -> {
                    views.setViewVisibility(R.id.entrance_widget_icon, View.VISIBLE)
                    views.setImageViewResource(R.id.entrance_widget_icon, R.drawable.icon_lock)
                    views.setTextViewText(R.id.entrance_widget_state, "施錠")
                }
                GroupState.UNLOCKED -> {
                    views.setViewVisibility(R.id.entrance_widget_icon, View.VISIBLE)
                    views.setImageViewResource(R.id.entrance_widget_icon, R.drawable.icon_unlock)
                    views.setTextViewText(R.id.entrance_widget_state, "解錠")
                }
                GroupState.MIXED -> {
                    views.setViewVisibility(R.id.entrance_widget_icon, View.GONE)
                    views.setTextViewText(R.id.entrance_widget_state, individualStateLabel(snapshot))
                }
                GroupState.UNKNOWN -> {
                    views.setViewVisibility(R.id.entrance_widget_icon, View.GONE)
                    views.setTextViewText(R.id.entrance_widget_state, "状態を確認できません")
                }
            }

            val time = if (snapshot.capturedAt > 0) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(snapshot.capturedAt))
            } else {
                null
            }
            val confirmation = when {
                time == null -> "確認できません"
                snapshot.confirmation == SnapshotConfirmation.FULL -> "確認 $time"
                else -> "最終確認 $time"
            }
            views.setTextViewText(R.id.entrance_widget_confirmed, confirmation)

            val battery = batteryWarningLabel(snapshot)
            views.setViewVisibility(R.id.entrance_widget_battery, if (battery == null) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.entrance_widget_battery, battery ?: "")
            views.setTextViewText(R.id.entrance_widget_status, status ?: "")
            views.setOnClickPendingIntent(R.id.entrance_widget_action, operationPendingIntent(context))
            views.setOnClickPendingIntent(R.id.entrance_widget_refresh, refreshPendingIntent(context))
            manager.updateAppWidget(id, views)
        }

        private fun stateLabel(state: LockState) = when (state) {
            LockState.LOCKED -> "施錠"
            LockState.UNLOCKED -> "解錠"
            LockState.UNKNOWN -> "不明"
        }

        private fun operationPendingIntent(context: Context) =
            servicePendingIntent(context, 41031, EntranceGroupCommand.ONE_BUTTON)

        private fun refreshPendingIntent(context: Context) =
            servicePendingIntent(context, 41032, EntranceGroupCommand.REFRESH_STATE)

        private fun servicePendingIntent(context: Context, requestCode: Int, action: String): PendingIntent {
            val intent = Intent(context, EntranceGroupOperationService::class.java).setAction(action)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, requestCode, intent, flags)
            } else {
                PendingIntent.getService(context, requestCode, intent, flags)
            }
        }

        private const val PREFS = "entrance_group_widget"
        private const val KEY_A = "last_device_a_state"
        private const val KEY_B = "last_device_b_state"
        private const val KEY_BATTERY_A = "last_battery_a"
        private const val KEY_BATTERY_B = "last_battery_b"
        private const val KEY_CONFIRMED_AT = "last_confirmed_at"
        private const val KEY_STATE_CONFIRMED = "display_state_confirmed_v2"
    }
}

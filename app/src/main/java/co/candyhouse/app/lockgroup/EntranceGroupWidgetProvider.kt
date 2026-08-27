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

class EntranceGroupWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, manager, appWidgetIds)
        val snapshot = cachedSnapshot(context)
        appWidgetIds.forEach { updateOne(context, manager, it, snapshot, null) }
    }

    companion object {
        fun updateAll(context: Context, snapshot: EntranceGroupStateSnapshot, status: String?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_A, snapshot.deviceA.name)
                .putString(KEY_B, snapshot.deviceB.name)
                .apply()
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, EntranceGroupWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateOne(context, manager, it, snapshot, status) }
        }

        internal fun cachedSnapshot(context: Context): EntranceGroupStateSnapshot {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            fun state(key: String) = runCatching { LockState.valueOf(prefs.getString(key, LockState.UNKNOWN.name) ?: LockState.UNKNOWN.name) }.getOrDefault(LockState.UNKNOWN)
            return EntranceGroupStateSnapshot(state(KEY_A), state(KEY_B))
        }

        internal fun individualStateLabel(snapshot: EntranceGroupStateSnapshot): String = "1:${stateLabel(snapshot.deviceA)} 2:${stateLabel(snapshot.deviceB)}"

        private fun updateOne(context: Context, manager: AppWidgetManager, id: Int, snapshot: EntranceGroupStateSnapshot, status: String?) {
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
                else -> {
                    views.setViewVisibility(R.id.entrance_widget_icon, View.GONE)
                    views.setTextViewText(R.id.entrance_widget_state, individualStateLabel(snapshot))
                }
            }
            views.setTextViewText(R.id.entrance_widget_status, status ?: "")
            views.setOnClickPendingIntent(R.id.entrance_widget_action, operationPendingIntent(context))
            manager.updateAppWidget(id, views)
        }

        private fun stateLabel(state: LockState): String = when (state) { LockState.LOCKED -> "施錠"; LockState.UNLOCKED -> "解錠"; LockState.UNKNOWN -> "不明" }
        private fun operationPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, EntranceGroupOperationService::class.java).setAction(EntranceGroupCommand.ONE_BUTTON)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) PendingIntent.getForegroundService(context, 41031, intent, flags) else PendingIntent.getService(context, 41031, intent, flags)
        }
        private const val PREFS = "entrance_group_widget"
        private const val KEY_A = "last_device_a_state"
        private const val KEY_B = "last_device_b_state"
    }
}

package co.candyhouse.app.lockgroup

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import co.candyhouse.app.R

class EntranceGroupWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, manager, appWidgetIds)
        val state = cachedState(context)
        appWidgetIds.forEach { updateOne(context, manager, it, state, null) }
    }

    companion object {
        fun updateAll(context: Context, state: GroupState, status: String?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STATE, state.name)
                .apply()
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, EntranceGroupWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateOne(context, manager, it, state, status) }
        }

        private fun cachedState(context: Context): GroupState = runCatching {
            GroupState.valueOf(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_STATE, GroupState.UNKNOWN.name) ?: GroupState.UNKNOWN.name
            )
        }.getOrDefault(GroupState.UNKNOWN)

        private fun updateOne(context: Context, manager: AppWidgetManager, id: Int, state: GroupState, status: String?) {
            val views = RemoteViews(context.packageName, R.layout.widget_entrance_group)
            views.setTextViewText(R.id.entrance_widget_state, "状態: ${stateLabel(state)}")
            views.setTextViewText(R.id.entrance_widget_status, status ?: "")
            views.setOnClickPendingIntent(R.id.entrance_widget_lock, operationPendingIntent(context, EntranceGroupCommand.WIDGET_LOCK, 41031))
            views.setOnClickPendingIntent(R.id.entrance_widget_unlock, operationPendingIntent(context, EntranceGroupCommand.WIDGET_UNLOCK, 41032))
            manager.updateAppWidget(id, views)
        }

        private fun stateLabel(state: GroupState): String = when (state) {
            GroupState.LOCKED -> "施錠"
            GroupState.UNLOCKED -> "解錠"
            GroupState.MIXED -> "混在"
            GroupState.UNKNOWN -> "不明"
        }

        private fun operationPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, EntranceGroupOperationService::class.java).setAction(action)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, requestCode, intent, flags)
            } else {
                PendingIntent.getService(context, requestCode, intent, flags)
            }
        }

        private const val PREFS = "entrance_group_widget"
        private const val KEY_STATE = "last_group_state"
    }
}

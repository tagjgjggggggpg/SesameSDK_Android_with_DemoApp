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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EntranceGroupWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, manager, appWidgetIds)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                val group = SharedPreferencesLockGroupStore().getGroup(SharedPreferencesLockGroupStore.DEFAULT_GROUP_ID)
                    ?: SharedPreferencesLockGroupStore.DEFAULT_GROUP
                val state = runCatching { GroupLockController(SesameGroupLockGateway()).getGroupState(group) }
                    .getOrDefault(GroupState.UNKNOWN)
                appWidgetIds.forEach { updateOne(context, manager, it, state, null) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun updateAll(context: Context, state: GroupState, status: String?) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, EntranceGroupWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateOne(context, manager, it, state, status) }
        }

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
    }
}

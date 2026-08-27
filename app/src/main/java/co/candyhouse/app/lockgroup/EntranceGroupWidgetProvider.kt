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
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val edit = prefs.edit()
                .putString(KEY_A, snapshot.deviceA.state.name)
                .putString(KEY_B, snapshot.deviceB.state.name)
            snapshot.deviceA.batteryPercent?.let { edit.putInt(KEY_BATTERY_A, it) }
            snapshot.deviceB.batteryPercent?.let { edit.putInt(KEY_BATTERY_B, it) }
            if (snapshot.confirmation != SnapshotConfirmation.NONE && snapshot.capturedAt > 0L) edit.putLong(KEY_CONFIRMED_AT, snapshot.capturedAt)
            edit.apply()
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, EntranceGroupWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateOne(context, manager, it, snapshot, status) }
        }

        internal fun cachedSnapshot(context: Context): EntranceGroupStateSnapshot {
            val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            fun state(k:String)=runCatching{LockState.valueOf(p.getString(k,LockState.UNKNOWN.name)?:LockState.UNKNOWN.name)}.getOrDefault(LockState.UNKNOWN)
            fun battery(k:String)=p.getInt(k,-1).takeIf{it>=0}
            return EntranceGroupStateSnapshot(
                EntranceDeviceSnapshot(state(KEY_A),battery(KEY_BATTERY_A),fresh=false),
                EntranceDeviceSnapshot(state(KEY_B),battery(KEY_BATTERY_B),fresh=false),
                p.getLong(KEY_CONFIRMED_AT,0L),
            )
        }

        internal fun individualStateLabel(s:EntranceGroupStateSnapshot)="1:${stateLabel(s.deviceA.state)} 2:${stateLabel(s.deviceB.state)}"
        internal fun batteryWarningLabel(s:EntranceGroupStateSnapshot):String? {
            val lows=listOf(s.deviceA.batteryPercent,s.deviceB.batteryPercent).mapIndexedNotNull{i,b->b?.takeIf{it<=LOW_BATTERY_THRESHOLD_PERCENT}?.let{"${i+1}:$it%"}}
            return lows.takeIf{it.isNotEmpty()}?.joinToString(" / ",prefix="⚠ ")
        }
        private fun updateOne(context:Context,manager:AppWidgetManager,id:Int,s:EntranceGroupStateSnapshot,status:String?){
            val v=RemoteViews(context.packageName,R.layout.widget_entrance_group)
            when(s.groupState){GroupState.LOCKED->{v.setViewVisibility(R.id.entrance_widget_icon,View.VISIBLE);v.setImageViewResource(R.id.entrance_widget_icon,R.drawable.icon_lock);v.setTextViewText(R.id.entrance_widget_state,"施錠")};GroupState.UNLOCKED->{v.setViewVisibility(R.id.entrance_widget_icon,View.VISIBLE);v.setImageViewResource(R.id.entrance_widget_icon,R.drawable.icon_unlock);v.setTextViewText(R.id.entrance_widget_state,"解錠")};else->{v.setViewVisibility(R.id.entrance_widget_icon,View.GONE);v.setTextViewText(R.id.entrance_widget_state,individualStateLabel(s))}}
            val time=if(s.capturedAt>0)SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(s.capturedAt))else null
            val confirm=when{time==null->"確認できません";s.confirmation==SnapshotConfirmation.FULL->"確認 $time";s.confirmation==SnapshotConfirmation.PARTIAL->"一部確認 $time";else->"最終確認 $time"}
            v.setTextViewText(R.id.entrance_widget_confirmed,confirm)
            val battery=batteryWarningLabel(s);v.setViewVisibility(R.id.entrance_widget_battery,if(battery==null)View.GONE else View.VISIBLE);v.setTextViewText(R.id.entrance_widget_battery,battery?:"")
            v.setTextViewText(R.id.entrance_widget_status,status?:"");v.setOnClickPendingIntent(R.id.entrance_widget_action,operationPendingIntent(context));manager.updateAppWidget(id,v)
        }
        private fun stateLabel(s:LockState)=when(s){LockState.LOCKED->"施錠";LockState.UNLOCKED->"解錠";LockState.UNKNOWN->"不明"}
        private fun operationPendingIntent(context:Context):PendingIntent{val i=Intent(context,EntranceGroupOperationService::class.java).setAction(EntranceGroupCommand.ONE_BUTTON);val f=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE;return if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)PendingIntent.getForegroundService(context,41031,i,f)else PendingIntent.getService(context,41031,i,f)}
        private const val PREFS="entrance_group_widget";private const val KEY_A="last_device_a_state";private const val KEY_B="last_device_b_state";private const val KEY_BATTERY_A="last_battery_a";private const val KEY_BATTERY_B="last_battery_b";private const val KEY_CONFIRMED_AT="last_confirmed_at"
    }
}

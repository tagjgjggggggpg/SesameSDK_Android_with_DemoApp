package co.candyhouse.app.lockgroup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import co.candyhouse.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal object EntranceGroupCommand { const val ONE_BUTTON="co.candyhouse.app.lockgroup.ONE_BUTTON"; fun isSupported(a:String?)=a==ONE_BUTTON }
internal suspend fun executeEntranceGroupAction(c:GroupLockController,g:LockGroup,a:GroupLockAction)=when(a){GroupLockAction.LOCK->c.lockGroup(g);GroupLockAction.UNLOCK->c.unlockGroup(g)}
internal suspend fun resolveAndExecuteEntranceOneButton(c:GroupLockController,g:LockGroup):Pair<EntranceGroupStateSnapshot,GroupOperationResult>{val fresh=c.getEntranceStateSnapshot(g);return fresh to executeEntranceGroupAction(c,g,resolveEntranceExplicitActionFromFreshState(fresh))}

class EntranceGroupOperationService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    override fun onCreate(){super.onCreate();createNotificationChannel()}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(!EntranceGroupCommand.isSupported(intent?.action)){stopSelf(startId);return START_NOT_STICKY}
        startForeground(FOREGROUND_NOTIFICATION_ID,notification("玄関の状態を確認しています"))
        scope.launch{
            val group=SharedPreferencesLockGroupStore().getGroup(SharedPreferencesLockGroupStore.DEFAULT_GROUP_ID)?:SharedPreferencesLockGroupStore.DEFAULT_GROUP
            val controller=GroupLockController(SesameGroupLockGateway())
            val result=try{val fresh=controller.getEntranceStateSnapshot(group);EntranceGroupWidgetProvider.updateAll(this@EntranceGroupOperationService,fresh,"状態を確認しました");processFreshBatteryAlerts(group,fresh);executeEntranceGroupAction(controller,group,resolveEntranceExplicitActionFromFreshState(fresh))}
            catch(c:CancellationException){throw c}catch(e:Throwable){GroupOperationResult(group.id,GroupLockAction.UNLOCK,GroupOperationStatus.FAILURE,emptyList(),e.message?:e::class.java.simpleName)}
            val post=runCatching{controller.getEntranceStateSnapshot(group)}.getOrElse{EntranceGroupStateSnapshot(EntranceDeviceSnapshot(LockState.UNKNOWN,fresh=false),EntranceDeviceSnapshot(LockState.UNKNOWN,fresh=false),System.currentTimeMillis())}
            val message=resultText(result);EntranceGroupWidgetProvider.updateAll(this@EntranceGroupOperationService,post,message);processFreshBatteryAlerts(group,post);refreshTile();Toast.makeText(this@EntranceGroupOperationService,message,Toast.LENGTH_LONG).show();stopForeground(STOP_FOREGROUND_REMOVE);if(result.status!=GroupOperationStatus.SUCCESS)notifyFailure(message);stopSelf(startId)
        };return START_NOT_STICKY
    }
    private fun processFreshBatteryAlerts(group:LockGroup,s:EntranceGroupStateSnapshot){
        val prefs=getSharedPreferences(BATTERY_PREFS,Context.MODE_PRIVATE);listOf(s.deviceA,s.deviceB).forEachIndexed{i,d->if(!d.fresh)return@forEachIndexed;val id=group.deviceIds.getOrNull(i)?:return@forEachIndexed;val key="alerted_$id";val old=prefs.getBoolean(key,false);if(shouldSendLowBatteryAlert(d.batteryPercent,old))notifyLowBattery(i,d.batteryPercent!!);prefs.edit().putBoolean(key,nextLowBatteryAlerted(d.batteryPercent,old)).apply()}
    }
    private fun notifyLowBattery(index:Int,percent:Int){getSystemService(NotificationManager::class.java).notify(LOW_BATTERY_NOTIFICATION_BASE+index,NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(R.drawable.small_icon).setContentTitle("SESAME 玄関").setContentText("${index+1}番の電池残量が${percent}%です").setAutoCancel(true).build())}
    override fun onDestroy(){scope.cancel();super.onDestroy()};override fun onBind(intent:Intent?):IBinder?=null
    private fun resultText(r:GroupOperationResult)=when(r.status){GroupOperationStatus.SUCCESS->if(r.action==GroupLockAction.LOCK)"玄関を施錠しました" else "玄関を解錠しました";GroupOperationStatus.PARTIAL->"玄関: 一部の端末で操作に失敗しました";GroupOperationStatus.FAILURE->"玄関: 操作に失敗しました";GroupOperationStatus.BUSY->"玄関: 別の操作を実行中です"}
    private fun notification(t:String)=NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(R.drawable.small_icon).setContentTitle("SESAME 玄関").setContentText(t).setOnlyAlertOnce(true).setOngoing(true).build()
    private fun notifyFailure(t:String){runCatching{getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID,NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(R.drawable.small_icon).setContentTitle("SESAME 玄関").setContentText(t).setAutoCancel(true).build())}}
    private fun createNotificationChannel(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID,"玄関グループ操作",NotificationManager.IMPORTANCE_LOW))}
    private fun refreshTile(){TileService.requestListeningState(this,ComponentName(this,EntranceQuickSettingsTileService::class.java))}
    companion object{private const val CHANNEL_ID="entrance_group_operation";private const val BATTERY_PREFS="entrance_low_battery_alerts";private const val FOREGROUND_NOTIFICATION_ID=41020;private const val RESULT_NOTIFICATION_ID=41021;private const val LOW_BATTERY_NOTIFICATION_BASE=41040;fun start(context:Context){ContextCompat.startForegroundService(context,Intent(context,EntranceGroupOperationService::class.java).setAction(EntranceGroupCommand.ONE_BUTTON))}}
}

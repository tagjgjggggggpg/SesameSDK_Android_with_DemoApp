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

internal object EntranceGroupCommand {
    const val ONE_BUTTON = "co.candyhouse.app.lockgroup.ONE_BUTTON"
    fun isSupported(intentAction: String?): Boolean = intentAction == ONE_BUTTON
}

internal suspend fun executeEntranceGroupAction(controller: GroupLockController, group: LockGroup, action: GroupLockAction): GroupOperationResult = when (action) {
    GroupLockAction.LOCK -> controller.lockGroup(group)
    GroupLockAction.UNLOCK -> controller.unlockGroup(group)
}

internal suspend fun resolveAndExecuteEntranceOneButton(controller: GroupLockController, group: LockGroup): Pair<EntranceGroupStateSnapshot, GroupOperationResult> {
    val fresh = controller.getEntranceStateSnapshot(group)
    val action = resolveEntranceExplicitActionFromFreshState(fresh)
    return fresh to executeEntranceGroupAction(controller, group, action)
}

class EntranceGroupOperationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!EntranceGroupCommand.isSupported(intent?.action)) { stopSelf(startId); return START_NOT_STICKY }
        startForeground(FOREGROUND_NOTIFICATION_ID, notification("玄関の状態を確認しています"))
        scope.launch {
            val group = SharedPreferencesLockGroupStore().getGroup(SharedPreferencesLockGroupStore.DEFAULT_GROUP_ID) ?: SharedPreferencesLockGroupStore.DEFAULT_GROUP
            val controller = GroupLockController(SesameGroupLockGateway())
            val result = try {
                val fresh = controller.getEntranceStateSnapshot(group)
                EntranceGroupWidgetProvider.updateAll(this@EntranceGroupOperationService, fresh, "状態を確認しました")
                val action = resolveEntranceExplicitActionFromFreshState(fresh)
                executeEntranceGroupAction(controller, group, action)
            } catch (cancellation: CancellationException) { throw cancellation }
            catch (error: Throwable) { GroupOperationResult(group.id, GroupLockAction.UNLOCK, GroupOperationStatus.FAILURE, emptyList(), error.message ?: error::class.java.simpleName) }

            val postSnapshot = runCatching { controller.getEntranceStateSnapshot(group) }.getOrDefault(EntranceGroupStateSnapshot(LockState.UNKNOWN, LockState.UNKNOWN))
            val message = resultText(result)
            EntranceGroupWidgetProvider.updateAll(this@EntranceGroupOperationService, postSnapshot, message)
            refreshTile()
            Toast.makeText(this@EntranceGroupOperationService, message, Toast.LENGTH_LONG).show()
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (result.status != GroupOperationStatus.SUCCESS) notifyFailure(message)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun resultText(result: GroupOperationResult): String = when (result.status) {
        GroupOperationStatus.SUCCESS -> if (result.action == GroupLockAction.LOCK) "玄関を施錠しました" else "玄関を解錠しました"
        GroupOperationStatus.PARTIAL -> "玄関: 一部の端末で操作に失敗しました"
        GroupOperationStatus.FAILURE -> "玄関: 操作に失敗しました"
        GroupOperationStatus.BUSY -> "玄関: 別の操作を実行中です"
    }
    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.small_icon).setContentTitle("SESAME 玄関").setContentText(text).setOnlyAlertOnce(true).setOngoing(true).build()
    private fun notifyFailure(text: String) { runCatching { getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.small_icon).setContentTitle("SESAME 玄関").setContentText(text).setAutoCancel(true).build()) } }
    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "玄関グループ操作", NotificationManager.IMPORTANCE_LOW)) }
    private fun refreshTile() { TileService.requestListeningState(this, ComponentName(this, EntranceQuickSettingsTileService::class.java)) }

    companion object {
        private const val CHANNEL_ID = "entrance_group_operation"
        private const val FOREGROUND_NOTIFICATION_ID = 41020
        private const val RESULT_NOTIFICATION_ID = 41021
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, EntranceGroupOperationService::class.java).setAction(EntranceGroupCommand.ONE_BUTTON)) }
    }
}

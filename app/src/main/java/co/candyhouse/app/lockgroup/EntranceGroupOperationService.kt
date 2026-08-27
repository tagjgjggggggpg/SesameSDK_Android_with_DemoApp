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
    const val QS_LOCK = "co.candyhouse.app.lockgroup.QS_LOCK"
    const val QS_UNLOCK = "co.candyhouse.app.lockgroup.QS_UNLOCK"
    const val WIDGET_LOCK = "co.candyhouse.app.lockgroup.WIDGET_LOCK"
    const val WIDGET_UNLOCK = "co.candyhouse.app.lockgroup.WIDGET_UNLOCK"

    fun resolve(intentAction: String?): GroupLockAction? = when (intentAction) {
        QS_LOCK, WIDGET_LOCK -> GroupLockAction.LOCK
        QS_UNLOCK, WIDGET_UNLOCK -> GroupLockAction.UNLOCK
        else -> null
    }
}

internal suspend fun executeEntranceGroupAction(
    controller: GroupLockController,
    group: LockGroup,
    action: GroupLockAction,
): GroupOperationResult = when (action) {
    GroupLockAction.LOCK -> controller.lockGroup(group)
    GroupLockAction.UNLOCK -> controller.unlockGroup(group)
}

class EntranceGroupOperationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = EntranceGroupCommand.resolve(intent?.action)
        if (action == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(FOREGROUND_NOTIFICATION_ID, notification(progressText(action)))
        scope.launch {
            val group = SharedPreferencesLockGroupStore().getGroup(SharedPreferencesLockGroupStore.DEFAULT_GROUP_ID)
                ?: SharedPreferencesLockGroupStore.DEFAULT_GROUP
            val controller = GroupLockController(SesameGroupLockGateway())
            val result = try {
                executeEntranceGroupAction(controller, group, action)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                GroupOperationResult(
                    groupId = group.id,
                    action = action,
                    status = GroupOperationStatus.FAILURE,
                    deviceResults = emptyList(),
                    error = error.message ?: error::class.java.simpleName,
                )
            }
            val state = runCatching { controller.getGroupState(group) }.getOrDefault(GroupState.UNKNOWN)
            val message = resultText(result)
            EntranceGroupWidgetProvider.updateAll(this@EntranceGroupOperationService, state, message)
            refreshTiles()
            Toast.makeText(this@EntranceGroupOperationService, message, Toast.LENGTH_LONG).show()
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (result.status != GroupOperationStatus.SUCCESS) notifyFailure(message)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun progressText(action: GroupLockAction) = when (action) {
        GroupLockAction.LOCK -> "玄関を施錠しています"
        GroupLockAction.UNLOCK -> "玄関を解錠しています"
    }

    private fun resultText(result: GroupOperationResult): String = when (result.status) {
        GroupOperationStatus.SUCCESS -> if (result.action == GroupLockAction.LOCK) "玄関を施錠しました" else "玄関を解錠しました"
        GroupOperationStatus.PARTIAL -> "玄関: 一部の端末で操作に失敗しました"
        GroupOperationStatus.FAILURE -> "玄関: 操作に失敗しました"
        GroupOperationStatus.BUSY -> "玄関: 別の操作を実行中です"
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle("SESAME 玄関")
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .build()

    private fun notifyFailure(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                RESULT_NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.small_icon)
                    .setContentTitle("SESAME 玄関")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "玄関グループ操作", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun refreshTiles() {
        TileService.requestListeningState(this, ComponentName(this, EntranceQuickSettingsTileService::class.java))
        TileService.requestListeningState(this, ComponentName(this, EntranceUnlockQuickSettingsTileService::class.java))
    }

    companion object {
        private const val CHANNEL_ID = "entrance_group_operation"
        private const val FOREGROUND_NOTIFICATION_ID = 41020
        private const val RESULT_NOTIFICATION_ID = 41021

        fun start(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EntranceGroupOperationService::class.java).setAction(action),
            )
        }
    }
}

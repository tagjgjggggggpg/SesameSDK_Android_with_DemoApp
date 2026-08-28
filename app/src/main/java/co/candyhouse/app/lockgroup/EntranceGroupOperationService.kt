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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import co.candyhouse.app.R
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal object EntranceGroupCommand {
    const val ONE_BUTTON = "co.candyhouse.app.lockgroup.ONE_BUTTON"
    const val REFRESH_STATE = "co.candyhouse.app.lockgroup.REFRESH_STATE"
    const val WEAR_ONE_BUTTON = "co.candyhouse.app.lockgroup.WEAR_ONE_BUTTON"
    fun isSupported(action: String?) = action == ONE_BUTTON || action == REFRESH_STATE || action == WEAR_ONE_BUTTON
}

internal suspend fun executeEntranceGroupAction(controller: GroupLockController, group: LockGroup, action: GroupLockAction) = when (action) {
    GroupLockAction.LOCK -> controller.lockGroup(group)
    GroupLockAction.UNLOCK -> controller.unlockGroup(group)
}

internal suspend fun resolveAndExecuteEntranceOneButton(
    controller: GroupLockController,
    group: LockGroup,
): Pair<EntranceGroupStateSnapshot, GroupOperationResult?> {
    val fresh = controller.getEntranceStateSnapshot(group)
    val action = resolveEntranceExplicitActionFromFreshState(fresh) ?: return fresh to null
    return fresh to executeEntranceGroupAction(controller, group, action)
}

internal fun refreshStatus(snapshot: EntranceGroupStateSnapshot) = when (snapshot.confirmation) {
    SnapshotConfirmation.FULL -> "状態を更新しました"
    SnapshotConfirmation.PARTIAL -> "一部のみ確認"
    SnapshotConfirmation.NONE -> "状態を確認できません"
}

internal fun wearResponseFromOperation(commandId: String, groupId: String, result: GroupOperationResult?): WearGroupProtocol.Response {
    val overall = when (result?.status) {
        GroupOperationStatus.SUCCESS -> "SUCCESS"
        GroupOperationStatus.PARTIAL -> "PARTIAL"
        GroupOperationStatus.FAILURE -> "FAILURE"
        GroupOperationStatus.BUSY -> "BUSY"
        null -> "STATE_UNAVAILABLE"
    }
    val message = when (overall) {
        "SUCCESS" -> if (result?.action == GroupLockAction.LOCK) "施錠しました" else "解錠しました"
        "PARTIAL" -> "一部失敗"
        "FAILURE" -> "操作に失敗しました"
        "BUSY" -> "処理中です"
        else -> "状態確認不能"
    }
    fun deviceResult(index: Int): String = result?.deviceResults?.getOrNull(index)?.let {
        if (it.success) "SUCCESS" else "FAILURE"
    } ?: "NONE"
    return WearGroupProtocol.Response(
        commandId = commandId,
        groupId = groupId,
        resolvedTarget = result?.action?.name ?: "NONE",
        overallStatus = overall,
        deviceAResult = deviceResult(0),
        deviceBResult = deviceResult(1),
        message = message,
    )
}

class EntranceGroupOperationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.action
        if (!EntranceGroupCommand.isSupported(command)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(FOREGROUND_NOTIFICATION_ID, notification("玄関の状態を確認しています"))
        if (command == EntranceGroupCommand.REFRESH_STATE) {
            EntranceGroupWidgetProvider.showStatus(this, "確認中…")
        }

        scope.launch {
            val group = SharedPreferencesLockGroupStore().getGroup(SharedPreferencesLockGroupStore.DEFAULT_GROUP_ID)
                ?: SharedPreferencesLockGroupStore.DEFAULT_GROUP

            if (command == EntranceGroupCommand.REFRESH_STATE) {
                val snapshot = try {
                    SesameGroupLockGateway().refreshEntranceStateSnapshot(group)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    unknownSnapshot()
                }
                val message = refreshStatus(snapshot)
                EntranceGroupWidgetProvider.updateAll(this@EntranceGroupOperationService, snapshot, message)
                WearGroupStateSync.publish(this@EntranceGroupOperationService, snapshot)
                processFreshBatteryAlerts(group, snapshot)
                refreshTile()
                finishStart(startId)
                return@launch
            }

            val controller = GroupLockController(SesameGroupLockGateway())
            val (initial, result) = try {
                resolveAndExecuteEntranceOneButton(controller, group)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                unknownSnapshot() to null
            }

            EntranceGroupWidgetProvider.updateAll(
                this@EntranceGroupOperationService,
                initial,
                if (result == null) "状態確認不能" else "状態を確認しました",
            )
            processFreshBatteryAlerts(group, initial)

            val postRaw = if (result == null) {
                initial
            } else {
                runCatching { controller.getEntranceStateSnapshot(group) }.getOrElse { unknownSnapshot() }
            }
            val postDisplay = EntranceGroupWidgetProvider.failClosedPostOperationSnapshot(result, postRaw)
            val message = resultText(result)
            EntranceGroupWidgetProvider.updateAll(this@EntranceGroupOperationService, postDisplay, message)
            WearGroupStateSync.publish(this@EntranceGroupOperationService, postDisplay)
            processFreshBatteryAlerts(group, postRaw)
            refreshTile()

            if (command == EntranceGroupCommand.WEAR_ONE_BUTTON) {
                sendWearResult(intent, result)
            }
            finishStart(startId)
        }
        return START_NOT_STICKY
    }

    private fun sendWearResult(intent: Intent?, result: GroupOperationResult?) {
        val nodeId = intent?.getStringExtra(EXTRA_WEAR_SOURCE_NODE) ?: return
        val commandId = intent.getStringExtra(EXTRA_WEAR_COMMAND_ID) ?: return
        val groupId = intent.getStringExtra(EXTRA_WEAR_GROUP_ID) ?: WearGroupProtocol.ENTRANCE_GROUP_ID
        val payload = WearGroupProtocol.encodeResponse(wearResponseFromOperation(commandId, groupId, result))
        WearCommandLedger.forContext(this).complete(commandId, payload)
        Wearable.getMessageClient(this).sendMessage(nodeId, WearGroupProtocol.RESULT_PATH, payload)
    }

    private fun unknownSnapshot() = EntranceGroupStateSnapshot(
        EntranceDeviceSnapshot(LockState.UNKNOWN, fresh = false),
        EntranceDeviceSnapshot(LockState.UNKNOWN, fresh = false),
        0L,
    )

    private fun processFreshBatteryAlerts(group: LockGroup, snapshot: EntranceGroupStateSnapshot) {
        val prefs = getSharedPreferences(BATTERY_PREFS, Context.MODE_PRIVATE)
        listOf(snapshot.deviceA, snapshot.deviceB).forEachIndexed { index, device ->
            if (!device.fresh) return@forEachIndexed
            val sampleAt = device.batterySampleObservedAtMillis ?: return@forEachIndexed
            val percent = device.batteryPercent ?: return@forEachIndexed
            val id = group.deviceIds.getOrNull(index) ?: return@forEachIndexed
            val sampleKey = "sample_$id"
            if (sampleAt <= prefs.getLong(sampleKey, Long.MIN_VALUE)) return@forEachIndexed
            val alertKey = "alerted_$id"
            val old = prefs.getBoolean(alertKey, false)
            if (shouldSendLowBatteryAlert(percent, old)) notifyLowBattery(index, percent)
            prefs.edit()
                .putLong(sampleKey, sampleAt)
                .putBoolean(alertKey, nextLowBatteryAlerted(percent, old))
                .apply()
        }
    }

    private fun notifyLowBattery(index: Int, percent: Int) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                LOW_BATTERY_NOTIFICATION_BASE + index,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.small_icon)
                    .setContentTitle("SESAME 玄関")
                    .setContentText("${index + 1}番の電池残量が${percent}%です")
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resultText(result: GroupOperationResult?) = when {
        result == null -> "玄関: 状態確認不能"
        result.status == GroupOperationStatus.SUCCESS && result.action == GroupLockAction.LOCK -> "玄関を施錠しました"
        result.status == GroupOperationStatus.SUCCESS -> "玄関を解錠しました"
        result.status == GroupOperationStatus.PARTIAL -> "玄関: 一部の端末で操作に失敗しました"
        result.status == GroupOperationStatus.FAILURE -> "玄関: 操作に失敗しました"
        else -> "玄関: 別の操作を実行中です"
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle("SESAME 玄関")
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "玄関グループ操作", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun refreshTile() {
        TileService.requestListeningState(this, ComponentName(this, EntranceQuickSettingsTileService::class.java))
    }

    private fun finishStart(startId: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    companion object {
        private const val CHANNEL_ID = "entrance_group_operation"
        private const val BATTERY_PREFS = "entrance_low_battery_alerts"
        private const val FOREGROUND_NOTIFICATION_ID = 41020
        private const val LOW_BATTERY_NOTIFICATION_BASE = 41040
        internal const val EXTRA_WEAR_SOURCE_NODE = "wear_source_node"
        internal const val EXTRA_WEAR_COMMAND_ID = "wear_command_id"
        internal const val EXTRA_WEAR_GROUP_ID = "wear_group_id"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EntranceGroupOperationService::class.java).setAction(EntranceGroupCommand.ONE_BUTTON),
            )
        }

        fun startFromWear(context: Context, sourceNodeId: String, commandId: String, groupId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EntranceGroupOperationService::class.java)
                    .setAction(EntranceGroupCommand.WEAR_ONE_BUTTON)
                    .putExtra(EXTRA_WEAR_SOURCE_NODE, sourceNodeId)
                    .putExtra(EXTRA_WEAR_COMMAND_ID, commandId)
                    .putExtra(EXTRA_WEAR_GROUP_ID, groupId),
            )
        }
    }
}

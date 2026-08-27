package co.candyhouse.app.lockgroup

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import co.candyhouse.app.tabs.devices.ssm2.getNickname
import co.candyhouse.sesame.open.CHDeviceManager
import co.candyhouse.sesame.open.devices.base.CHDevices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LockGroupActivity : AppCompatActivity() {
    private val groupStore: LockGroupStore by lazy { SharedPreferencesLockGroupStore() }
    private val controller by lazy { GroupLockController(SesameGroupLockGateway()) }

    private lateinit var groupNameView: TextView
    private lateinit var stateView: TextView
    private lateinit var membersView: TextView
    private lateinit var resultView: TextView
    private lateinit var lockButton: Button
    private lateinit var unlockButton: Button
    private lateinit var configureButton: Button
    private var operationRunning = false
    private var stateRefreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "玄関"
        setContentView(buildContentView())
        renderGroup()
    }

    override fun onResume() {
        super.onResume()
        renderGroup()
        refreshGroupState()
    }

    override fun onDestroy() {
        stateRefreshJob?.cancel()
        super.onDestroy()
    }

    private fun buildContentView(): ScrollView {
        val padding = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
        }

        groupNameView = TextView(this).apply {
            textSize = 26f
            gravity = Gravity.CENTER
        }
        stateView = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(20))
        }
        membersView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(20))
        }
        lockButton = Button(this).apply {
            text = "すべて施錠"
            setOnClickListener { runGroupAction(GroupLockAction.LOCK) }
        }
        unlockButton = Button(this).apply {
            text = "すべて解錠"
            setOnClickListener { runGroupAction(GroupLockAction.UNLOCK) }
        }
        configureButton = Button(this).apply {
            text = "対象ロックを選択"
            setOnClickListener { showDeviceSelection() }
        }
        resultView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(20), 0, 0)
        }

        val match = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        content.addView(groupNameView, match)
        content.addView(stateView, match)
        content.addView(membersView, match)
        content.addView(lockButton, match)
        content.addView(unlockButton, match)
        content.addView(configureButton, match)
        content.addView(resultView, match)

        return ScrollView(this).apply { addView(content) }
    }

    private fun currentGroup(): LockGroup =
        groupStore.getGroup(SharedPreferencesLockGroupStore.DEFAULT_GROUP_ID)
            ?: SharedPreferencesLockGroupStore.DEFAULT_GROUP

    private fun renderGroup() {
        val group = currentGroup()
        groupNameView.text = group.name
        membersView.text = if (group.deviceIds.isEmpty()) {
            "対象ロック: 未設定"
        } else {
            "対象ロック: ${group.deviceIds.size}台\n" + group.deviceIds.joinToString("\n")
        }
        updateButtons()
    }

    private fun refreshGroupState() {
        val group = currentGroup()
        stateRefreshJob?.cancel()
        stateRefreshJob = lifecycleScope.launch {
            val state = controller.getGroupState(group)
            stateView.text = "状態: ${stateLabel(state)}"
        }
    }

    private fun runGroupAction(action: GroupLockAction) {
        if (operationRunning) return
        val group = currentGroup()
        if (group.deviceIds.isEmpty()) {
            Toast.makeText(this, "先に対象ロックを選択してください", Toast.LENGTH_SHORT).show()
            return
        }

        operationRunning = true
        resultView.text = if (action == GroupLockAction.LOCK) "施錠中…" else "解錠中…"
        updateButtons()

        lifecycleScope.launch {
            try {
                val result = if (action == GroupLockAction.LOCK) {
                    controller.lockGroup(group)
                } else {
                    controller.unlockGroup(group)
                }
                resultView.text = formatResult(result)
                refreshGroupState()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                resultView.text = "失敗: ${error.message ?: error::class.java.simpleName}"
            } finally {
                operationRunning = false
                if (!isFinishing && !isDestroyed) {
                    updateButtons()
                }
            }
        }
    }

    private fun showDeviceSelection() {
        lifecycleScope.launch {
            val devices = loadSupportedLocks()
            if (devices.isEmpty()) {
                Toast.makeText(this@LockGroupActivity, "登録済みの対応ロックがありません", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val group = currentGroup()
            val selected = group.deviceIds.toSet()
            val checked = BooleanArray(devices.size) { index ->
                devices[index].deviceId?.toString() in selected
            }
            val labels = devices.map { device ->
                "${device.getNickname()}\n${device.deviceId}"
            }.toTypedArray()

            AlertDialog.Builder(this@LockGroupActivity)
                .setTitle("${group.name}のロック")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("保存") { _, _ ->
                    val deviceIds = devices.mapIndexedNotNull { index, device ->
                        if (checked[index]) device.deviceId?.toString() else null
                    }
                    groupStore.saveGroup(group.copy(deviceIds = deviceIds))
                    resultView.text = "設定を保存しました"
                    renderGroup()
                    refreshGroupState()
                }
                .show()
        }
    }

    private suspend fun loadSupportedLocks(): List<CHDevices> =
        suspendCancellableCoroutine { continuation ->
            CHDeviceManager.getCandyDevices { result ->
                result.onSuccess { state ->
                    if (continuation.isActive) {
                        continuation.resume(state.data.filter(::isSupportedGroupLockDevice))
                    }
                }
                result.onFailure {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
            }
        }

    private fun updateButtons() {
        val enabled = !operationRunning && currentGroup().deviceIds.isNotEmpty()
        lockButton.isEnabled = enabled
        unlockButton.isEnabled = enabled
        configureButton.isEnabled = !operationRunning
    }

    private fun formatResult(result: GroupOperationResult): String {
        val header = when (result.status) {
            GroupOperationStatus.SUCCESS -> "完了"
            GroupOperationStatus.PARTIAL -> "一部失敗"
            GroupOperationStatus.FAILURE -> "失敗"
            GroupOperationStatus.BUSY -> "別の操作を実行中"
        }
        if (result.deviceResults.isEmpty()) return listOfNotNull(header, result.error).joinToString(": ")

        return buildString {
            append(header)
            result.deviceResults.forEach { deviceResult ->
                append("\n")
                append(deviceResult.deviceId)
                append(": ")
                if (deviceResult.success) {
                    append("成功")
                    append(" (")
                    append(lockStateLabel(deviceResult.finalKnownState))
                    append(")")
                } else {
                    append("失敗")
                    deviceResult.error?.let { append(" - ").append(it) }
                    append(" (")
                    append(lockStateLabel(deviceResult.finalKnownState))
                    append(")")
                }
            }
        }
    }

    private fun stateLabel(state: GroupState): String = when (state) {
        GroupState.LOCKED -> "施錠"
        GroupState.UNLOCKED -> "解錠"
        GroupState.MIXED -> "混在"
        GroupState.UNKNOWN -> "不明"
    }

    private fun lockStateLabel(state: LockState): String = when (state) {
        LockState.LOCKED -> "施錠"
        LockState.UNLOCKED -> "解錠"
        LockState.UNKNOWN -> "状態不明"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

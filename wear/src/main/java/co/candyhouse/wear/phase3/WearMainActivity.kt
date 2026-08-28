package co.candyhouse.wear.phase3

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WearMainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: WearStateStore
    private lateinit var dispatcher: WearControlDispatcher
    private lateinit var stateView: TextView
    private lateinit var singleStateImage: ImageView
    private lateinit var unknownStateView: TextView
    private lateinit var splitStateView: LinearLayout
    private lateinit var topStateImage: ImageView
    private lateinit var topUnknownView: TextView
    private lateinit var topStateLabel: TextView
    private lateinit var bottomStateImage: ImageView
    private lateinit var bottomUnknownView: TextView
    private lateinit var bottomStateLabel: TextView
    private lateinit var confirmedView: TextView
    private lateinit var statusView: TextView
    private lateinit var prefs: SharedPreferences
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wear_main)
        store = WearStateStore(this)
        dispatcher = WearControlDispatcher(WearOneButtonController.production(this))
        prefs = getSharedPreferences(WearStateStore.PREFS, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        stateView = findViewById(R.id.wear_state)
        singleStateImage = findViewById(R.id.wear_single_state_image)
        unknownStateView = findViewById(R.id.wear_unknown_state)
        splitStateView = findViewById(R.id.wear_split_state)
        topStateImage = findViewById(R.id.wear_top_state_image)
        topUnknownView = findViewById(R.id.wear_top_unknown)
        topStateLabel = findViewById(R.id.wear_top_state_label)
        bottomStateImage = findViewById(R.id.wear_bottom_state_image)
        bottomUnknownView = findViewById(R.id.wear_bottom_unknown)
        bottomStateLabel = findViewById(R.id.wear_bottom_state_label)
        confirmedView = findViewById(R.id.wear_confirmed)
        statusView = findViewById(R.id.wear_status)
        findViewById<Button>(R.id.wear_action).setOnClickListener { activate(fromTile = false) }
        render()
        if (intent?.getBooleanExtra(EXTRA_AUTO_ACTIVATE, false) == true) {
            intent?.removeExtra(EXTRA_AUTO_ACTIVATE)
            activate(fromTile = true)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        scope.cancel()
        super.onDestroy()
    }

    private fun activate(fromTile: Boolean) {
        store.saveStatus("確認中…")
        scope.launch {
            val result = if (fromTile) dispatcher.activateFromTile() else dispatcher.activateFromApp()
            store.saveStatus(
                when (result) {
                    WearActivationResult.Locked -> "時計をロック解除してください"
                    WearActivationResult.PhoneUnavailable -> "Phoneに接続できません"
                    is WearActivationResult.Sent -> "確認中…"
                }
            )
        }
    }

    private fun render() {
        val state = store.load()
        val visual = resolveWearVisualState(state.deviceAState, state.deviceBState)
        stateView.text = visual.groupLabel()
        renderVisualState(visual)
        confirmedView.text = if (state.confirmedAt > 0L) {
            "確認 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(state.confirmedAt))}"
        } else {
            "確認できません"
        }
        statusView.text = state.status
    }

    private fun renderVisualState(visual: WearVisualState) {
        singleStateImage.visibility = View.GONE
        unknownStateView.visibility = View.GONE
        splitStateView.visibility = View.GONE

        when (visual) {
            is WearVisualState.Single -> {
                singleStateImage.setImageResource(visual.state.drawableRes())
                singleStateImage.visibility = View.VISIBLE
            }
            is WearVisualState.Split -> {
                renderSplitState(visual.top, topStateImage, topUnknownView, topStateLabel)
                renderSplitState(visual.bottom, bottomStateImage, bottomUnknownView, bottomStateLabel)
                splitStateView.visibility = View.VISIBLE
            }
            WearVisualState.Unknown -> unknownStateView.visibility = View.VISIBLE
        }
    }

    private fun renderSplitState(
        state: WearVisualLockState,
        image: ImageView,
        unknown: TextView,
        label: TextView,
    ) {
        label.text = state.label()
        if (state == WearVisualLockState.UNKNOWN) {
            image.visibility = View.GONE
            unknown.visibility = View.VISIBLE
        } else {
            image.setImageResource(state.drawableRes())
            image.visibility = View.VISIBLE
            unknown.visibility = View.GONE
        }
    }

    private fun WearVisualLockState.drawableRes(): Int = when (this) {
        WearVisualLockState.LOCKED -> R.drawable.phase3_lock_locked
        WearVisualLockState.UNLOCKED -> R.drawable.phase3_lock_unlocked
        WearVisualLockState.UNKNOWN -> error("UNKNOWN has no definite lock image")
    }

    private fun WearVisualLockState.label(): String = when (this) {
        WearVisualLockState.LOCKED -> "施錠"
        WearVisualLockState.UNLOCKED -> "解錠"
        WearVisualLockState.UNKNOWN -> "不明"
    }

    companion object {
        const val EXTRA_AUTO_ACTIVATE = "phase3_auto_activate"
    }
}

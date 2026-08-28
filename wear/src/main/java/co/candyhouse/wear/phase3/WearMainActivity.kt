package co.candyhouse.wear.phase3

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
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
        stateView.text = when (state.groupState) {
            "LOCKED" -> "施錠"
            "UNLOCKED" -> "解錠"
            "MIXED" -> "混在"
            else -> "不明"
        }
        confirmedView.text = if (state.confirmedAt > 0L) {
            "確認 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(state.confirmedAt))}"
        } else {
            "確認できません"
        }
        statusView.text = state.status
    }

    companion object {
        const val EXTRA_AUTO_ACTIVATE = "phase3_auto_activate"
    }
}

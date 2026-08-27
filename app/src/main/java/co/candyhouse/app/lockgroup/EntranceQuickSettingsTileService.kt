package co.candyhouse.app.lockgroup

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class EntranceQuickSettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "玄関"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val launch = Runnable { launchGroupActivity() }
        if (isSecure && isLocked) {
            unlockAndRun(launch)
        } else {
            launch.run()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchGroupActivity() {
        try {
            val intent = Intent(this, LockGroupActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                startActivityAndCollapse(intent)
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to launch group lock activity from Quick Settings", error)
        }
    }

    companion object {
        private const val TAG = "EntranceQSTile"
        private const val REQUEST_CODE = 41001
    }
}

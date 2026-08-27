package co.candyhouse.app.lockgroup

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.graphics.drawable.Icon
import co.candyhouse.app.R

class EntranceQuickSettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val snapshot = EntranceGroupWidgetProvider.cachedSnapshot(this)
        qsTile?.apply {
            label = "玄関"
            when (snapshot.groupState) {
                GroupState.LOCKED -> {
                    state = Tile.STATE_ACTIVE
                    icon = Icon.createWithResource(this@EntranceQuickSettingsTileService, R.drawable.icon_lock)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "施錠"
                }
                GroupState.UNLOCKED -> {
                    state = Tile.STATE_INACTIVE
                    icon = Icon.createWithResource(this@EntranceQuickSettingsTileService, R.drawable.icon_unlock)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "解錠"
                }
                else -> {
                    state = Tile.STATE_INACTIVE
                    icon = Icon.createWithResource(this@EntranceQuickSettingsTileService, R.drawable.small_icon)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = EntranceGroupWidgetProvider.individualStateLabel(snapshot)
                }
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val run = Runnable { EntranceGroupOperationService.start(this) }
        if (isSecure && isLocked) unlockAndRun(run) else run.run()
    }
}

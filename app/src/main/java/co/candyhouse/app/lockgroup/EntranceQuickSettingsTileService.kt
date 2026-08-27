package co.candyhouse.app.lockgroup

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import co.candyhouse.app.R

class EntranceQuickSettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val snapshot = EntranceGroupWidgetProvider.cachedSnapshot(this)
        qsTile?.apply {
            when (snapshot.groupState) {
                GroupState.LOCKED -> {
                    label = "玄関 施錠"
                    state = Tile.STATE_ACTIVE
                    icon = Icon.createWithResource(this@EntranceQuickSettingsTileService, R.drawable.icon_lock)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "施錠"
                }
                GroupState.UNLOCKED -> {
                    label = "玄関 解錠"
                    state = Tile.STATE_INACTIVE
                    icon = Icon.createWithResource(this@EntranceQuickSettingsTileService, R.drawable.icon_unlock)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "解錠"
                }
                GroupState.MIXED -> {
                    label = "玄関 混在"
                    state = Tile.STATE_INACTIVE
                    icon = Icon.createWithResource(this@EntranceQuickSettingsTileService, R.drawable.small_icon)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "混在"
                }
                GroupState.UNKNOWN -> {
                    label = "玄関 不明"
                    state = Tile.STATE_INACTIVE
                    icon = Icon.createWithResource(this@EntranceQuickSettingsTileService, R.drawable.small_icon)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "不明"
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

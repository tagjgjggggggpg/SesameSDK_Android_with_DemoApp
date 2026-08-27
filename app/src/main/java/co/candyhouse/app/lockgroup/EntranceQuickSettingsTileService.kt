package co.candyhouse.app.lockgroup

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class EntranceQuickSettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "玄関 施錠"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        EntranceGroupOperationService.start(this, EntranceGroupCommand.QS_LOCK)
    }
}

class EntranceUnlockQuickSettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "玄関 解錠"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val run = Runnable { EntranceGroupOperationService.start(this, EntranceGroupCommand.QS_UNLOCK) }
        if (isSecure && isLocked) unlockAndRun(run) else run.run()
    }
}

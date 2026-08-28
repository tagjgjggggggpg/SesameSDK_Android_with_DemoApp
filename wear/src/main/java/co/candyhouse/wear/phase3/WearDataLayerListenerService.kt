package co.candyhouse.wear.phase3

import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearDataLayerListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val store = WearStateStore(this)
        dataEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != WearGroupProtocol.STATE_PATH) return@forEach
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            if (map.getInt("protocolVersion") != WearGroupProtocol.VERSION) return@forEach
            store.saveState(
                groupState = map.getString("groupState") ?: "UNKNOWN",
                deviceAState = map.getString("deviceAState") ?: "UNKNOWN",
                deviceBState = map.getString("deviceBState") ?: "UNKNOWN",
                confirmedAt = map.getLong("confirmedAt"),
            )
            requestTileUpdate()
        }
        super.onDataChanged(dataEvents)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == WearGroupProtocol.RESULT_PATH) {
            WearGroupProtocol.decodeResponse(messageEvent.data)?.let { response ->
                WearStateStore(this).saveStatus(response.message.ifBlank { response.overallStatus })
                requestTileUpdate()
            }
        }
        super.onMessageReceived(messageEvent)
    }

    private fun requestTileUpdate() {
        TileService.getUpdater(this).requestUpdate(EntranceTileService::class.java)
    }
}

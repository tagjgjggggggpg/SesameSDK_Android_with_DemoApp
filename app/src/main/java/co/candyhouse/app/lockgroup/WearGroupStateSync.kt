package co.candyhouse.app.lockgroup

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

internal data class WearSyncedGroupState(
    val groupState: String,
    val deviceAState: String,
    val deviceBState: String,
    val confirmedAt: Long,
)

internal object WearGroupStateSync {
    fun fromSnapshot(snapshot: EntranceGroupStateSnapshot) = WearSyncedGroupState(
        groupState = snapshot.groupState.name,
        deviceAState = snapshot.deviceA.state.name,
        deviceBState = snapshot.deviceB.state.name,
        confirmedAt = snapshot.capturedAt,
    )

    fun publish(context: Context, snapshot: EntranceGroupStateSnapshot) {
        val state = fromSnapshot(snapshot)
        val request = PutDataMapRequest.create(WearGroupProtocol.STATE_PATH).apply {
            dataMap.putInt("protocolVersion", WearGroupProtocol.VERSION)
            dataMap.putString("groupState", state.groupState)
            dataMap.putString("deviceAState", state.deviceAState)
            dataMap.putString("deviceBState", state.deviceBState)
            dataMap.putLong("confirmedAt", state.confirmedAt)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
    }
}

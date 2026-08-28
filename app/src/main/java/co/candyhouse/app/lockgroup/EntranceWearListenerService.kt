package co.candyhouse.app.lockgroup

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

class EntranceWearListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        if (messageEvent.path != WearGroupProtocol.COMMAND_PATH) return

        val command = WearGroupProtocol.decodeCommand(messageEvent.data)
        if (command == null) {
            sendResponse(messageEvent.sourceNodeId, WearGroupProtocol.encodeResponse(WearGroupProtocol.invalid()))
            return
        }

        val ledger = WearCommandLedger.forContext(this)
        when (val begin = ledger.begin(command)) {
            WearLedgerBeginResult.New -> {
                try {
                    EntranceGroupOperationService.startFromWear(
                        context = this,
                        sourceNodeId = messageEvent.sourceNodeId,
                        commandId = command.commandId,
                        groupId = command.groupId,
                    )
                } catch (_: Throwable) {
                    val response = WearGroupProtocol.encodeResponse(WearGroupProtocol.interrupted(command.commandId, command.groupId))
                    ledger.interrupt(command.commandId, response)
                    sendResponse(messageEvent.sourceNodeId, response)
                }
            }
            is WearLedgerBeginResult.Duplicate -> sendResponse(messageEvent.sourceNodeId, begin.response)
        }
    }

    private fun sendResponse(nodeId: String, payload: ByteArray) {
        Wearable.getMessageClient(this).sendMessage(nodeId, WearGroupProtocol.RESULT_PATH, payload)
    }
}

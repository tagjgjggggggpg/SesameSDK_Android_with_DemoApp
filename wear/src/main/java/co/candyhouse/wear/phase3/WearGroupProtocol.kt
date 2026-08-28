package co.candyhouse.wear.phase3

import java.net.URLDecoder
import java.net.URLEncoder

internal object WearGroupProtocol {
    const val VERSION = 1
    const val COMMAND_PATH = "/sesame/phase3/one_button"
    const val RESULT_PATH = "/sesame/phase3/result"
    const val STATE_PATH = "/sesame/phase3/state"
    const val PHONE_CAPABILITY = "sesame_phase3_phone"
    const val COMMAND_ONE_BUTTON = "ONE_BUTTON_ACTIVATE"
    const val ENTRANCE_GROUP_ID = "entrance"

    data class Response(
        val commandId: String,
        val groupId: String,
        val resolvedTarget: String,
        val overallStatus: String,
        val deviceAResult: String,
        val deviceBResult: String,
        val message: String,
    )

    fun encodeCommand(commandId: String): ByteArray = encode(
        linkedMapOf(
            "protocolVersion" to VERSION.toString(),
            "command" to COMMAND_ONE_BUTTON,
            "commandId" to commandId,
            "groupId" to ENTRANCE_GROUP_ID,
        )
    )

    fun decodeResponse(payload: ByteArray): Response? {
        val values = decode(payload) ?: return null
        if (values["protocolVersion"]?.toIntOrNull() != VERSION) return null
        val commandId = values["commandId"]?.takeIf { it.isNotBlank() } ?: return null
        val groupId = values["groupId"]?.takeIf { it == ENTRANCE_GROUP_ID } ?: return null
        return Response(
            commandId = commandId,
            groupId = groupId,
            resolvedTarget = values["resolvedTarget"] ?: return null,
            overallStatus = values["overallStatus"] ?: return null,
            deviceAResult = values["deviceAResult"] ?: "NONE",
            deviceBResult = values["deviceBResult"] ?: "NONE",
            message = values["message"] ?: "",
        )
    }

    private fun encode(values: LinkedHashMap<String, String>): ByteArray = values.entries.joinToString("\n") { (key, value) ->
        "$key=${URLEncoder.encode(value, "UTF-8")}"
    }.toByteArray(Charsets.UTF_8)

    private fun decode(payload: ByteArray): Map<String, String>? = runCatching {
        payload.toString(Charsets.UTF_8)
            .lineSequence()
            .filter { it.isNotBlank() }
            .associate { line ->
                val parts = line.split('=', limit = 2)
                require(parts.size == 2)
                parts[0] to URLDecoder.decode(parts[1], "UTF-8")
            }
    }.getOrNull()
}

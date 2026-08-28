package co.candyhouse.app.lockgroup

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

    data class Command(val commandId: String, val groupId: String)
    data class Response(
        val commandId: String,
        val groupId: String,
        val resolvedTarget: String,
        val overallStatus: String,
        val deviceAResult: String = "NONE",
        val deviceBResult: String = "NONE",
        val message: String = "",
    )

    fun encodeCommand(commandId: String, groupId: String = ENTRANCE_GROUP_ID): ByteArray = encode(
        linkedMapOf(
            "protocolVersion" to VERSION.toString(),
            "command" to COMMAND_ONE_BUTTON,
            "commandId" to commandId,
            "groupId" to groupId,
        )
    )

    fun decodeCommand(payload: ByteArray): Command? {
        val values = decode(payload) ?: return null
        if (values["protocolVersion"]?.toIntOrNull() != VERSION) return null
        if (values["command"] != COMMAND_ONE_BUTTON) return null
        if (values.keys.any { it in FORBIDDEN_COMMAND_KEYS }) return null
        val commandId = values["commandId"]?.takeIf { it.isNotBlank() } ?: return null
        val groupId = values["groupId"]?.takeIf { it == ENTRANCE_GROUP_ID } ?: return null
        return Command(commandId, groupId)
    }

    fun encodeResponse(response: Response): ByteArray = encode(
        linkedMapOf(
            "protocolVersion" to VERSION.toString(),
            "commandId" to response.commandId,
            "groupId" to response.groupId,
            "resolvedTarget" to response.resolvedTarget,
            "overallStatus" to response.overallStatus,
            "deviceAResult" to response.deviceAResult,
            "deviceBResult" to response.deviceBResult,
            "message" to response.message,
        )
    )

    fun decodeResponse(payload: ByteArray): Response? {
        val values = decode(payload) ?: return null
        if (values["protocolVersion"]?.toIntOrNull() != VERSION) return null
        val commandId = values["commandId"] ?: return null
        val groupId = values["groupId"] ?: return null
        val target = values["resolvedTarget"] ?: return null
        val status = values["overallStatus"] ?: return null
        return Response(
            commandId = commandId,
            groupId = groupId,
            resolvedTarget = target,
            overallStatus = status,
            deviceAResult = values["deviceAResult"] ?: "NONE",
            deviceBResult = values["deviceBResult"] ?: "NONE",
            message = values["message"] ?: "",
        )
    }

    fun inProgress(commandId: String, groupId: String) = Response(commandId, groupId, "NONE", "IN_PROGRESS", message = "処理中です")
    fun interrupted(commandId: String, groupId: String) = Response(commandId, groupId, "NONE", "INTERRUPTED", message = "前回の処理は中断されました")
    fun invalid(commandId: String = "unknown") = Response(commandId, ENTRANCE_GROUP_ID, "NONE", "INVALID_REQUEST", message = "無効なリクエストです")

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

    private val FORBIDDEN_COMMAND_KEYS = setOf("target", "action", "lock", "unlock", "toggle", "resolvedTarget")
}

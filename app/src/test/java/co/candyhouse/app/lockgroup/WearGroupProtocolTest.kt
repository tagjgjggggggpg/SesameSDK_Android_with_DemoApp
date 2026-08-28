package co.candyhouse.app.lockgroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearGroupProtocolTest {
    @Test
    fun oneButtonCommand_containsVersionCommandIdAndGroupWithoutTarget() {
        val payload = WearGroupProtocol.encodeCommand("command-123")
        val text = payload.toString(Charsets.UTF_8)
        val decoded = WearGroupProtocol.decodeCommand(payload)

        assertNotNull(decoded)
        assertEquals("command-123", decoded!!.commandId)
        assertEquals(WearGroupProtocol.ENTRANCE_GROUP_ID, decoded.groupId)
        assertTrue(text.contains("protocolVersion=1"))
        assertTrue(text.contains("command=ONE_BUTTON_ACTIVATE"))
        assertTrue(text.contains("commandId=command-123"))
        assertFalse(text.contains("target="))
        assertFalse(text.contains("action="))
        assertFalse(text.contains("toggle="))
        assertFalse(text.contains("lock="))
        assertFalse(text.contains("unlock="))
    }

    @Test
    fun commandWithDirectTargetField_isRejected() {
        val unsafe = (WearGroupProtocol.encodeCommand("command-123").toString(Charsets.UTF_8) + "\ntarget=LOCK").toByteArray()
        assertEquals(null, WearGroupProtocol.decodeCommand(unsafe))
    }

    @Test
    fun response_roundTripPreservesCommandIdAndResolvedTarget() {
        val original = WearGroupProtocol.Response(
            commandId = "command-456",
            groupId = WearGroupProtocol.ENTRANCE_GROUP_ID,
            resolvedTarget = "UNLOCK",
            overallStatus = "PARTIAL",
            deviceAResult = "SUCCESS",
            deviceBResult = "FAILURE",
            message = "一部失敗",
        )
        val decoded = WearGroupProtocol.decodeResponse(WearGroupProtocol.encodeResponse(original))
        assertEquals(original, decoded)
    }
}

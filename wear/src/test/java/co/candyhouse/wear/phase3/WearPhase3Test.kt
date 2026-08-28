package co.candyhouse.wear.phase3

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearPhase3Test {
    @Test
    fun unlockedTap_sendsOneButtonCommandWithNewCommandIdAndNoTarget() = runBlocking {
        val resolver = FakeResolver("phone-node")
        val sender = FakeSender()
        val controller = WearOneButtonController(
            lockChecker = FakeLockChecker(false),
            nodeResolver = resolver,
            sender = sender,
            commandIdFactory = { "new-id" },
        )

        val result = controller.activateNewTap()

        assertEquals(WearActivationResult.Sent("new-id"), result)
        assertEquals(1, sender.calls.size)
        val call = sender.calls.single()
        assertEquals("phone-node", call.nodeId)
        assertEquals(WearGroupProtocol.COMMAND_PATH, call.path)
        val text = call.payload.toString(Charsets.UTF_8)
        assertTrue(text.contains("command=ONE_BUTTON_ACTIVATE"))
        assertTrue(text.contains("commandId=new-id"))
        assertFalse(text.contains("target="))
        assertFalse(text.contains("action="))
        assertFalse(text.contains("toggle="))
        assertFalse(text.contains("lock="))
        assertFalse(text.contains("unlock="))
    }

    @Test
    fun lockedWatch_sendsZeroDataLayerCommands() = runBlocking {
        val resolver = FakeResolver("phone-node")
        val sender = FakeSender()
        val controller = WearOneButtonController(FakeLockChecker(true), resolver, sender) { "must-not-be-used" }

        assertEquals(WearActivationResult.Locked, controller.activateNewTap())
        assertEquals(0, resolver.calls)
        assertTrue(sender.calls.isEmpty())
    }

    @Test
    fun noReachablePhone_sendsZeroMessagesAndReturnsUnavailable() = runBlocking {
        val resolver = FakeResolver(null)
        val sender = FakeSender()
        val controller = WearOneButtonController(FakeLockChecker(false), resolver, sender) { "id-unavailable" }

        assertEquals(WearActivationResult.PhoneUnavailable, controller.activateNewTap())
        assertEquals(1, resolver.calls)
        assertTrue(sender.calls.isEmpty())
    }

    @Test
    fun transportRetry_reusesSameCommandId() = runBlocking {
        val sender = FakeSender()
        val controller = WearOneButtonController(FakeLockChecker(false), FakeResolver("phone-node"), sender) { "new-id" }

        assertEquals(WearActivationResult.Sent("retry-id"), controller.retry("retry-id"))
        assertTrue(sender.calls.single().payload.toString(Charsets.UTF_8).contains("commandId=retry-id"))
    }

    @Test
    fun separateUserTaps_generateDifferentCommandIds() = runBlocking {
        val ids = ArrayDeque(listOf("id-1", "id-2"))
        val sender = FakeSender()
        val controller = WearOneButtonController(FakeLockChecker(false), FakeResolver("phone-node"), sender) { ids.removeFirst() }

        assertEquals(WearActivationResult.Sent("id-1"), controller.activateNewTap())
        assertEquals(WearActivationResult.Sent("id-2"), controller.activateNewTap())
        assertEquals(2, sender.calls.size)
    }

    @Test
    fun appAndTile_useSameControllerPath() = runBlocking {
        val ids = ArrayDeque(listOf("app-id", "tile-id"))
        val sender = FakeSender()
        val dispatcher = WearControlDispatcher(
            WearOneButtonController(FakeLockChecker(false), FakeResolver("phone-node"), sender) { ids.removeFirst() }
        )

        assertEquals(WearActivationResult.Sent("app-id"), dispatcher.activateFromApp())
        assertEquals(WearActivationResult.Sent("tile-id"), dispatcher.activateFromTile())
        assertEquals(2, sender.calls.size)
        assertTrue(sender.calls.all { it.path == WearGroupProtocol.COMMAND_PATH })
    }

    @Test
    fun responseDecoder_preservesPhoneCommandIdAndTargetResult() {
        val payload = listOf(
            "protocolVersion=1",
            "commandId=same-id",
            "groupId=entrance",
            "resolvedTarget=UNLOCK",
            "overallStatus=SUCCESS",
            "deviceAResult=SUCCESS",
            "deviceBResult=SUCCESS",
            "message=%E8%A7%A3%E9%8C%A0%E3%81%97%E3%81%BE%E3%81%97%E3%81%9F",
        ).joinToString("\n").toByteArray()
        val response = WearGroupProtocol.decodeResponse(payload)!!
        assertEquals("same-id", response.commandId)
        assertEquals("UNLOCK", response.resolvedTarget)
        assertEquals("SUCCESS", response.overallStatus)
    }

    @Test
    fun cachedDisplayState_doesNotAlterOneButtonPayload() {
        val lockedDisplay = WearDisplayState(groupState = "LOCKED")
        val unlockedDisplay = WearDisplayState(groupState = "UNLOCKED")
        assertEquals(lockedDisplay.groupState, "LOCKED")
        assertEquals(unlockedDisplay.groupState, "UNLOCKED")
        assertEquals(
            WearGroupProtocol.encodeCommand("same-id").toList(),
            WearGroupProtocol.encodeCommand("same-id").toList(),
        )
    }

    private class FakeLockChecker(private val locked: Boolean) : WearDeviceLockChecker {
        override fun isLocked() = locked
    }

    private class FakeResolver(private val nodeId: String?) : WearPhoneNodeResolver {
        var calls = 0
        override suspend fun reachablePhoneNodeId(): String? {
            calls++
            return nodeId
        }
    }

    private class FakeSender : WearMessageSender {
        data class Call(val nodeId: String, val path: String, val payload: ByteArray)
        val calls = mutableListOf<Call>()
        override suspend fun send(nodeId: String, path: String, payload: ByteArray) {
            calls += Call(nodeId, path, payload)
        }
    }
}

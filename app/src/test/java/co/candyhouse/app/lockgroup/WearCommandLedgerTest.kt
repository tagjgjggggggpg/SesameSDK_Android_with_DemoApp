package co.candyhouse.app.lockgroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearCommandLedgerTest {
    @Test
    fun newCommand_executesOnce_andDuplicateInProgressDoesNotExecuteAgain() {
        val store = MemoryStore()
        val ledger = WearCommandLedger(store, processId = "process-a", now = { 1L })
        val command = WearGroupProtocol.Command("id-1", WearGroupProtocol.ENTRANCE_GROUP_ID)
        var operations = 0

        if (ledger.begin(command) == WearLedgerBeginResult.New) operations++
        val duplicate = ledger.begin(command)
        if (duplicate == WearLedgerBeginResult.New) operations++

        assertEquals(1, operations)
        assertTrue(duplicate is WearLedgerBeginResult.Duplicate)
        val response = WearGroupProtocol.decodeResponse((duplicate as WearLedgerBeginResult.Duplicate).response)
        assertEquals("IN_PROGRESS", response!!.overallStatus)
    }

    @Test
    fun duplicateTerminal_returnsCachedResultWithoutReexecution() {
        val store = MemoryStore()
        val ledger = WearCommandLedger(store, processId = "process-a", now = { 2L })
        val command = WearGroupProtocol.Command("id-2", WearGroupProtocol.ENTRANCE_GROUP_ID)
        var operations = 0
        if (ledger.begin(command) == WearLedgerBeginResult.New) operations++
        val terminal = WearGroupProtocol.encodeResponse(
            WearGroupProtocol.Response("id-2", WearGroupProtocol.ENTRANCE_GROUP_ID, "LOCK", "SUCCESS", "SUCCESS", "SUCCESS", "施錠しました")
        )
        ledger.complete("id-2", terminal)

        val duplicate = ledger.begin(command)
        if (duplicate == WearLedgerBeginResult.New) operations++

        assertEquals(1, operations)
        assertTrue(duplicate is WearLedgerBeginResult.Duplicate)
        assertEquals("SUCCESS", WearGroupProtocol.decodeResponse((duplicate as WearLedgerBeginResult.Duplicate).response)!!.overallStatus)
    }

    @Test
    fun duplicateFromPreviousProcess_becomesInterruptedWithoutReexecution() {
        val store = MemoryStore()
        val command = WearGroupProtocol.Command("id-3", WearGroupProtocol.ENTRANCE_GROUP_ID)
        assertEquals(WearLedgerBeginResult.New, WearCommandLedger(store, processId = "old-process").begin(command))

        var operations = 0
        val duplicate = WearCommandLedger(store, processId = "new-process").begin(command)
        if (duplicate == WearLedgerBeginResult.New) operations++

        assertEquals(0, operations)
        assertTrue(duplicate is WearLedgerBeginResult.Duplicate)
        assertEquals("INTERRUPTED", WearGroupProtocol.decodeResponse((duplicate as WearLedgerBeginResult.Duplicate).response)!!.overallStatus)
    }

    @Test
    fun ledgerKeepsOnlyMostRecentSixteenCommandIds() {
        val store = MemoryStore()
        val ledger = WearCommandLedger(store, processId = "process-a")
        repeat(20) { index ->
            assertEquals(WearLedgerBeginResult.New, ledger.begin(WearGroupProtocol.Command("id-$index", WearGroupProtocol.ENTRANCE_GROUP_ID)))
        }
        assertEquals(16, store.records.size)
        assertEquals("id-19", store.records.first().commandId)
        assertEquals("id-4", store.records.last().commandId)
    }

    private class MemoryStore : WearCommandLedgerStore {
        var records: List<WearLedgerRecord> = emptyList()
        override fun load(): List<WearLedgerRecord> = records
        override fun save(records: List<WearLedgerRecord>) { this.records = records.toList() }
    }
}

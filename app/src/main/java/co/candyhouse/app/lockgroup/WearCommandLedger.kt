package co.candyhouse.app.lockgroup

import android.content.Context
import java.util.UUID

internal enum class WearLedgerState { IN_PROGRESS, INTERRUPTED, TERMINAL }

internal data class WearLedgerRecord(
    val commandId: String,
    val groupId: String,
    val state: WearLedgerState,
    val ownerProcessId: String,
    val response: String,
    val updatedAtMillis: Long,
)

internal interface WearCommandLedgerStore {
    fun load(): List<WearLedgerRecord>
    fun save(records: List<WearLedgerRecord>)
}

internal sealed interface WearLedgerBeginResult {
    data object New : WearLedgerBeginResult
    data class Duplicate(val response: ByteArray) : WearLedgerBeginResult
}

internal class WearCommandLedger(
    private val store: WearCommandLedgerStore,
    private val processId: String = PROCESS_ID,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun begin(command: WearGroupProtocol.Command): WearLedgerBeginResult = synchronized(PROCESS_LOCK) {
        val records = store.load().toMutableList()
        val index = records.indexOfFirst { it.commandId == command.commandId }
        if (index >= 0) {
            val existing = records[index]
            if (existing.state == WearLedgerState.IN_PROGRESS && existing.ownerProcessId != processId) {
                val interrupted = WearGroupProtocol.encodeResponse(WearGroupProtocol.interrupted(existing.commandId, existing.groupId)).toString(Charsets.UTF_8)
                records[index] = existing.copy(
                    state = WearLedgerState.INTERRUPTED,
                    ownerProcessId = processId,
                    response = interrupted,
                    updatedAtMillis = now(),
                )
                store.save(records)
                return@synchronized WearLedgerBeginResult.Duplicate(interrupted.toByteArray(Charsets.UTF_8))
            }
            return@synchronized WearLedgerBeginResult.Duplicate(existing.response.toByteArray(Charsets.UTF_8))
        }

        val initial = WearGroupProtocol.encodeResponse(WearGroupProtocol.inProgress(command.commandId, command.groupId)).toString(Charsets.UTF_8)
        records.add(
            0,
            WearLedgerRecord(
                commandId = command.commandId,
                groupId = command.groupId,
                state = WearLedgerState.IN_PROGRESS,
                ownerProcessId = processId,
                response = initial,
                updatedAtMillis = now(),
            )
        )
        store.save(records.take(MAX_RECORDS))
        WearLedgerBeginResult.New
    }

    fun complete(commandId: String, response: ByteArray) = synchronized(PROCESS_LOCK) {
        update(commandId, WearLedgerState.TERMINAL, response)
    }

    fun interrupt(commandId: String, response: ByteArray) = synchronized(PROCESS_LOCK) {
        update(commandId, WearLedgerState.INTERRUPTED, response)
    }

    private fun update(commandId: String, state: WearLedgerState, response: ByteArray) {
        val records = store.load().toMutableList()
        val index = records.indexOfFirst { it.commandId == commandId }
        if (index < 0) return
        records[index] = records[index].copy(
            state = state,
            ownerProcessId = processId,
            response = response.toString(Charsets.UTF_8),
            updatedAtMillis = now(),
        )
        store.save(records)
    }

    companion object {
        const val MAX_RECORDS = 16
        private val PROCESS_LOCK = Any()
        private val PROCESS_ID = UUID.randomUUID().toString()
        fun forContext(context: Context): WearCommandLedger = WearCommandLedger(SharedPreferencesWearCommandLedgerStore(context))
    }
}

internal class SharedPreferencesWearCommandLedgerStore(context: Context) : WearCommandLedgerStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): List<WearLedgerRecord> {
        val ids = prefs.getString(KEY_ORDER, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        return ids.mapNotNull { id ->
            val state = runCatching { WearLedgerState.valueOf(prefs.getString("state_$id", "") ?: "") }.getOrNull() ?: return@mapNotNull null
            val groupId = prefs.getString("group_$id", null) ?: return@mapNotNull null
            val owner = prefs.getString("owner_$id", null) ?: return@mapNotNull null
            val response = prefs.getString("response_$id", null) ?: return@mapNotNull null
            WearLedgerRecord(id, groupId, state, owner, response, prefs.getLong("updated_$id", 0L))
        }
    }

    override fun save(records: List<WearLedgerRecord>) {
        val editor = prefs.edit().clear().putString(KEY_ORDER, records.joinToString("\n") { it.commandId })
        records.forEach { record ->
            editor
                .putString("state_${record.commandId}", record.state.name)
                .putString("group_${record.commandId}", record.groupId)
                .putString("owner_${record.commandId}", record.ownerProcessId)
                .putString("response_${record.commandId}", record.response)
                .putLong("updated_${record.commandId}", record.updatedAtMillis)
        }
        check(editor.commit()) { "Failed to persist Wear command ledger" }
    }

    companion object {
        private const val PREFS = "phase3_wear_command_ledger"
        private const val KEY_ORDER = "order"
    }
}

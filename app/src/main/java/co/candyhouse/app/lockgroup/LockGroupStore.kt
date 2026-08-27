package co.candyhouse.app.lockgroup

import androidx.core.content.edit
import co.candyhouse.sesame.utils.SharedPreferencesUtils
import org.json.JSONArray
import org.json.JSONObject

interface LockGroupStore {
    fun getGroup(groupId: String): LockGroup?
    fun getGroups(): List<LockGroup>
    fun saveGroup(group: LockGroup)
}

class SharedPreferencesLockGroupStore : LockGroupStore {
    override fun getGroup(groupId: String): LockGroup? = getGroups().firstOrNull { it.id == groupId }

    override fun getGroups(): List<LockGroup> {
        val raw = SharedPreferencesUtils.preferences.getString(PREF_KEY, null)
            ?: return listOf(DEFAULT_GROUP)

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val devicesJson = item.optJSONArray("deviceIds") ?: JSONArray()
                    val deviceIds = buildList {
                        for (deviceIndex in 0 until devicesJson.length()) {
                            devicesJson.optString(deviceIndex)
                                .takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                    }.distinct()

                    add(
                        LockGroup(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            deviceIds = deviceIds,
                        )
                    )
                }
            }.ifEmpty { listOf(DEFAULT_GROUP) }
        }.getOrElse { listOf(DEFAULT_GROUP) }
    }

    override fun saveGroup(group: LockGroup) {
        val normalized = group.copy(
            id = group.id.trim(),
            name = group.name.trim(),
            deviceIds = group.deviceIds.filter { it.isNotBlank() }.distinct(),
        )
        require(normalized.id.isNotEmpty()) { "Group id must not be empty" }
        require(normalized.name.isNotEmpty()) { "Group name must not be empty" }

        val updated = getGroups().toMutableList()
        val existingIndex = updated.indexOfFirst { it.id == normalized.id }
        if (existingIndex >= 0) {
            updated[existingIndex] = normalized
        } else {
            updated += normalized
        }

        val array = JSONArray()
        updated.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("deviceIds", JSONArray(item.deviceIds))
                }
            )
        }
        SharedPreferencesUtils.preferences.edit { putString(PREF_KEY, array.toString()) }
    }

    companion object {
        const val DEFAULT_GROUP_ID = "entrance"
        val DEFAULT_GROUP = LockGroup(
            id = DEFAULT_GROUP_ID,
            name = "玄関",
            deviceIds = emptyList(),
        )

        private const val PREF_KEY = "lock_groups_v1"
    }
}

package com.nacon01.kunekune

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Minimal string store used to keep persistence code independent of Android in tests. */
interface StringPreferenceStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class SharedPreferencesStringStore(private val preferences: SharedPreferences) : StringPreferenceStore {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }
}

/** Persistent catalog and selection state for block targets. */
class BlockTargetStore(private val store: StringPreferenceStore) {
    constructor(context: Context) : this(
        SharedPreferencesStringStore(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        )
    )

    fun all(): List<BlockTarget> = readState().targets.values.sortedBy { it.id }

    fun targets(): List<BlockTarget> = all()

    fun get(id: String): BlockTarget? = readState().targets[id]

    fun add(target: BlockTarget): BlockTarget {
        val current = readState()
        val targets = current.targets.toMutableMap().apply { put(target.id, target) }
        val initial = current.initialTargetId ?: target.id
        val selected = current.selectedTargetIds.toMutableSet().apply {
            if (current.targets.isEmpty()) add(target.id)
        }
        writeState(repair(State(targets, selected, initial)))
        return target
    }

    fun upsert(target: BlockTarget): BlockTarget = add(target)

    fun remove(id: String): Boolean {
        val current = readState()
        if (id !in current.targets) return false
        val targets = current.targets.toMutableMap().apply { remove(id) }
        val selected = current.selectedTargetIds - id
        val initial = if (current.initialTargetId == id) {
            targets.keys.sorted().firstOrNull()
        } else {
            current.initialTargetId
        }
        writeState(repair(State(targets, selected, initial)))
        return true
    }

    fun selectedTargetIds(): Set<String> = readState().selectedTargetIds

    fun setSelectedTargetIds(ids: Set<String>): Set<String> {
        val current = readState()
        val selected = ids.filter { it in current.targets }.toSet()
        val repaired = repair(current.copy(selectedTargetIds = selected))
        writeState(repaired)
        return repaired.selectedTargetIds
    }

    fun setSelectedTargetIds(ids: Iterable<String>): Set<String> = setSelectedTargetIds(ids.toSet())

    fun setSelected(id: String, selected: Boolean): Set<String> {
        val ids = selectedTargetIds().toMutableSet()
        if (selected) ids.add(id) else ids.remove(id)
        return setSelectedTargetIds(ids)
    }

    fun initialTargetId(): String? = readState().initialTargetId

    fun setInitialTargetId(id: String?): String? {
        val current = readState()
        val repaired = repair(current.copy(initialTargetId = id))
        writeState(repaired)
        return repaired.initialTargetId
    }

    private fun readState(): State {
        val raw = store.getString(KEY_STATE) ?: return State.empty()
        return try {
            decode(raw).let(::repair)
        } catch (_: Exception) {
            State.empty()
        }
    }

    private fun writeState(state: State) {
        if (state.targets.isEmpty()) {
            store.remove(KEY_STATE)
        } else {
            store.putString(KEY_STATE, encode(state))
        }
    }

    private fun repair(state: State): State {
        val targets = state.targets.toSortedMap()
        val validSelected = state.selectedTargetIds.filter { it in targets }.toSet()
        val initial = state.initialTargetId?.takeIf { it in targets } ?: targets.keys.firstOrNull()
        val selected = if (initial == null) validSelected else validSelected + initial
        return State(targets, selected, initial)
    }

    private fun encode(state: State): String = JSONObject().apply {
        put("version", VERSION)
        put("targets", JSONArray().apply {
            state.targets.values.sortedBy { it.id }.forEach { target -> put(target.toJson()) }
        })
        put("selectedTargetIds", JSONArray().apply {
            state.selectedTargetIds.sorted().forEach(::put)
        })
        if (state.initialTargetId == null) put("initialTargetId", JSONObject.NULL)
        else put("initialTargetId", state.initialTargetId)
    }.toString()

    private fun decode(raw: String): State {
        val json = JSONObject(raw)
        require(json.keys().asSequence().toSet() == setOf(
            "version", "targets", "selectedTargetIds", "initialTargetId"
        ))
        require(json.getInt("version") == VERSION)
        val targetsJson = json.getJSONArray("targets")
        val targets = linkedMapOf<String, BlockTarget>()
        for (index in 0 until targetsJson.length()) {
            val target = targetsJson.getJSONObject(index).toTarget()
            require(targets.put(target.id, target) == null) { "Duplicate target" }
        }
        val selectedJson = json.getJSONArray("selectedTargetIds")
        val selected = linkedSetOf<String>()
        for (index in 0 until selectedJson.length()) {
            val id = selectedJson.get(index)
            require(id is String && id.isNotBlank())
            require(selected.add(id)) { "Duplicate selection" }
        }
        val initial = if (json.isNull("initialTargetId")) null else json.getString("initialTargetId")
        require(initial == null || initial.isNotBlank())
        return State(targets, selected, initial)
    }

    private fun BlockTarget.toJson(): JSONObject = JSONObject().apply {
        when (this@toJson) {
            is BlockTarget.App -> {
                put("type", "app")
                put("packageName", packageName)
                put("label", label)
            }
            is BlockTarget.Domain -> {
                put("type", "domain")
                put("host", host)
                put("includeSubdomains", includeSubdomains)
                put("launchUrl", launchUrl)
            }
        }
    }

    private fun JSONObject.toTarget(): BlockTarget {
        val keys = keys().asSequence().toSet()
        return when (getString("type")) {
            "app" -> {
                require(keys == setOf("type", "packageName", "label"))
                BlockTarget.app(getString("packageName"), getString("label"))
            }
            "domain" -> {
                require(keys == setOf("type", "host", "includeSubdomains", "launchUrl"))
                val host = getString("host")
                val launchUrl = getString("launchUrl")
                val target = BlockTarget.domain(host, getBoolean("includeSubdomains"), launchUrl)
                require(target.host == host)
                target
            }
            else -> error("Unknown target type")
        }
    }

    private data class State(
        val targets: Map<String, BlockTarget>,
        val selectedTargetIds: Set<String>,
        val initialTargetId: String?
    ) {
        companion object {
            fun empty() = State(emptyMap(), emptySet(), null)
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "block_targets"
        private const val KEY_STATE = "state"
        private const val VERSION = 1
    }
}

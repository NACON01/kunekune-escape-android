package com.nacon01.kunekune

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Durable home-zone state. stopAll is a one-transition signal and is intentionally not stored. */
class HomeZoneRuntimeStore(private val store: StringPreferenceStore) {
    constructor(context: Context) : this(
        SharedPreferencesStringStore(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        )
    )

    fun load(): HomeZoneSnapshot {
        val raw = store.getString(KEY_SNAPSHOT) ?: return HomeZoneSnapshot.initial()
        return try {
            val json = JSONObject(raw)
            val state = HomeZoneState.valueOf(json.getString("state"))
            val visitGeneration = json.getLong("visitGeneration")
            require(visitGeneration >= 0)
            val lastKnownInside = if (json.isNull("lastKnownInside")) {
                null
            } else {
                json.getBoolean("lastKnownInside")
            }
            val grant = json.optJSONObject("sessionGrant")?.let { grantJson ->
                val ids = grantJson.getJSONArray("grantedTargetIds").toStringSet()
                SessionGrant(
                    visitGeneration = grantJson.getLong("visitGeneration"),
                    routeId = grantJson.getString("routeId"),
                    grantedTargetIds = ids,
                    initialTargetId = grantJson.getString("initialTargetId")
                ).takeIf { it.visitGeneration == visitGeneration }
            }
            HomeZoneSnapshot(
                state = state,
                visitGeneration = visitGeneration,
                sessionGrant = grant,
                lastKnownInside = lastKnownInside,
                unknownWarning = json.getBoolean("unknownWarning"),
                stopAll = false
            )
        } catch (_: Exception) {
            HomeZoneSnapshot.initial()
        }
    }

    fun save(snapshot: HomeZoneSnapshot) {
        val json = JSONObject().apply {
            put("state", snapshot.state.name)
            put("visitGeneration", snapshot.visitGeneration)
            if (snapshot.lastKnownInside == null) put("lastKnownInside", JSONObject.NULL)
            else put("lastKnownInside", snapshot.lastKnownInside)
            put("unknownWarning", snapshot.unknownWarning)
            snapshot.sessionGrant?.let { grant ->
                put("sessionGrant", JSONObject().apply {
                    put("visitGeneration", grant.visitGeneration)
                    put("routeId", grant.routeId)
                    put("grantedTargetIds", JSONArray(grant.grantedTargetIds.sorted()))
                    put("initialTargetId", grant.initialTargetId)
                })
            }
        }
        store.putString(KEY_SNAPSHOT, json.toString())
    }

    fun clear() = store.remove(KEY_SNAPSHOT)

    private fun JSONArray.toStringSet(): Set<String> = buildSet {
        for (index in 0 until length()) add(getString(index))
    }.also { require(it.isNotEmpty()) }

    companion object {
        private const val PREFERENCES_NAME = "home_zone_runtime"
        private const val KEY_SNAPSHOT = "snapshot"
    }
}

/** Process-safe facade over the pure reducer; each accepted transition is persisted atomically. */
class HomeZoneRuntimeCoordinator(
    private val store: HomeZoneRuntimeStore
) {
    constructor(context: Context) : this(HomeZoneRuntimeStore(context))

    private var machine = HomeZoneStateMachine(store.load())

    @Synchronized
    fun snapshot(): HomeZoneSnapshot = machine.snapshot

    @Synchronized
    fun reload(): HomeZoneSnapshot {
        machine = HomeZoneStateMachine(store.load())
        return machine.snapshot
    }

    @Synchronized
    fun transition(event: HomeZoneEvent): HomeZoneTransition {
        val transition = machine.transition(event)
        if (transition.accepted) {
            machine = transition.machine
            store.save(machine.snapshot.copy(stopAll = false))
        }
        return transition
    }

    @Synchronized
    fun observe(observation: LocationObservation): HomeZoneTransition =
        transition(HomeZoneEvent.Location(observation))

    @Synchronized
    fun onLocation(observation: LocationObservation): HomeZoneTransition = observe(observation)

    @Synchronized
    fun awaitMarker(): HomeZoneTransition = transition(HomeZoneEvent.AwaitingMarker)

    @Synchronized
    fun markerFound(): HomeZoneTransition = transition(HomeZoneEvent.MarkerFound)

    @Synchronized
    fun destinationSelected(): HomeZoneTransition =
        transition(HomeZoneEvent.DestinationSelected)

    @Synchronized
    fun selectTargets(grant: SessionGrant): HomeZoneTransition =
        transition(HomeZoneEvent.TargetsSelected(grant))

    @Synchronized
    fun guidanceStarted(grant: SessionGrant): HomeZoneTransition =
        transition(HomeZoneEvent.GuidanceStarted(grant))

    @Synchronized
    fun resetWorkflow(): HomeZoneTransition = transition(HomeZoneEvent.WorkflowReset)
}

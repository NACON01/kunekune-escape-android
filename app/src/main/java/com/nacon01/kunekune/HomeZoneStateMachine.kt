package com.nacon01.kunekune

import java.util.UUID

data class SessionGrant(
    val visitGeneration: Long,
    val routeId: String,
    val grantedTargetIds: Set<String>,
    val initialTargetId: String
) {
    init {
        require(visitGeneration >= 0) { "Visit generation must not be negative" }
        require(routeId.isCanonicalUuid()) { "Route ID must be a canonical UUID" }
        require(grantedTargetIds.isNotEmpty() && grantedTargetIds.all { it.isNotBlank() }) {
            "Granted target IDs must be nonempty"
        }
        require(initialTargetId.isNotBlank() && initialTargetId in grantedTargetIds) {
            "Initial target must be included in the grant"
        }
    }

    companion object {
        private fun String.isCanonicalUuid(): Boolean = try {
            UUID.fromString(this).toString() == this
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}

enum class HomeZoneState {
    OUTSIDE_OFF,
    INSIDE_LOCKED,
    AWAITING_MARKER,
    AWAITING_DESTINATION,
    AWAITING_TARGET_SELECTION,
    STARTING_GUIDANCE,
    GUIDANCE_ACTIVE
}

typealias HomeZoneRuntimeState = HomeZoneState

enum class LocationObservation {
    INSIDE,
    OUTSIDE,
    UNKNOWN
}

sealed interface HomeZoneEvent {
    data class Location(val observation: LocationObservation) : HomeZoneEvent
    data object AwaitingMarker : HomeZoneEvent
    data object MarkerFound : HomeZoneEvent
    data object DestinationSelected : HomeZoneEvent
    data class TargetsSelected(val grant: SessionGrant) : HomeZoneEvent
    data class GuidanceStarted(val grant: SessionGrant) : HomeZoneEvent
}

data class HomeZoneSnapshot(
    val state: HomeZoneState,
    val visitGeneration: Long,
    val sessionGrant: SessionGrant?,
    val lastKnownInside: Boolean?,
    val unknownWarning: Boolean,
    val stopAll: Boolean
) {
    val isInside: Boolean
        get() = lastKnownInside == true

    companion object {
        fun initial(): HomeZoneSnapshot = HomeZoneSnapshot(
            state = HomeZoneState.OUTSIDE_OFF,
            visitGeneration = 0,
            sessionGrant = null,
            lastKnownInside = null,
            unknownWarning = true,
            stopAll = false
        )
    }
}

data class HomeZoneTransition(
    val accepted: Boolean,
    val snapshot: HomeZoneSnapshot,
    val reason: String? = null
) {
    val stopAll: Boolean
        get() = snapshot.stopAll

    val machine: HomeZoneStateMachine
        get() = HomeZoneStateMachine(snapshot)
}

/** Pure state reducer for home-zone entry, grants, guidance, and exit. */
class HomeZoneStateMachine(
    val snapshot: HomeZoneSnapshot = HomeZoneSnapshot.initial()
) {
    val state: HomeZoneState get() = snapshot.state
    val visitGeneration: Long get() = snapshot.visitGeneration
    val sessionGrant: SessionGrant? get() = snapshot.sessionGrant
    val stopAll: Boolean get() = snapshot.stopAll
    val unknownWarning: Boolean get() = snapshot.unknownWarning

    fun transition(event: HomeZoneEvent): HomeZoneTransition = reduce(snapshot, event)

    fun next(event: HomeZoneEvent): HomeZoneStateMachine = transition(event).machine

    fun observe(observation: LocationObservation): HomeZoneTransition =
        transition(HomeZoneEvent.Location(observation))

    fun onLocation(observation: LocationObservation): HomeZoneTransition = observe(observation)

    fun awaitMarker(): HomeZoneTransition = transition(HomeZoneEvent.AwaitingMarker)

    fun requestMarker(): HomeZoneTransition = awaitMarker()

    fun markerFound(): HomeZoneTransition = transition(HomeZoneEvent.MarkerFound)

    fun destinationSelected(): HomeZoneTransition = transition(HomeZoneEvent.DestinationSelected)

    fun selectTargets(grant: SessionGrant): HomeZoneTransition =
        transition(HomeZoneEvent.TargetsSelected(grant))

    fun startGuidance(grant: SessionGrant): HomeZoneTransition = selectTargets(grant)

    fun guidanceStarted(grant: SessionGrant): HomeZoneTransition =
        transition(HomeZoneEvent.GuidanceStarted(grant))

    companion object {
        fun reduce(snapshot: HomeZoneSnapshot, event: HomeZoneEvent): HomeZoneTransition {
            val next = when (event) {
                is HomeZoneEvent.Location -> reduceLocation(snapshot, event.observation)
                HomeZoneEvent.AwaitingMarker -> workflow(
                    snapshot,
                    HomeZoneState.INSIDE_LOCKED,
                    HomeZoneState.AWAITING_MARKER
                )
                HomeZoneEvent.MarkerFound -> workflow(
                    snapshot,
                    HomeZoneState.AWAITING_MARKER,
                    HomeZoneState.AWAITING_DESTINATION
                )
                HomeZoneEvent.DestinationSelected -> workflow(
                    snapshot,
                    HomeZoneState.AWAITING_DESTINATION,
                    HomeZoneState.AWAITING_TARGET_SELECTION
                )
                is HomeZoneEvent.TargetsSelected -> grantWorkflow(
                    snapshot,
                    HomeZoneState.AWAITING_TARGET_SELECTION,
                    HomeZoneState.STARTING_GUIDANCE,
                    event.grant
                )
                is HomeZoneEvent.GuidanceStarted -> grantWorkflow(
                    snapshot,
                    HomeZoneState.STARTING_GUIDANCE,
                    HomeZoneState.GUIDANCE_ACTIVE,
                    event.grant,
                    requireExistingGrant = true
                )
            }
            return next
        }

        private fun reduceLocation(
            snapshot: HomeZoneSnapshot,
            observation: LocationObservation
        ): HomeZoneTransition = when (observation) {
            LocationObservation.UNKNOWN -> HomeZoneTransition(
                accepted = true,
                snapshot = snapshot.copy(unknownWarning = true, stopAll = false)
            )
            LocationObservation.INSIDE -> {
                val entering = snapshot.lastKnownInside != true
                HomeZoneTransition(
                    accepted = true,
                    snapshot = snapshot.copy(
                        state = if (entering) HomeZoneState.INSIDE_LOCKED else snapshot.state,
                        lastKnownInside = true,
                        unknownWarning = false,
                        stopAll = false
                    )
                )
            }
            LocationObservation.OUTSIDE -> {
                val exited = snapshot.lastKnownInside == true
                HomeZoneTransition(
                    accepted = true,
                    snapshot = snapshot.copy(
                        state = HomeZoneState.OUTSIDE_OFF,
                        visitGeneration = snapshot.visitGeneration + if (exited) 1 else 0,
                        sessionGrant = null,
                        lastKnownInside = false,
                        unknownWarning = false,
                        stopAll = true
                    )
                )
            }
        }

        private fun workflow(
            snapshot: HomeZoneSnapshot,
            expected: HomeZoneState,
            next: HomeZoneState
        ): HomeZoneTransition {
            if (!snapshot.isInside || snapshot.state != expected) {
                return rejected(snapshot, "Invalid transition from ${snapshot.state}")
            }
            return accepted(snapshot.copy(state = next, stopAll = false))
        }

        private fun grantWorkflow(
            snapshot: HomeZoneSnapshot,
            expected: HomeZoneState,
            next: HomeZoneState,
            grant: SessionGrant,
            requireExistingGrant: Boolean = false
        ): HomeZoneTransition {
            if (!snapshot.isInside || snapshot.state != expected) {
                return rejected(snapshot, "Invalid transition from ${snapshot.state}")
            }
            if (grant.visitGeneration != snapshot.visitGeneration) {
                return rejected(snapshot, "Session grant belongs to another visit")
            }
            if (requireExistingGrant && snapshot.sessionGrant != grant) {
                return rejected(snapshot, "Guidance grant does not match the selected grant")
            }
            return accepted(snapshot.copy(state = next, sessionGrant = grant, stopAll = false))
        }

        private fun accepted(snapshot: HomeZoneSnapshot) = HomeZoneTransition(true, snapshot)

        private fun rejected(snapshot: HomeZoneSnapshot, reason: String) =
            HomeZoneTransition(false, snapshot.copy(stopAll = false), reason)
    }
}

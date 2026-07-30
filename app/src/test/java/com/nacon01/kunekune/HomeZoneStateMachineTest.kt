package com.nacon01.kunekune

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeZoneStateMachineTest {
    private val routeId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000").toString()

    @Test
    fun initialAndUnknownStateHaveNoHistoryAndWarn() {
        val initial = HomeZoneStateMachine()
        assertEquals(HomeZoneState.OUTSIDE_OFF, initial.state)
        assertEquals(0, initial.visitGeneration)
        assertTrue(initial.unknownWarning)

        val unknown = initial.observe(LocationObservation.UNKNOWN)
        assertTrue(unknown.accepted)
        assertEquals(HomeZoneState.OUTSIDE_OFF, unknown.snapshot.state)
        assertTrue(unknown.snapshot.unknownWarning)
    }

    @Test
    fun unknownAfterInsidePreservesWorkflowAndOutsideStopsAndClears() {
        var machine = HomeZoneStateMachine()
        machine = machine.observe(LocationObservation.INSIDE).machine
        machine = machine.awaitMarker().machine
        val grant = grant(machine.visitGeneration)
        machine = machine.markerFound().machine
            .destinationSelected().machine
            .selectTargets(grant).machine

        val unknown = machine.observe(LocationObservation.UNKNOWN)
        assertEquals(HomeZoneState.STARTING_GUIDANCE, unknown.snapshot.state)
        assertEquals(grant, unknown.snapshot.sessionGrant)
        assertTrue(unknown.snapshot.unknownWarning)

        val outside = unknown.machine.observe(LocationObservation.OUTSIDE)
        assertTrue(outside.stopAll)
        assertEquals(HomeZoneState.OUTSIDE_OFF, outside.snapshot.state)
        assertEquals(1, outside.snapshot.visitGeneration)
        assertNull(outside.snapshot.sessionGrant)
    }

    @Test
    fun fullWorkflowAcceptsMatchingGrantAndRejectsInvalidTransitions() {
        var machine = HomeZoneStateMachine().observe(LocationObservation.INSIDE).machine
        assertFalse(machine.markerFound().accepted)
        machine = machine.awaitMarker().machine
            .markerFound().machine
            .destinationSelected().machine

        val grant = grant(machine.visitGeneration)
        val starting = machine.selectTargets(grant)
        assertTrue(starting.accepted)
        assertEquals(HomeZoneState.STARTING_GUIDANCE, starting.snapshot.state)
        assertFalse(starting.machine.guidanceStarted(grant.copy(visitGeneration = 1)).accepted)

        val active = starting.machine.guidanceStarted(grant)
        assertTrue(active.accepted)
        assertEquals(HomeZoneState.GUIDANCE_ACTIVE, active.snapshot.state)
        assertFalse(active.machine.awaitMarker().accepted)
    }

    @Test
    fun staleGrantIsRejectedAndCannotExpandExistingGrant() {
        var machine = HomeZoneStateMachine().observe(LocationObservation.INSIDE).machine
            .awaitMarker().machine
            .markerFound().machine
            .destinationSelected().machine
        val stale = grant(1)
        val rejected = machine.selectTargets(stale)

        assertFalse(rejected.accepted)
        assertEquals(HomeZoneState.AWAITING_TARGET_SELECTION, rejected.snapshot.state)
        assertNull(rejected.snapshot.sessionGrant)
    }

    @Test
    fun sessionGrantRequiresCanonicalRouteAndIncludedInitialTarget() {
        assertInvalidGrant { SessionGrant(-1, routeId, setOf("app:one"), "app:one") }
        assertInvalidGrant { SessionGrant(0, routeId.uppercase(), setOf("app:one"), "app:one") }
        assertInvalidGrant { SessionGrant(0, "not-a-uuid", setOf("app:one"), "app:one") }
        assertInvalidGrant { SessionGrant(0, routeId, setOf("app:one"), "app:two") }
        assertInvalidGrant { SessionGrant(0, routeId, setOf(" "), " ") }
    }

    private fun grant(generation: Long, targets: Set<String> = setOf("app:one")) = SessionGrant(
        visitGeneration = generation,
        routeId = routeId,
        grantedTargetIds = targets,
        initialTargetId = targets.first()
    )

    private fun assertInvalidGrant(factory: () -> SessionGrant) {
        try {
            factory()
            throw AssertionError("Expected invalid grant")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}

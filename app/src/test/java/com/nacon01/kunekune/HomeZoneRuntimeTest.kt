package com.nacon01.kunekune

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeZoneRuntimeTest {
    private class MemoryStore(private var value: String? = null) : StringPreferenceStore {
        override fun getString(key: String): String? = value
        override fun putString(key: String, value: String) { this.value = value }
        override fun remove(key: String) { value = null }
        fun corrupt() { value = "not-json" }
    }

    private val routeId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000").toString()

    @Test
    fun processReloadPreservesStateAndCorruptDataFallsBack() {
        val store = MemoryStore()
        val first = HomeZoneRuntimeCoordinator(HomeZoneRuntimeStore(store))
        first.observe(LocationObservation.INSIDE)
        first.observe(LocationObservation.UNKNOWN)
        val reloaded = HomeZoneRuntimeCoordinator(HomeZoneRuntimeStore(store))
        assertEquals(HomeZoneState.INSIDE_LOCKED, reloaded.snapshot().state)
        assertEquals(true, reloaded.snapshot().lastKnownInside)
        assertTrue(reloaded.snapshot().unknownWarning)

        store.corrupt()
        val fallback = HomeZoneRuntimeCoordinator(HomeZoneRuntimeStore(store)).snapshot()
        assertEquals(HomeZoneState.OUTSIDE_OFF, fallback.state)
        assertEquals(0L, fallback.visitGeneration)
        assertNull(fallback.lastKnownInside)
        assertTrue(fallback.unknownWarning)
    }

    @Test
    fun exitIncrementsGenerationClearsGrantAndSignalsStop() {
        val coordinator = HomeZoneRuntimeCoordinator(HomeZoneRuntimeStore(MemoryStore()))
        coordinator.observe(LocationObservation.INSIDE)
        coordinator.awaitMarker()
        coordinator.markerFound()
        coordinator.destinationSelected()
        val grant = SessionGrant(0, routeId, setOf("app:one"), "app:one")
        coordinator.selectTargets(grant)

        val exit = coordinator.observe(LocationObservation.OUTSIDE)
        assertTrue(exit.stopAll)
        assertEquals(HomeZoneState.OUTSIDE_OFF, exit.snapshot.state)
        assertEquals(1L, exit.snapshot.visitGeneration)
        assertNull(exit.snapshot.sessionGrant)
    }

    @Test
    fun unknownWithoutHistoryStaysOffAndUnknownAfterInsidePreservesDecision() {
        val coordinator = HomeZoneRuntimeCoordinator(HomeZoneRuntimeStore(MemoryStore()))
        coordinator.observe(LocationObservation.UNKNOWN)
        assertEquals(HomeZoneState.OUTSIDE_OFF, coordinator.snapshot().state)
        coordinator.observe(LocationObservation.INSIDE)
        coordinator.awaitMarker()
        coordinator.observe(LocationObservation.UNKNOWN)
        assertEquals(HomeZoneState.AWAITING_MARKER, coordinator.snapshot().state)
        assertTrue(coordinator.snapshot().unknownWarning)
    }
}


package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeZonePreferencesTest {
    @Test
    fun validatesFiniteCoordinatesAndAllowsSmallPositiveRadiusWithWarning() {
        val config = HomeZoneConfig(35.0, 139.0, 25.0)

        assertTrue(config.hasSmallRadiusWarning)
        try {
            HomeZoneConfig(Double.NaN, 0.0, 100.0)
            throw AssertionError("Expected invalid latitude")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            HomeZoneConfig(0.0, 0.0, 0.0)
            throw AssertionError("Expected invalid radius")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun persistsAndClearsHomeZoneAndCorruptDataIsSafe() {
        val preferences = TestStringPreferences()
        val homeZone = HomeZonePreferences(preferences)
        val config = HomeZoneConfig(35.681236, 139.767125, 100.0)

        homeZone.save(config)
        assertEquals(config, homeZone.get())
        homeZone.clear()
        assertNull(homeZone.get())

        preferences.values["config"] = "{}"
        assertNull(homeZone.get())
    }
}

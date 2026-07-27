package com.nacon01.kunekune

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredRouteValidatorTest {
    @Test
    fun markerYAxisRoutePassesUsingThreeDimensionalLength() {
        assertTrue(StoredRouteValidator.isValid(listOf(v(0f, 0f, 0f), v(0f, 0.2f, 0f))))
    }

    @Test
    fun onePointRouteFails() {
        assertFalse(StoredRouteValidator.isValid(listOf(v(0f, 0f, 0f))))
    }

    @Test
    fun identicalPointsFail() {
        assertFalse(StoredRouteValidator.isValid(listOf(v(1f, 2f, 3f), v(1f, 2f, 3f))))
    }

    @Test
    fun nonFinitePointsFail() {
        assertFalse(StoredRouteValidator.isValid(listOf(v(0f, 0f, 0f), v(Float.NaN, 0f, 0f))))
        assertFalse(StoredRouteValidator.isValid(listOf(v(0f, 0f, 0f), v(0f, Float.POSITIVE_INFINITY, 0f))))
    }

    @Test
    fun tinyThreeDimensionalRouteFails() {
        assertFalse(StoredRouteValidator.isValid(listOf(v(0f, 0f, 0f), v(0f, 0.1999f, 0f))))
    }

    private fun v(x: Float, y: Float, z: Float) = GuidanceVector3(x, y, z)
}

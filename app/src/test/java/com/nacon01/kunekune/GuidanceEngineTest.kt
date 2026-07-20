package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceEngineTest {
    private val engine = GuidanceEngine()

    @Test
    fun straightRouteProjectsAndLooksAhead() {
        val result = engine.calculate(
            route = listOf(v(0f, 0f, 0f), v(0f, 0f, -3f)),
            currentPosition = v(0.4f, 0f, -0.5f),
            currentForward = v(0f, 0f, -1f)
        )

        assertEquals(0.5f, result.projectedDistanceMeters, 0.0001f)
        assertEquals(0f, result.projectedPoint.x, 0.0001f)
        assertEquals(-1.5f, result.targetPoint.z, 0.0001f)
        assertEquals(2.5f, result.remainingDistanceMeters, 0.0001f)
        assertEquals(16.6667f, result.progressPercent, 0.001f)
        assertEquals(21.8014f, result.signedAngleDegrees, 0.001f)
        assertTrue(!result.arrived)
    }

    @Test
    fun lRouteUsesNearestSegmentAndSignedAngle() {
        val result = engine.calculate(
            route = listOf(v(0f, 0f, 0f), v(2f, 0f, 0f), v(2f, 0f, -2f)),
            currentPosition = v(2.2f, 0f, -0.5f),
            currentForward = v(0f, 0f, -1f)
        )

        assertEquals(2.5f, result.projectedDistanceMeters, 0.0001f)
        assertEquals(1.5f, result.remainingDistanceMeters, 0.0001f)
        assertEquals(2f, result.targetPoint.x, 0.0001f)
        assertEquals(-1.5f, result.targetPoint.z, 0.0001f)
        assertEquals(11.3099f, result.signedAngleDegrees, 0.001f)
    }

    @Test
    fun angleSignIsNegativeForTurnFromMinusZToPlusX() {
        val result = engine.calculate(
            route = listOf(v(0f, 0f, 0f), v(1f, 0f, 0f)),
            currentPosition = v(0f, 0f, 0f),
            currentForward = v(0f, 0f, -1f)
        )

        assertEquals(-90f, result.signedAngleDegrees, 0.0001f)
    }

    @Test
    fun arrivesWithinRemainingRouteThreshold() {
        val result = engine.calculate(
            route = listOf(v(0f, 0f, 0f), v(2f, 0f, 0f)),
            currentPosition = v(1.5f, 0f, 0f),
            currentForward = v(1f, 0f, 0f)
        )

        assertEquals(0.5f, result.remainingDistanceMeters, 0.0001f)
        assertTrue(result.arrived)
    }

    private fun v(x: Float, y: Float, z: Float) = GuidanceVector3(x, y, z)
}


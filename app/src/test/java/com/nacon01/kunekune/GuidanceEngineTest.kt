package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceEngineTest {
    private val engine = GuidanceEngine()

    @Test
    fun ordinaryStraightRouteProjectsLooksAheadAndReportsDistances() {
        val result = engine.calculate(
            route = listOf(v(0f, 0f, 0f), v(0f, 0f, -3f)),
            currentPosition = v(0.4f, 0.7f, -0.5f),
            currentForward = v(0f, 0f, -1f)
        )

        assertEquals(0.5f, result.projectedDistanceMeters, 0.0001f)
        assertEquals(-1.5f, result.targetPoint.z, 0.0001f)
        assertEquals(2.5f, result.remainingDistanceMeters, 0.0001f)
        assertEquals(0.4f, result.crossTrackDistanceMeters, 0.0001f)
        assertEquals(2.5318f, result.endpointDistanceMeters, 0.001f)
        assertEquals(21.8014f, result.signedAngleDegrees, 0.001f)
        assertFalse(result.arrived)
    }

    @Test
    fun farPerpendicularToEndpointDoesNotArriveEvenWhenProjectionIsAtEnd() {
        val result = engine.calculate(
            route = listOf(v(0f, 0f, 0f), v(2f, 0f, 0f)),
            currentPosition = v(2f, 0f, 3f),
            currentForward = v(1f, 0f, 0f)
        )
        assertEquals(0f, result.remainingDistanceMeters, 0.0001f)
        assertEquals(3f, result.endpointDistanceMeters, 0.0001f)
        assertFalse(result.arrived)
    }

    @Test
    fun postureHeightVariationDoesNotPreventArrival() {
        val result = engine.calculate(
            route = listOf(v(0f, 1.7f, 0f), v(2f, 1.7f, 0f)),
            currentPosition = v(1.55f, 0.6f, 0.1f),
            currentForward = v(1f, 0f, 0f)
        )
        assertTrue(result.arrived)
        assertEquals(0.4609f, result.endpointDistanceMeters, 0.001f)
    }

    @Test
    fun verticallyOnlyStoredRouteFailsAfterGravityWorldValidation() {
        val storedRoute = listOf(v(0f, 0f, 0f), v(0f, 0.21f, 0f))
        assertTrue(StoredRouteValidator.isValid(storedRoute))

        // An upright marker pose can leave this route with no horizontal world length.
        val worldRoute = GuidanceCoordinateTransform.routeToWorld(storedRoute) { point -> point }
        assertFalse(engine.isValidRoute(worldRoute))
    }

    @Test
    fun wallMarkerRouteIsTransformedIntoGravityWorldHorizontalPlane() {
        // For the documented wall-mounted marker, marker +Y points away from
        // the wall and marker -Z points upward: (x, y, z) -> (x, -z, y).
        val markerRoute = listOf(v(0f, 0f, 0f), v(0f, 1f, 0f))
        val worldRoute = GuidanceCoordinateTransform.routeToWorld(markerRoute) { point ->
            v(point.x, -point.z, point.y)
        }

        val result = engine.calculate(
            route = worldRoute,
            currentPosition = v(0f, 0f, 0.75f),
            currentForward = v(0f, 0f, 1f)
        )

        assertEquals(0.75f, result.projectedDistanceMeters, 0.0001f)
        assertEquals(0.25f, result.endpointDistanceMeters, 0.0001f)
        assertTrue(result.arrived)
    }

    @Test(expected = IllegalArgumentException::class)
    fun onePointRouteIsRejected() {
        engine.calculate(listOf(v(0f, 0f, 0f)), v(0f, 0f, 0f), v(1f, 0f, 0f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroLengthRouteIsRejected() {
        engine.calculate(
            listOf(v(1f, 0f, 1f), v(1f, 2f, 1f)),
            v(1f, 0f, 1f),
            v(1f, 0f, 0f)
        )
    }

    @Test
    fun endpointAndCorridorThresholdsAreBothRequired() {
        val strict = GuidanceEngine(arrivalThresholdMeters = 0.6f, arrivalCorridorMeters = 0.2f)
        val route = listOf(v(0f, 0f, 0f), v(2f, 0f, 0f))
        assertTrue(strict.calculate(route, v(1.5f, 8f, 0.1f), v(1f, 0f, 0f)).arrived)
        assertFalse(strict.calculate(route, v(1.5f, 8f, 0.3f), v(1f, 0f, 0f)).arrived)
        assertFalse(strict.calculate(route, v(1.3f, 8f, 0f), v(1f, 0f, 0f)).arrived)
    }

    @Test
    fun arrivalRequiresDwellAndThenLatches() {
        val latch = GuidanceArrivalLatch(dwellSeconds = 1f)
        assertFalse(latch.update(true, 0.4f))
        assertFalse(latch.update(false, 0.2f))
        assertFalse(latch.update(true, 0.25f))
        assertFalse(latch.update(true, 0.25f))
        assertFalse(latch.update(true, 0.25f))
        assertTrue(latch.update(true, 0.25f))
        assertTrue(latch.update(false, 10f))
    }

    @Test
    fun oneLongArrivalFrameCannotSatisfyDwell() {
        val latch = GuidanceArrivalLatch(dwellSeconds = 1f)

        assertFalse(latch.update(true, 10f))
        assertFalse(latch.update(false, 0.1f))
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
        assertEquals(11.3099f, result.signedAngleDegrees, 0.001f)
    }

    private fun v(x: Float, y: Float, z: Float) = GuidanceVector3(x, y, z)
}

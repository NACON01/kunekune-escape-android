package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRecorderTest {
    @Test
    fun addsFirstPointAndOnlyAddsAfterMinimumDistance() {
        val recorder = RouteRecorder()
        recorder.start(1_000L)

        assertTrue(recorder.sample(RoutePosition(0f, 0f, 0f), cameraTracking = true, nowMillis = 1_000L))
        assertFalse(recorder.sample(RoutePosition(0.2f, 0f, 0f), cameraTracking = true, nowMillis = 2_000L))
        assertTrue(recorder.sample(RoutePosition(0.3f, 0f, 0f), cameraTracking = true, nowMillis = 3_000L))

        val route = recorder.stop(recordedAtEpochMillis = 123L)!!
        assertEquals(2, route.points.size)
        assertEquals(2_000L, route.points[1].elapsedMillis)
        assertEquals(0.3f, route.totalDistanceMeters, 0.0001f)
    }

    @Test
    fun doesNotAddPointWhileCameraIsPaused() {
        val recorder = RouteRecorder()
        recorder.start(0L)

        assertFalse(recorder.sample(RoutePosition(0f, 0f, 0f), cameraTracking = false, nowMillis = 0L))
        assertTrue(recorder.sample(RoutePosition(0f, 0f, 0f), cameraTracking = true, nowMillis = 10L))
        assertFalse(recorder.sample(RoutePosition(1f, 0f, 0f), cameraTracking = false, nowMillis = 20L))

        assertEquals(1, recorder.stop(1L)!!.points.size)
    }
}

package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalBehaviorTest {
    @Test
    fun releaseReachesFullConcealmentAwayAndClearsOnReturn() {
        val controller = ArrivalController(ArrivalBehavior.RELEASE, 30, 1)
        controller.onArrival(0.8f)
        controller.update(0f, targetForeground = true, atDestination = false)

        val concealed = controller.update(60f, targetForeground = true, atDestination = false)
        assertEquals(ArrivalPhase.FULL_CONCEALMENT, concealed.phase)
        assertEquals(1f, concealed.density, 0.0001f)

        val returned = controller.update(0f, targetForeground = true, atDestination = true)
        assertEquals(ArrivalPhase.AT_DESTINATION, returned.phase)
        assertEquals(0f, returned.density, 0.0001f)
    }

    @Test
    fun fadeOutIncreasesConcealmentAndPausesAwayFromForeground() {
        val controller = ArrivalController(ArrivalBehavior.FADE_OUT, 1, 30)
        controller.onArrival(0.4f)

        val halfway = controller.update(30f, targetForeground = true)
        assertEquals(0.7f, halfway.density, 0.0001f)
        val paused = controller.update(30f, targetForeground = false)
        assertEquals(0.7f, paused.density, 0.0001f)
        assertTrue(paused.timedFadePaused)
        assertEquals(ArrivalPhase.ARRIVAL_FADE, paused.phase)

        val complete = controller.update(30f, targetForeground = true)
        assertEquals(1f, complete.density, 0.0001f)
        assertEquals(ArrivalPhase.FULL_CONCEALMENT, complete.phase)
    }

    @Test
    fun releaseClearsAtDestinationAndRefadesAfterHystereticDeparture() {
        val controller = ArrivalController(ArrivalBehavior.RELEASE, 30, 1)
        controller.onArrival(0.8f)
        assertEquals(0f, controller.currentState().density, 0.0001f)

        val away = controller.update(0f, targetForeground = true, atDestination = false)
        assertEquals(ArrivalPhase.AWAY_FROM_DESTINATION_FADE, away.phase)
        val halfway = controller.update(30f, targetForeground = true, atDestination = false)
        assertEquals(0.5f, halfway.density, 0.0001f)

        val returned = controller.update(0f, targetForeground = true, atDestination = true)
        assertEquals(ArrivalPhase.AT_DESTINATION, returned.phase)
        assertEquals(0f, returned.density, 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun durationsMustBeWithinOneToOneHundredTwentyMinutes() {
        ArrivalController(ArrivalBehavior.FADE_OUT, 0, 30)
    }
}

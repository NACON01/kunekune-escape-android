package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Test

class FadeControllerTest {
    @Test
    fun stagnationForThirtySecondsReachesBlack() {
        val controller = FadeController()

        controller.update(true, 0f, 0f)
        val density = repeatStagnation(controller, seconds = 30)

        assertEquals(1f, density, 0.0001f)
    }

    @Test
    fun stagnationForFifteenSecondsReachesHalf() {
        val controller = FadeController()

        controller.update(true, 0f, 0f)
        val density = repeatStagnation(controller, seconds = 15)

        assertEquals(0.5f, density, 0.0001f)
    }

    @Test
    fun forwardProgressClearsTheScrim() {
        val controller = FadeController()

        controller.update(true, 0f, 15f)
        val density = controller.update(true, 0.04f, 1f)

        assertEquals(0f, density, 0.0001f)
    }

    @Test
    fun notStartedKeepsTheScrimClear() {
        val controller = FadeController()

        val density = controller.update(false, 2f, 30f)

        assertEquals(0f, density, 0.0001f)
    }

    @Test
    fun stagnationClampsAfterThirtySeconds() {
        val controller = FadeController()

        controller.update(true, 0f, 0f)
        val density = repeatStagnation(controller, seconds = 45)

        assertEquals(1f, density, 0.0001f)
    }

    private fun repeatStagnation(controller: FadeController, seconds: Int): Float {
        var density = 0f
        repeat(seconds) {
            density = controller.update(true, 0f, 1f)
        }
        return density
    }
}

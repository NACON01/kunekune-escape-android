package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FadeControllerTest {
    @Test
    fun slowWalkingContinuouslyRestoresAt15_30And60Hz() {
        for (hz in listOf(15, 30, 60)) {
            val controller = FadeController()
            var arc = 0f
            repeatSeconds(hz, 16f) { dt ->
                controller.update(true, true, true, arc, dt)
            }
            val dark = controller.currentDensity()
            assertTrue("hz=$hz should darken first", dark > 0.25f)

            repeatSeconds(hz, 5f) { dt ->
                arc += 0.20f * dt
                controller.update(true, true, true, arc, dt)
            }
            assertTrue("hz=$hz should recover", controller.currentDensity() < dark - 0.15f)
        }
    }

    @Test
    fun twelveCentimeterPerSecondWalkingWithAlternatingMillimeterNoiseRestoresAt15_30And60Hz() {
        for (hz in listOf(15, 30, 60)) {
            val controller = FadeController()
            repeatSeconds(hz, 16f) { dt ->
                controller.update(true, true, true, 0f, dt)
            }
            val dark = controller.currentDensity()
            var arc = 0f
            var frame = 0
            repeatSeconds(hz, 8f) { dt ->
                val noise = if (frame++ % 2 == 0) 0.003f else -0.003f
                arc += 0.12f * dt + noise
                controller.update(true, true, true, arc, dt)
            }
            assertTrue(
                "hz=$hz should recover at 0.12m/s",
                controller.currentDensity() < dark - 0.10f
            )
        }
    }

    @Test
    fun stationaryVioNoiseDoesNotEarnProgress() {
        val controller = FadeController()
        var frame = 0
        repeatSeconds(60, 20f) { dt ->
            val noise = if (frame++ % 2 == 0) 0.003f else -0.003f
            controller.update(true, true, true, noise, dt)
        }
        assertTrue(controller.currentDensity() > 0.4f)
    }

    @Test
    fun backwardMotionDoesNotEarnProgress() {
        val controller = FadeController()
        var arc = 3f
        repeatSeconds(30, 15f) { dt ->
            arc -= 0.25f * dt
            controller.update(true, true, true, arc, dt)
        }
        assertTrue(controller.currentDensity() > 0.3f)
    }

    @Test
    fun substantialBackwardStepClearsPendingProgress() {
        val controller = FadeController()

        repeatSeconds(30, 4f) { dt ->
            controller.update(true, true, true, 0f, dt)
        }
        val before = controller.currentDensity()
        controller.update(true, true, true, 0f, 1f / 30f)
        controller.update(true, true, true, 0.06f, 1f / 30f)
        controller.update(true, true, true, 0.01f, 1f / 30f)
        controller.update(true, true, true, 0.08f, 1f / 30f)

        assertTrue("a backward step must not preserve pending reward", controller.currentDensity() >= before)
    }

    @Test
    fun offRouteForwardProjectionDoesNotEarnProgress() {
        val controller = FadeController()
        var arc = 0f
        repeatSeconds(30, 15f) { dt ->
            arc += 0.4f * dt
            controller.update(true, true, false, arc, dt)
        }
        assertTrue(controller.currentDensity() > 0.3f)
    }

    @Test
    fun briefTrackingLossFreezesDensityAndRecoveryNeedsFreshProgress() {
        val controller = FadeController()
        repeatSeconds(30, 15f) { dt -> controller.update(true, true, true, 0f, dt) }
        val beforeLoss = controller.currentDensity()
        repeatSeconds(30, 2f) { dt -> controller.update(true, false, false, 8f, dt) }
        assertEquals(beforeLoss, controller.currentDensity(), 0.0001f)

        // 復帰直後の大きな座標差は報酬にならず、以降の正味前進だけが回復させる。
        controller.update(true, true, true, 8f, 1f / 30f)
        assertEquals(beforeLoss, controller.currentDensity(), 0.002f)
        var arc = 8f
        repeatSeconds(30, 2f) { dt ->
            arc += 0.4f * dt
            controller.update(true, true, true, arc, dt)
        }
        assertTrue(controller.currentDensity() < beforeLoss)
    }

    @Test
    fun prolongedTrackingLossNeverClearsAsReward() {
        val controller = FadeController()
        repeatSeconds(30, 18f) { dt -> controller.update(true, true, true, 0f, dt) }
        val beforeLoss = controller.currentDensity()
        repeatSeconds(30, 20f) { dt -> controller.update(true, false, false, 50f, dt) }
        assertEquals(beforeLoss, controller.currentDensity(), 0.0001f)
    }

    @Test
    fun startupAndStagnationGraceKeepInitialViewClear() {
        val controller = FadeController()
        repeatSeconds(60, 2.9f) { dt -> controller.update(true, true, true, 0f, dt) }
        assertEquals(0f, controller.currentDensity(), 0.0001f)
    }

    @Test
    fun configuredFadeDurationReachesFullDensity() {
        for (durationSeconds in listOf(15, 30, 60, 120)) {
            val controller = FadeController.forFadeDurationSeconds(durationSeconds)
            repeatSeconds(30, 3f + durationSeconds + 0.5f) { dt ->
                controller.update(true, true, true, 0f, dt)
            }
            assertEquals("duration=$durationSeconds", 1f, controller.currentDensity(), 0.001f)
        }
    }

    private fun repeatSeconds(hz: Int, seconds: Float, block: (Float) -> Unit) {
        val dt = 1f / hz
        repeat((seconds * hz).toInt()) { block(dt) }
    }
}

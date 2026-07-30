package com.nacon01.kunekune

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalMessageControllerTest {
    @Test
    fun arrivalIsVisibleImmediately() {
        var now = 10_000L
        val controller = ArrivalMessageController(monotonicClockMillis = { now })

        assertTrue(controller.update(GuidanceState.ARRIVED))
    }

    @Test
    fun arrivalIsVisibleAt1999Milliseconds() {
        var now = 10_000L
        val controller = ArrivalMessageController(monotonicClockMillis = { now })

        assertTrue(controller.update(GuidanceState.ARRIVED))
        now += 1_999L
        assertTrue(controller.update(GuidanceState.ARRIVED))
    }

    @Test
    fun arrivalIsHiddenAtExactlyTwoSeconds() {
        var now = 10_000L
        val controller = ArrivalMessageController(monotonicClockMillis = { now })

        assertTrue(controller.update(GuidanceState.ARRIVED))
        now += 2_000L
        assertFalse(controller.update(GuidanceState.ARRIVED))
    }

    @Test
    fun repeatedArrivedSnapshotsDoNotRestartDeadline() {
        var now = 0L
        val controller = ArrivalMessageController(monotonicClockMillis = { now })

        assertTrue(controller.onArrived())
        now = 1_999L
        assertTrue(controller.onArrived())
        now = 2_000L
        assertFalse(controller.onArrived())
        now = 3_999L
        assertFalse(controller.onArrived())
    }

    @Test
    fun resetNonArrivedAndNewSessionRestartTheDisplayWindow() {
        var now = 0L
        val controller = ArrivalMessageController(monotonicClockMillis = { now })

        assertTrue(controller.onArrived())
        now = 2_000L
        assertFalse(controller.onArrived())

        controller.reset()
        assertTrue(controller.onArrived())

        now = 2_000L
        controller.onNonArrived()
        assertTrue(controller.onArrived())

        now = 4_000L
        controller.newSession()
        assertTrue(controller.onArrived())
    }
}

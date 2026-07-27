package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousViewingTrackerTest {
    @Test
    fun armedInterventionHidesAwayAndRestoresImmediatelyOnReturn() {
        val tracker = ContinuousViewingTracker(1)
        tracker.update(0L, "browser", "browser", true, false, true)
        val armed = tracker.update(
            60_000L * NANOS_PER_MILLISECOND,
            "browser",
            "browser",
            true,
            false,
            true
        )
        assertTrue(armed.interventionArmed)
        assertTrue(armed.currentlyVisible)

        val away = tracker.update(
            61_000L * NANOS_PER_MILLISECOND,
            "maps",
            "browser",
            true,
            false,
            true
        )
        assertTrue(away.interventionArmed)
        assertFalse(away.currentlyVisible)

        val returned = tracker.update(
            62_000L * NANOS_PER_MILLISECOND,
            "browser",
            "browser",
            true,
            false,
            true
        )
        assertTrue(returned.interventionArmed)
        assertTrue(returned.currentlyVisible)
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }

    @Test
    fun thresholdIsReachedAtTheConfiguredContinuousBoundary() {
        val tracker = ContinuousViewingTracker(1)
        tracker.update(0L, "browser", "browser", true, false, true)
        val before = tracker.update(59_999L * NANOS_PER_MILLISECOND, "browser", "browser", true, false, true)
        assertFalse(before.interventionActive)
        val atBoundary = tracker.update(60_000L * NANOS_PER_MILLISECOND, "browser", "browser", true, false, true)
        assertTrue(atBoundary.interventionActive)
        assertEquals(60_000L, atBoundary.elapsedMillis)
    }

    @Test
    fun otherPackageScreenLockAndMissingDataResetContinuousTime() {
        val tracker = ContinuousViewingTracker(1)
        tracker.update(0L, "browser", "browser", true, false, true)
        tracker.update(30_000L * NANOS_PER_MILLISECOND, "browser", "browser", true, false, true)
        assertTrue(tracker.update(31_000L * NANOS_PER_MILLISECOND, "maps", "browser", true, false, true).reset)
        assertEquals(0L, tracker.update(32_000L * NANOS_PER_MILLISECOND, "browser", "browser", true, false, true).elapsedMillis)
        assertTrue(tracker.update(33_000L * NANOS_PER_MILLISECOND, "browser", "browser", false, false, true).reset)
        assertTrue(tracker.update(34_000L * NANOS_PER_MILLISECOND, "browser", "browser", true, true, true).reset)
        assertTrue(tracker.update(35_000L * NANOS_PER_MILLISECOND, "browser", "browser", true, false, false).reset)
    }

    @Test
    fun invalidObservationRequiresAValidBaselineBeforeCountingAgain() {
        val tracker = ContinuousViewingTracker(1)
        tracker.update(0L, "browser", "browser", true, false, true)
        tracker.update(30_000L * NANOS_PER_MILLISECOND, "browser", "browser", true, false, true)

        tracker.update(31_000L * NANOS_PER_MILLISECOND, "maps", "browser", true, false, true)
        val firstValidAfterOtherPackage = tracker.update(
            35_000L * NANOS_PER_MILLISECOND,
            "browser",
            "browser",
            true,
            false,
            true
        )
        assertEquals(0L, firstValidAfterOtherPackage.elapsedMillis)

        val nextValid = tracker.update(
            36_000L * NANOS_PER_MILLISECOND,
            "browser",
            "browser",
            true,
            false,
            true
        )
        assertEquals(1_000L, nextValid.elapsedMillis)
    }

    @Test
    fun suspendedBaselinePreservesElapsedAndExcludesAmbiguousGapFromThreshold() {
        val tracker = ContinuousViewingTracker(1)
        tracker.update(0L, "browser", "browser", true, false, true)
        val beforePause = tracker.update(
            59_999L * NANOS_PER_MILLISECOND,
            "browser",
            "browser",
            true,
            false,
            true
        )
        tracker.suspendPreviousObservationBaseline()

        val afterSamePackageResume = tracker.update(
            120_000L * NANOS_PER_MILLISECOND,
            "browser",
            "browser",
            true,
            false,
            true
        )
        assertEquals(beforePause.elapsedMillis, afterSamePackageResume.elapsedMillis)
        assertFalse(afterSamePackageResume.interventionActive)

        val nextValid = tracker.update(
            121_000L * NANOS_PER_MILLISECOND,
            "browser",
            "browser",
            true,
            false,
            true
        )
        assertEquals(60_999L, nextValid.elapsedMillis)
        assertTrue(nextValid.interventionActive)
    }

    @Test
    fun backwardsClockInvalidatesBaselineAndInterventionRemainsLatched() {
        val tracker = ContinuousViewingTracker(1)
        tracker.update(0L, "browser", "browser", true, false, true)
        val active = tracker.update(60_000L * NANOS_PER_MILLISECOND, "browser", "browser", true, false, true)
        assertTrue(active.interventionActive)

        val backwards = tracker.update(59_000L * NANOS_PER_MILLISECOND, "browser", "browser", true, false, true)
        assertTrue(backwards.reset)
        assertEquals(0L, backwards.elapsedMillis)
        assertTrue(backwards.interventionActive)

        val firstValidAfterRollback = tracker.update(
            61_000L * NANOS_PER_MILLISECOND,
            "browser",
            "browser",
            true,
            false,
            true
        )
        assertEquals(0L, firstValidAfterRollback.elapsedMillis)
        assertTrue(firstValidAfterRollback.interventionActive)
    }
}

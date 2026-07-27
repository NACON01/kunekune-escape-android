package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageForegroundReducerTest {
    @Test
    fun samePackageDifferentClassTransitionsAreNotInterruptions() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "ActivityA"))
        reducer.apply(resumed("target", "ActivityB"))
        reducer.apply(paused("target", "ActivityA"))
        reducer.apply(stopped("target", "ActivityA"))

        val observation = reducer.consumeObservation()
        assertEquals("target", observation.packageName)
        assertFalse(observation.differentPackageSincePreviousObservation)
    }

    @Test
    fun delayedSameClassActivityEventsDoNotClearTheForeground() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "Activity"))
        reducer.apply(resumed("target", "Activity"))
        reducer.apply(paused("target", "Activity"))
        reducer.apply(stopped("target", "Activity"))

        assertEquals("target", reducer.consumeObservation().packageName)
    }

    @Test
    fun awayAndBackKeepsInterruptionFlagEvenWhenTargetIsCurrent() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.consumeObservation()
        reducer.apply(resumed("other", "OtherActivity"))
        reducer.apply(resumed("target", "TargetActivity"))

        val observation = reducer.consumeObservation()
        assertEquals("target", observation.packageName)
        assertTrue(observation.differentPackageSincePreviousObservation)
    }

    @Test
    fun screenAndKeyguardEventsAreReportedAsInterruptions() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.consumeObservation()
        reducer.apply(paused("target", "TargetActivity"))
        reducer.apply(ForegroundUsageEvent(ForegroundUsageEventType.SCREEN_NON_INTERACTIVE))
        reducer.apply(ForegroundUsageEvent(ForegroundUsageEventType.KEYGUARD_SHOWN))

        val observation = reducer.consumeObservation()
        assertEquals("target", observation.packageName)
        assertTrue(observation.screenNonInteractiveSincePreviousObservation)
        assertFalse(observation.reconciliationPending)
    }

    @Test
    fun pauseAtQueryBoundaryRemainsPendingAcrossConsumeAndNoEvents() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.consumeObservation()
        reducer.apply(paused("target", "TargetActivity"))

        assertTrue(reducer.consumeObservation().reconciliationPending)
        assertTrue(reducer.consumeObservation().reconciliationPending)
    }

    @Test
    fun samePackageResumeResolvesPendingWithoutInterruption() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.apply(paused("target", "TargetActivity"))
        assertTrue(reducer.consumeObservation().reconciliationPending)

        reducer.apply(resumed("target", "TargetActivity"))
        val observation = reducer.consumeObservation()

        assertFalse(observation.reconciliationPending)
        assertFalse(observation.differentPackageSincePreviousObservation)
    }

    @Test
    fun differentPackageResumeResolvesPendingWithInterruption() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.apply(paused("target", "TargetActivity"))
        reducer.consumeObservation()

        reducer.apply(resumed("other", "OtherActivity"))
        val observation = reducer.consumeObservation()

        assertFalse(observation.reconciliationPending)
        assertTrue(observation.differentPackageSincePreviousObservation)
    }

    @Test
    fun consumingObservationClearsFlagsButPreservesForegroundWithoutNewEvents() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.apply(resumed("other", "OtherActivity"))
        assertTrue(reducer.consumeObservation().differentPackageSincePreviousObservation)

        val observationWithNoNewEvents = reducer.consumeObservation()
        assertEquals("other", observationWithNoNewEvents.packageName)
        assertFalse(observationWithNoNewEvents.differentPackageSincePreviousObservation)
        assertFalse(observationWithNoNewEvents.screenNonInteractiveSincePreviousObservation)
    }

    @Test
    fun queryWindowUsesInitialLookbackThenIncrementalExclusiveEnd() {
        val window = UsageEventQueryWindow(300L)

        assertEquals(UsageQueryWindow(700L, 1_000L, false), window.next(1_000L))
        window.commit(1_000L)
        assertEquals(UsageQueryWindow(1_000L, 1_250L, false), window.next(1_250L))
        window.commit(1_250L)
        assertEquals(null, window.next(1_250L))
    }

    @Test
    fun queryWindowResetsRangeAfterClockRollback() {
        val window = UsageEventQueryWindow(300L)
        window.next(2_000L)
        window.commit(2_000L)

        assertEquals(UsageQueryWindow(1_000L, 1_300L, true), window.next(1_300L))
        window.commit(1_300L)
        assertEquals(UsageQueryWindow(1_300L, 1_301L, false), window.next(1_301L))
    }

    private fun resumed(packageName: String, className: String) = ForegroundUsageEvent(
        ForegroundUsageEventType.ACTIVITY_RESUMED,
        ForegroundActivityIdentity(packageName, className)
    )

    private fun paused(packageName: String, className: String) = ForegroundUsageEvent(
        ForegroundUsageEventType.ACTIVITY_PAUSED,
        ForegroundActivityIdentity(packageName, className)
    )

    private fun stopped(packageName: String, className: String) = ForegroundUsageEvent(
        ForegroundUsageEventType.ACTIVITY_STOPPED,
        ForegroundActivityIdentity(packageName, className)
    )
}

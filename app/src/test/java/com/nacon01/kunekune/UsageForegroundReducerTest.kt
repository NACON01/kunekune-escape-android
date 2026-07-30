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
    fun originalDifferentPackageFlagSurvivesSuccessfulTargetResumeReconciliation() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.consumeObservation()
        reducer.apply(resumed("other", "OtherActivity"))
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.apply(paused("target", "TargetActivity"))

        val original = reducer.consumeObservation()
        reducer.reconcileRecentLifecycle(
            listOf(resumed("target", "TargetActivity", 100L))
        )

        val merged = original.mergeReconciledObservation(reducer.consumeObservation())
        assertEquals("target", merged.packageName)
        assertFalse(merged.reconciliationPending)
        assertTrue(merged.differentPackageSincePreviousObservation)
    }

    @Test
    fun originalScreenNonInteractiveFlagSurvivesSuccessfulReconciliation() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.consumeObservation()
        reducer.apply(ForegroundUsageEvent(ForegroundUsageEventType.SCREEN_NON_INTERACTIVE))
        reducer.apply(paused("target", "TargetActivity"))

        val original = reducer.consumeObservation()
        reducer.reconcileRecentLifecycle(
            listOf(resumed("target", "TargetActivity", 100L))
        )

        val merged = original.mergeReconciledObservation(reducer.consumeObservation())
        assertEquals("target", merged.packageName)
        assertFalse(merged.reconciliationPending)
        assertTrue(merged.screenNonInteractiveSincePreviousObservation)
    }

    @Test
    fun reconciliationFlagsAlsoSurviveTheMerge() {
        val original = ForegroundUsageObservation(
            packageName = "target",
            differentPackageSincePreviousObservation = false,
            screenNonInteractiveSincePreviousObservation = false,
            reconciliationPending = true
        )
        val reconciled = ForegroundUsageObservation(
            packageName = "other",
            differentPackageSincePreviousObservation = true,
            screenNonInteractiveSincePreviousObservation = true,
            reconciliationPending = false
        )

        val merged = original.mergeReconciledObservation(reconciled)

        assertEquals("other", merged.packageName)
        assertFalse(merged.reconciliationPending)
        assertTrue(merged.differentPackageSincePreviousObservation)
        assertTrue(merged.screenNonInteractiveSincePreviousObservation)
    }

    @Test
    fun falseFlagsStayFalseAndReconciledStateRemainsAuthoritative() {
        val original = ForegroundUsageObservation(
            packageName = "target",
            differentPackageSincePreviousObservation = false,
            screenNonInteractiveSincePreviousObservation = false,
            reconciliationPending = true
        )
        val reconciled = ForegroundUsageObservation(
            packageName = "other",
            differentPackageSincePreviousObservation = false,
            screenNonInteractiveSincePreviousObservation = false,
            reconciliationPending = false
        )

        val merged = original.mergeReconciledObservation(reconciled)

        assertEquals("other", merged.packageName)
        assertFalse(merged.reconciliationPending)
        assertFalse(merged.differentPackageSincePreviousObservation)
        assertFalse(merged.screenNonInteractiveSincePreviousObservation)
    }

    @Test
    fun recentSameTargetResumeResolvesPendingAfterNewerPause() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity", 100L))
        reducer.apply(paused("target", "TargetActivity", 200L))
        reducer.consumeObservation()

        reducer.reconcileRecentLifecycle(
            listOf(
                resumed("target", "TargetActivity", 150L),
                paused("target", "TargetActivity", 160L),
                resumed("target", "TargetActivity", 250L)
            )
        )

        val observation = reducer.consumeObservation()
        assertEquals("target", observation.packageName)
        assertFalse(observation.reconciliationPending)
        assertFalse(observation.differentPackageSincePreviousObservation)
    }

    @Test
    fun recentNonCurrentPauseAndStopLeaveNewestResumeAuthoritative() {
        val reducer = RecentForegroundLifecycleReducer()
        val activityB = ForegroundActivityIdentity("target", "ActivityB")

        reducer.apply(resumed("target", "ActivityA", 100L))
        reducer.apply(resumed("target", "ActivityB", 200L))
        reducer.apply(paused("target", "ActivityA", 300L))

        assertEquals(ForegroundLifecycleStatus.RESUMED, reducer.snapshot().status)
        assertEquals(activityB, reducer.snapshot().activity)

        reducer.apply(stopped("target", "ActivityA", 400L))

        assertEquals(ForegroundLifecycleStatus.RESUMED, reducer.snapshot().status)
        assertEquals(activityB, reducer.snapshot().activity)
    }

    @Test
    fun recentSamePackageNonCurrentPauseAndStopResolvePendingToNewestPackage() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.apply(paused("target", "TargetActivity"))
        reducer.consumeObservation()

        reducer.reconcileRecentLifecycle(
            listOf(
                resumed("target", "ActivityA", 100L),
                resumed("target", "ActivityB", 200L),
                paused("target", "ActivityA", 300L),
                stopped("target", "ActivityA", 400L)
            )
        )

        val observation = reducer.consumeObservation()
        assertEquals("target", observation.packageName)
        assertFalse(observation.reconciliationPending)
        assertFalse(observation.differentPackageSincePreviousObservation)
    }

    @Test
    fun recentPauseOrStopKeepsPending() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.apply(paused("target", "TargetActivity"))
        reducer.consumeObservation()

        reducer.reconcileRecentLifecycle(
            listOf(
                resumed("target", "TargetActivity", 100L),
                paused("target", "TargetActivity", 200L)
            )
        )
        assertTrue(reducer.consumeObservation().reconciliationPending)

        reducer.reconcileRecentLifecycle(
            listOf(
                resumed("target", "TargetActivity", 100L),
                stopped("target", "TargetActivity", 300L)
            )
        )
        assertTrue(reducer.consumeObservation().reconciliationPending)
    }

    @Test
    fun recentOtherPackageResumeSwitchesAndRaisesInterruptionOnce() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.apply(paused("target", "TargetActivity"))
        reducer.consumeObservation()

        val history = listOf(
            resumed("target", "TargetActivity", 100L),
            paused("target", "TargetActivity", 200L),
            resumed("other", "OtherActivity", 300L)
        )
        reducer.reconcileRecentLifecycle(history)
        val first = reducer.consumeObservation()
        assertEquals("other", first.packageName)
        assertFalse(first.reconciliationPending)
        assertTrue(first.differentPackageSincePreviousObservation)

        reducer.reconcileRecentLifecycle(history)
        val repeated = reducer.consumeObservation()
        assertEquals("other", repeated.packageName)
        assertFalse(repeated.reconciliationPending)
        assertFalse(repeated.differentPackageSincePreviousObservation)
    }

    @Test
    fun recentLateTargetPauseAndStopDoNotReplaceOtherPackageAndInterruptOnce() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.apply(paused("target", "TargetActivity"))
        reducer.consumeObservation()

        val history = listOf(
            resumed("target", "TargetActivity", 100L),
            resumed("other", "OtherActivity", 200L),
            paused("target", "TargetActivity", 300L),
            stopped("target", "TargetActivity", 400L)
        )
        reducer.reconcileRecentLifecycle(history)
        val first = reducer.consumeObservation()
        assertEquals("other", first.packageName)
        assertFalse(first.reconciliationPending)
        assertTrue(first.differentPackageSincePreviousObservation)

        reducer.reconcileRecentLifecycle(history)
        val repeated = reducer.consumeObservation()
        assertEquals("other", repeated.packageName)
        assertFalse(repeated.reconciliationPending)
        assertFalse(repeated.differentPackageSincePreviousObservation)
    }

    @Test
    fun olderPauseCannotBeatNewerResumeWhenRecentEventsAreUnordered() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.apply(paused("target", "TargetActivity"))
        reducer.consumeObservation()

        reducer.reconcileRecentLifecycle(
            listOf(
                paused("target", "TargetActivity", 300L),
                resumed("target", "TargetActivity", 400L)
            )
        )

        assertFalse(reducer.consumeObservation().reconciliationPending)
    }

    @Test
    fun onlyPauseOrNoRecentEventsKeepsPending() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.apply(paused("target", "TargetActivity"))
        reducer.consumeObservation()

        reducer.reconcileRecentLifecycle(
            listOf(paused("target", "TargetActivity", 100L))
        )
        assertTrue(reducer.consumeObservation().reconciliationPending)

        reducer.reconcileRecentLifecycle(emptyList())
        assertTrue(reducer.consumeObservation().reconciliationPending)
    }

    @Test
    fun missingActivityRecentLifecycleKeepsPending() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.apply(paused("target", "TargetActivity"))
        reducer.consumeObservation()

        reducer.reconcileRecentLifecycle(
            listOf(
                resumed("target", "TargetActivity", 100L),
                ForegroundUsageEvent(
                    ForegroundUsageEventType.ACTIVITY_PAUSED,
                    timestampMillis = 200L
                )
            )
        )

        assertTrue(reducer.consumeObservation().reconciliationPending)
    }

    @Test
    fun resetClearsPendingAndReconciliationState() {
        val reducer = UsageForegroundReducer()
        reducer.apply(resumed("target", "TargetActivity"))
        reducer.apply(paused("target", "TargetActivity"))
        reducer.reconcileRecentLifecycle(
            listOf(resumed("other", "OtherActivity", 100L))
        )
        reducer.consumeObservation()

        reducer.reset()

        val observation = reducer.consumeObservation()
        assertEquals(null, observation.packageName)
        assertFalse(observation.reconciliationPending)
        assertFalse(observation.differentPackageSincePreviousObservation)
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

    private fun resumed(packageName: String, className: String, timestampMillis: Long = 0L) = ForegroundUsageEvent(
        ForegroundUsageEventType.ACTIVITY_RESUMED,
        ForegroundActivityIdentity(packageName, className),
        timestampMillis
    )

    private fun paused(packageName: String, className: String, timestampMillis: Long = 0L) = ForegroundUsageEvent(
        ForegroundUsageEventType.ACTIVITY_PAUSED,
        ForegroundActivityIdentity(packageName, className),
        timestampMillis
    )

    private fun stopped(packageName: String, className: String, timestampMillis: Long = 0L) = ForegroundUsageEvent(
        ForegroundUsageEventType.ACTIVITY_STOPPED,
        ForegroundActivityIdentity(packageName, className),
        timestampMillis
    )
}

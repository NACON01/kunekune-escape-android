package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsagePollObservationTest {
    @Test
    fun sameGenerationMergeKeepsLatestSampleAndAccumulatesSafetyFlags() {
        val pending = UsagePollObservation(
            generation = 4L,
            result = ForegroundPackageResult(
                packageName = "other",
                accessGranted = false,
                hasUsableData = false,
                differentPackageSincePreviousObservation = true
            ),
            completedNanos = 10L
        )
        val incoming = UsagePollObservation(
            generation = 4L,
            result = ForegroundPackageResult(
                packageName = "target",
                accessGranted = true,
                hasUsableData = true,
                screenNonInteractiveSincePreviousObservation = true
            ),
            completedNanos = 20L
        )

        val merged = mergeUsagePollObservation(pending, incoming, currentGeneration = 4L)!!

        assertEquals("target", merged.result.packageName)
        assertFalse(merged.result.accessGranted)
        assertFalse(merged.result.hasUsableData)
        assertEquals(20L, merged.completedNanos)
        assertTrue(merged.result.differentPackageSincePreviousObservation)
        assertTrue(merged.result.screenNonInteractiveSincePreviousObservation)
    }

    @Test
    fun lateGenerationObservationIsDiscarded() {
        val pending = UsagePollObservation(
            generation = 5L,
            result = ForegroundPackageResult("target", true, true),
            completedNanos = 30L
        )
        val late = UsagePollObservation(
            generation = 4L,
            result = ForegroundPackageResult("other", true, true),
            completedNanos = 40L
        )

        assertEquals(
            pending,
            mergeUsagePollObservation(pending, late, currentGeneration = 5L)
        )
        assertNull(mergeUsagePollObservation(null, late, currentGeneration = 5L))
    }

    @Test
    fun mergeUsesLatestReconciliationStateInsteadOfKeepingResolvedPending() {
        val pending = UsagePollObservation(
            generation = 6L,
            result = ForegroundPackageResult(
                packageName = "target",
                accessGranted = true,
                hasUsableData = true,
                reconciliationPending = true
            ),
            completedNanos = 50L
        )
        val resolved = UsagePollObservation(
            generation = 6L,
            result = ForegroundPackageResult(
                packageName = "target",
                accessGranted = true,
                hasUsableData = true,
                reconciliationPending = false
            ),
            completedNanos = 60L
        )

        assertFalse(
            mergeUsagePollObservation(pending, resolved, currentGeneration = 6L)!!
                .result.reconciliationPending
        )
    }
}

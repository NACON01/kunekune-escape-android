package com.nacon01.kunekune

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceProgressSafetyTest {
    @Test
    fun longFrameCannotMakeAnArbitraryProjectionJumpPlausible() {
        assertFalse(GuidanceProgressSafety.isPlausibleProjectionDelta(0f, 5f, 60f))
        assertTrue(GuidanceProgressSafety.isPlausibleProjectionDelta(0f, 1.1f, 60f))
    }

    @Test
    fun nonFiniteProjectionIsNeverPlausible() {
        assertFalse(GuidanceProgressSafety.isPlausibleProjectionDelta(0f, Float.NaN, 0.1f))
        assertFalse(GuidanceProgressSafety.isPlausibleProjectionDelta(0f, 1f, Float.POSITIVE_INFINITY))
    }
}

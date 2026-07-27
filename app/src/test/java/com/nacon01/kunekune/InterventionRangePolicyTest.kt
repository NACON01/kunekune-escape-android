package com.nacon01.kunekune

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterventionRangePolicyTest {
    @Test
    fun arrivalDurationsAcceptOneThroughOneHundredTwentyMinutesOnly() {
        assertTrue(InterventionPreferences.isValidArrivalFadeMinutes(1))
        assertTrue(InterventionPreferences.isValidArrivalFadeMinutes(120))
        assertFalse(InterventionPreferences.isValidArrivalFadeMinutes(0))
        assertFalse(InterventionPreferences.isValidArrivalFadeMinutes(121))
        assertTrue(InterventionPreferences.isValidLeaveDestinationFadeMinutes(1))
        assertTrue(InterventionPreferences.isValidLeaveDestinationFadeMinutes(120))
        assertFalse(InterventionPreferences.isValidLeaveDestinationFadeMinutes(0))
        assertFalse(InterventionPreferences.isValidLeaveDestinationFadeMinutes(121))
    }

    @Test
    fun fadeAndViewingThresholdRangesAreInclusive() {
        assertTrue(InterventionPreferences.isValidFadeToBlackSeconds(1))
        assertTrue(InterventionPreferences.isValidFadeToBlackSeconds(60))
        assertFalse(InterventionPreferences.isValidFadeToBlackSeconds(0))
        assertFalse(InterventionPreferences.isValidFadeToBlackSeconds(61))
        assertTrue(InterventionPreferences.isValidViewingThresholdMinutes(1))
        assertTrue(InterventionPreferences.isValidViewingThresholdMinutes(120))
        assertFalse(InterventionPreferences.isValidViewingThresholdMinutes(0))
        assertFalse(InterventionPreferences.isValidViewingThresholdMinutes(121))
    }
}

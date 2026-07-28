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
        assertTrue(InterventionPreferences.isValidViewingThresholdSeconds(10))
        assertTrue(InterventionPreferences.isValidViewingThresholdSeconds(60))
        assertTrue(InterventionPreferences.isValidViewingThresholdSeconds(120))
        assertTrue(InterventionPreferences.isValidViewingThresholdSeconds(7_200))
        assertFalse(InterventionPreferences.isValidViewingThresholdSeconds(0))
        assertFalse(InterventionPreferences.isValidViewingThresholdSeconds(70))
        assertFalse(InterventionPreferences.isValidViewingThresholdSeconds(90))
        assertFalse(InterventionPreferences.isValidViewingThresholdSeconds(7_201))
    }

    @Test
    fun viewingThresholdMovesAcrossSecondAndMinuteSteps() {
        assertTrue(InterventionPreferences.nextViewingThresholdSeconds(50, 1) == 60)
        assertTrue(InterventionPreferences.nextViewingThresholdSeconds(60, 1) == 120)
        assertTrue(InterventionPreferences.nextViewingThresholdSeconds(120, -1) == 60)
        assertTrue(InterventionPreferences.nextViewingThresholdSeconds(60, -1) == 50)
        assertTrue(InterventionPreferences.nextViewingThresholdSeconds(10, -1) == 10)
        assertTrue(InterventionPreferences.nextViewingThresholdSeconds(7_200, 1) == 7_200)
    }

    @Test
    fun progressRewardAcceptsHalfThroughThreeHundredCentimeters() {
        assertTrue(InterventionPreferences.isValidProgressRewardCentimeters(0.5f))
        assertTrue(InterventionPreferences.isValidProgressRewardCentimeters(8f))
        assertTrue(InterventionPreferences.isValidProgressRewardCentimeters(300f))
        assertFalse(InterventionPreferences.isValidProgressRewardCentimeters(0.49f))
        assertFalse(InterventionPreferences.isValidProgressRewardCentimeters(300.01f))
        assertFalse(InterventionPreferences.isValidProgressRewardCentimeters(Float.NaN))
    }
}

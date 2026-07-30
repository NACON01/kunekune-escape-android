package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeZoneLocationTest {
    private val config = HomeZoneConfig(0.0, 0.0, 100.0)

    @Test
    fun accuracyCircleMustBeInsideOrOutsideBoundary() {
        val inside = HomeZoneLocationClassifier.classify(
            config,
            HomeZoneLocationSample(0.00045, 0.0, 10f)
        )
        val boundary = HomeZoneLocationClassifier.classify(
            config,
            HomeZoneLocationSample(0.0009, 0.0, 10f)
        )
        val outside = HomeZoneLocationClassifier.classify(
            config,
            HomeZoneLocationSample(0.0012, 0.0, 10f)
        )
        assertEquals(LocationObservation.INSIDE, inside)
        assertEquals(LocationObservation.UNKNOWN, boundary)
        assertEquals(LocationObservation.OUTSIDE, outside)
    }

    @Test
    fun invalidAccuracyIsUnknown() {
        assertEquals(
            LocationObservation.UNKNOWN,
            HomeZoneLocationClassifier.classify(config, HomeZoneLocationSample(0.0, 0.0, -1f))
        )
    }
}


package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GuidanceColorPolicyTest {
    @Test
    fun activeAndTrackingLostHudElementsUseTheirSharedMarkerColor() {
        val activeMarker = GuidanceColorPolicy.markerColor(trackingLost = false)
        val activeText = GuidanceColorPolicy.markerColor(trackingLost = false)
        val lostMarker = GuidanceColorPolicy.markerColor(trackingLost = true)
        val lostText = GuidanceColorPolicy.markerColor(trackingLost = true)

        assertEquals(activeMarker, activeText)
        assertEquals(lostMarker, lostText)
        assertNotEquals(activeMarker, lostMarker)
    }
}

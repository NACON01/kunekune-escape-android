package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayOpacityPolicyTest {
    @Test
    fun reachesFullyOpaqueAtMaximumDensity() {
        val opacity = OverlayOpacityPolicy.forDesiredDensity(1f)
        assertEquals(1f, opacity.windowAlpha, 0.0001f)
        assertEquals(1f, opacity.scrimAlpha, 0.0001f)
        assertEquals(true, opacity.fullyOpaque)
    }

    @Test
    fun keepsWindowTouchSafeDuringEarlyFade() {
        val opacity = OverlayOpacityPolicy.forDesiredDensity(0.35f)
        assertEquals(0.70f, opacity.windowAlpha, 0.0001f)
        assertEquals(0.50f, opacity.scrimAlpha, 0.0001f)
        assertEquals(false, opacity.fullyOpaque)
    }

    @Test
    fun preservesPlatformOpacityBoundaries() {
        val atTouchSafeFadeLimit = OverlayOpacityPolicy.forDesiredDensity(0.70f)
        assertEquals(0.70f, atTouchSafeFadeLimit.windowAlpha, 0.0001f)
        assertEquals(1f, atTouchSafeFadeLimit.scrimAlpha, 0.0001f)

        val atPlatformTouchLimit = OverlayOpacityPolicy.forDesiredDensity(0.80f)
        assertEquals(0.80f, atPlatformTouchLimit.windowAlpha, 0.0001f)
        assertEquals(1f, atPlatformTouchLimit.scrimAlpha, 0.0001f)

        val abovePlatformTouchLimit = OverlayOpacityPolicy.forDesiredDensity(0.81f)
        assertEquals(0.81f, abovePlatformTouchLimit.windowAlpha, 0.0001f)
        assertEquals(1f, abovePlatformTouchLimit.scrimAlpha, 0.0001f)
        assertEquals(false, abovePlatformTouchLimit.fullyOpaque)
    }

    @Test
    fun snapsTerminalDensityToOpaqueAndLeavesItWhenRecoveryStarts() {
        val terminal = OverlayOpacityPolicy.forDesiredDensity(
            OverlayOpacityPolicy.FULLY_OPAQUE_DENSITY_THRESHOLD
        )
        assertEquals(1f, terminal.windowAlpha, 0.0001f)
        assertEquals(1f, terminal.scrimAlpha, 0.0001f)
        assertEquals(true, terminal.fullyOpaque)

        val recovering = OverlayOpacityPolicy.forDesiredDensity(0.998f)
        assertEquals(0.998f, recovering.windowAlpha, 0.0001f)
        assertEquals(1f, recovering.scrimAlpha, 0.0001f)
        assertEquals(false, recovering.fullyOpaque)
    }
}

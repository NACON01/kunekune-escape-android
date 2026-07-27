package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingRecoveryPolicyTest {
    @Test
    fun initialLossTimesOutButEstablishedPausedLossDoesNot() {
        assertEquals(
            TrackingRecoveryDecision.TERMINAL,
            TrackingRecoveryPolicy.decide(
                localizationEstablished = false,
                camera = TrackingAvailability.PAUSED,
                anchor = null,
                markerTracking = false,
                elapsedSinceStartNanos = TrackingRecoveryPolicy.INITIAL_LOCALIZATION_TIMEOUT_NANOS
            )
        )
        assertEquals(
            TrackingRecoveryDecision.RECOVERABLE_LOSS,
            TrackingRecoveryPolicy.decide(
                localizationEstablished = true,
                camera = TrackingAvailability.PAUSED,
                anchor = TrackingAvailability.PAUSED,
                markerTracking = false,
                elapsedSinceStartNanos = Long.MAX_VALUE
            )
        )
    }

    @Test
    fun stoppedCameraOrAnchorIsTerminal() {
        assertEquals(
            TrackingRecoveryDecision.TERMINAL,
            TrackingRecoveryPolicy.decide(
                localizationEstablished = true,
                camera = TrackingAvailability.STOPPED,
                anchor = TrackingAvailability.TRACKING,
                markerTracking = true,
                elapsedSinceStartNanos = 0L
            )
        )
        assertEquals(
            TrackingRecoveryDecision.TERMINAL,
            TrackingRecoveryPolicy.decide(
                localizationEstablished = true,
                camera = TrackingAvailability.TRACKING,
                anchor = TrackingAvailability.STOPPED,
                markerTracking = false,
                elapsedSinceStartNanos = 0L
            )
        )
    }
}

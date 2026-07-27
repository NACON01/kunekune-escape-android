package com.nacon01.kunekune

/** Android-free decision table for preserving an established ARCore localization. */
enum class TrackingAvailability {
    TRACKING,
    PAUSED,
    STOPPED
}

enum class TrackingRecoveryDecision {
    SEARCHING,
    TRACKING,
    RECOVERABLE_LOSS,
    TERMINAL
}

object TrackingRecoveryPolicy {
    fun decide(
        localizationEstablished: Boolean,
        camera: TrackingAvailability,
        anchor: TrackingAvailability?,
        markerTracking: Boolean,
        elapsedSinceStartNanos: Long
    ): TrackingRecoveryDecision {
        if (camera == TrackingAvailability.STOPPED || anchor == TrackingAvailability.STOPPED) {
            return TrackingRecoveryDecision.TERMINAL
        }
        val localized = camera == TrackingAvailability.TRACKING &&
            anchor == TrackingAvailability.TRACKING && markerTracking
        if (localized) return TrackingRecoveryDecision.TRACKING
        if (localizationEstablished) return TrackingRecoveryDecision.RECOVERABLE_LOSS
        return if (elapsedSinceStartNanos >= INITIAL_LOCALIZATION_TIMEOUT_NANOS) {
            TrackingRecoveryDecision.TERMINAL
        } else {
            TrackingRecoveryDecision.SEARCHING
        }
    }

    const val INITIAL_LOCALIZATION_TIMEOUT_NANOS = 8_000_000_000L
}

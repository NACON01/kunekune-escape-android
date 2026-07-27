package com.nacon01.kunekune

data class ContinuousViewingState(
    val elapsedMillis: Long,
    val thresholdMillis: Long,
    val interventionArmed: Boolean,
    val currentlyVisible: Boolean,
    val reset: Boolean
) {
    /** Compatibility name for callers that only need to know whether the latch is armed. */
    val interventionActive: Boolean
        get() = interventionArmed
}

data class ViewingGateState(
    val armed: Boolean,
    val currentlyVisible: Boolean
)

/** Counts only uninterrupted, interactive foreground use of one exact package. */
class ContinuousViewingTracker(thresholdMinutes: Int) {
    private val thresholdMillis = thresholdMinutes.coerceIn(1, 120) * 60_000L
    private var elapsedMillis = 0L
    private var previousObservationNanos: Long? = null
    private var interventionActive = false

    fun update(
        nowNanos: Long,
        foregroundPackage: String?,
        expectedPackage: String?,
        screenInteractive: Boolean,
        keyguardLocked: Boolean,
        usageDataAvailable: Boolean,
        differentPackageSincePreviousObservation: Boolean = false,
        screenNonInteractiveSincePreviousObservation: Boolean = false
    ): ContinuousViewingState {
        val validViewing = usageDataAvailable && screenInteractive && !keyguardLocked &&
            !differentPackageSincePreviousObservation &&
            !screenNonInteractiveSincePreviousObservation &&
            !expectedPackage.isNullOrBlank() && foregroundPackage == expectedPackage
        val previous = previousObservationNanos
        val clockWentBackwards = previous != null && nowNanos < previous
        val validObservation = validViewing && !clockWentBackwards
        val deltaMillis = if (validObservation && previous != null) {
            (nowNanos - previous) / NANOS_PER_MILLISECOND
        } else 0L
        val wasReset = !validObservation
        if (wasReset) {
            elapsedMillis = 0L
            previousObservationNanos = null
        } else {
            elapsedMillis += deltaMillis
            previousObservationNanos = nowNanos
        }
        if (!interventionActive && elapsedMillis >= thresholdMillis) interventionActive = true
        return ContinuousViewingState(
            elapsedMillis = elapsedMillis,
            thresholdMillis = thresholdMillis,
            interventionArmed = interventionActive,
            currentlyVisible = interventionActive && validViewing,
            reset = wasReset
        )
    }

    /** Suspends the ambiguous interval without resetting elapsed viewing or intervention state. */
    fun suspendPreviousObservationBaseline() {
        previousObservationNanos = null
    }

    fun reset() {
        elapsedMillis = 0L
        previousObservationNanos = null
        interventionActive = false
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

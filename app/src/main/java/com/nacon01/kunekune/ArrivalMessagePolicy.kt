package com.nacon01.kunekune

/** Pure timing policy for the post-arrival message. */
data class ArrivalMessagePolicy(
    val delayMillis: Long = DEFAULT_DELAY_MILLIS
) {
    init {
        require(delayMillis >= 0L) { "Arrival message delay must not be negative" }
    }

    fun deadline(arrivedAtMonotonicMillis: Long): Long =
        arrivedAtMonotonicMillis.coerceAtMost(Long.MAX_VALUE - delayMillis) + delayMillis

    fun shouldShow(
        arrivedAtMonotonicMillis: Long?,
        nowMonotonicMillis: Long
    ): Boolean = arrivedAtMonotonicMillis != null &&
        nowMonotonicMillis < deadline(arrivedAtMonotonicMillis)

    fun shouldShow(
        state: GuidanceState,
        arrivedAtMonotonicMillis: Long?,
        nowMonotonicMillis: Long
    ): Boolean = state == GuidanceState.ARRIVED &&
        shouldShow(arrivedAtMonotonicMillis, nowMonotonicMillis)

    companion object {
        const val DEFAULT_DELAY_MILLIS = 2_000L
        const val ARRIVAL_MESSAGE_DELAY_MILLIS = DEFAULT_DELAY_MILLIS
    }
}

/**
 * State machine for the arrival message. The injected clock must be monotonic;
 * no wall-clock time is used by this class.
 */
class ArrivalMessageController(
    private val policy: ArrivalMessagePolicy = ArrivalMessagePolicy(),
    private val monotonicClockMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private var arrivedAtMonotonicMillis: Long? = null

    /** Applies a guidance state and returns whether the message is visible now. */
    fun update(state: GuidanceState): Boolean {
        if (state != GuidanceState.ARRIVED) {
            reset()
            return false
        }
        val now = monotonicClockMillis()
        if (arrivedAtMonotonicMillis == null) {
            arrivedAtMonotonicMillis = now
        }
        return policy.shouldShow(arrivedAtMonotonicMillis, now)
    }

    fun onArrived(): Boolean = update(GuidanceState.ARRIVED)

    fun deadlineMonotonicMillis(): Long? = arrivedAtMonotonicMillis?.let(policy::deadline)

    /** Starts a new guidance session and is safe to call repeatedly. */
    fun newSession() = reset()

    /** Clears both the deadline and the displayed state, idempotently. */
    fun reset() {
        arrivedAtMonotonicMillis = null
    }

    fun onNonArrived() = reset()
}

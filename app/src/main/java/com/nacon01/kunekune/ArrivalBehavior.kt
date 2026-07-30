package com.nacon01.kunekune

enum class ArrivalBehavior(val displayName: String) {
    RELEASE("到着時に解除"),
    FADE_OUT("到着後に暗転")
}

enum class ArrivalPhase {
    NOT_ARRIVED,
    AT_DESTINATION,
    ARRIVAL_FADE,
    AWAY_FROM_DESTINATION_FADE,
    FULL_CONCEALMENT
}

/** A RELEASE fade considers finite horizontal endpoint distances below one meter to be at the destination. */
internal fun isAtDestinationForDeparture(endpointDistanceMeters: Float): Boolean =
    endpointDistanceMeters.isFinite() && endpointDistanceMeters < 1.0f

data class ArrivalControllerState(
    val phase: ArrivalPhase,
    val density: Float,
    val elapsedMillis: Long,
    val timedFadePaused: Boolean
)

/** Pure post-arrival policy. Its clock advances only while the target is foreground. */
class ArrivalController(
    private val behavior: ArrivalBehavior,
    arrivalFadeMinutes: Int,
    leaveDestinationFadeMinutes: Int
) {
    private val arrivalFadeMillis = minutesToMillis(arrivalFadeMinutes)
    private val leaveDestinationFadeMillis = minutesToMillis(leaveDestinationFadeMinutes)
    private var phase = ArrivalPhase.NOT_ARRIVED
    private var density = 0f
    private var elapsedMillis = 0L
    private var initialDensity = 0f

    fun isArrived(): Boolean = phase != ArrivalPhase.NOT_ARRIVED

    fun onArrival(initialDensity: Float): ArrivalControllerState {
        if (!isArrived()) {
            this.initialDensity = initialDensity.coerceIn(0f, 1f)
            elapsedMillis = 0L
            phase = when (behavior) {
                ArrivalBehavior.FADE_OUT -> ArrivalPhase.ARRIVAL_FADE
                ArrivalBehavior.RELEASE -> ArrivalPhase.AT_DESTINATION
            }
            density = when (behavior) {
                ArrivalBehavior.FADE_OUT -> this.initialDensity
                ArrivalBehavior.RELEASE -> 0f
            }
        }
        return state(targetForeground = false)
    }

    /**
     * [atDestination] is only needed for RELEASE and must be null while the pose is not
     * trusted. That preserves the last known destination state during tracking loss.
     */
    fun update(
        elapsedSeconds: Float,
        targetForeground: Boolean,
        atDestination: Boolean? = null
    ): ArrivalControllerState {
        require(elapsedSeconds.isFinite())
        if (!isArrived()) return state(targetForeground)

        if (behavior == ArrivalBehavior.RELEASE) {
            when {
                atDestination == true -> {
                    phase = ArrivalPhase.AT_DESTINATION
                    elapsedMillis = 0L
                    density = 0f
                }
                atDestination == false && phase == ArrivalPhase.AT_DESTINATION -> {
                    phase = ArrivalPhase.AWAY_FROM_DESTINATION_FADE
                    elapsedMillis = 0L
                    density = 0f
                }
            }
        }

        val timedPhase = phase == ArrivalPhase.ARRIVAL_FADE ||
            phase == ArrivalPhase.AWAY_FROM_DESTINATION_FADE
        if (targetForeground && timedPhase) {
            elapsedMillis = (elapsedMillis + (elapsedSeconds.coerceAtLeast(0f) * 1_000L).toLong())
                .coerceAtMost(durationMillis())
            val progress = elapsedMillis.toFloat() / durationMillis().toFloat()
            density = when (phase) {
                ArrivalPhase.ARRIVAL_FADE -> initialDensity + (1f - initialDensity) * progress
                ArrivalPhase.AWAY_FROM_DESTINATION_FADE -> progress
                else -> density
            }.coerceIn(0f, 1f)
            if (elapsedMillis >= durationMillis()) phase = ArrivalPhase.FULL_CONCEALMENT
        }
        if (phase == ArrivalPhase.AT_DESTINATION) density = 0f
        if (phase == ArrivalPhase.FULL_CONCEALMENT) density = 1f
        return state(targetForeground)
    }

    fun currentState(): ArrivalControllerState = state(targetForeground = false)

    private fun durationMillis(): Long = when (behavior) {
        ArrivalBehavior.FADE_OUT -> arrivalFadeMillis
        ArrivalBehavior.RELEASE -> leaveDestinationFadeMillis
    }

    private fun state(targetForeground: Boolean) = ArrivalControllerState(
        phase = phase,
        density = density,
        elapsedMillis = elapsedMillis,
        timedFadePaused = targetForeground.not() &&
            (phase == ArrivalPhase.ARRIVAL_FADE || phase == ArrivalPhase.AWAY_FROM_DESTINATION_FADE)
    )

    companion object {
        const val MINUTES_MIN = 1
        const val MINUTES_MAX = 120

        private fun minutesToMillis(minutes: Int): Long {
            require(minutes in MINUTES_MIN..MINUTES_MAX)
            return minutes * 60_000L
        }
    }
}

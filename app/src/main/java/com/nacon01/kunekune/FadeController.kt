package com.nacon01.kunekune

import kotlin.math.abs

/**
 * Rewards sustained net route progress rather than individual frame deltas.
 * The filtered, half-second windows tolerate millimeter-scale VIO jitter while
 * keeping stationary oscillation and backward motion from earning recovery.
 */
class FadeController(
    private val startupGraceSeconds: Float = 3f,
    private val stagnationGraceSeconds: Float = 2f,
    private val fadeRatePerSecond: Float = 1f / 30f,
    private val recoveryRatePerSecond: Float = 0.45f,
    private val progressRewardMeters: Float = 0.08f,
    private val progressFilterTimeConstantSeconds: Float = 0.25f,
    private val maximumForwardSpeedMetersPerSecond: Float = 3f
) {
    private var previousArcDistanceMeters: Float? = null
    private var filteredArcDistanceMeters: Float? = null
    private var progressWindowElapsedSeconds = 0f
    private var progressWindowNetMeters = 0f
    private var pendingForwardMeters = 0f
    private var activeSeconds = 0f
    private var secondsSinceProgress = 0f
    private var recoveryWindowSeconds = 0f
    private var density = 0f

    init {
        require(startupGraceSeconds >= 0f)
        require(stagnationGraceSeconds >= 0f)
        require(fadeRatePerSecond > 0f)
        require(recoveryRatePerSecond > 0f)
        require(progressRewardMeters > 0f)
        require(progressFilterTimeConstantSeconds > 0f)
        require(maximumForwardSpeedMetersPerSecond > 0f)
    }

    fun update(
        isGuiding: Boolean,
        localizationValid: Boolean,
        progressValid: Boolean,
        arcDistanceMeters: Float,
        dtSeconds: Float
    ): Float {
        require(arcDistanceMeters.isFinite())
        require(dtSeconds.isFinite())
        val elapsed = dtSeconds.coerceIn(0f, MAX_STEP_SECONDS)

        if (!isGuiding) {
            reset()
            return density
        }
        if (!localizationValid) {
            clearProgressMeasurement()
            return density
        }

        activeSeconds += elapsed
        secondsSinceProgress += elapsed
        val previousRaw = previousArcDistanceMeters

        if (progressValid) {
            if (previousRaw == null) {
                previousArcDistanceMeters = arcDistanceMeters
                filteredArcDistanceMeters = arcDistanceMeters
            } else {
                val rawDelta = arcDistanceMeters - previousRaw
                val maximumPlausibleStep = MAX_STEP_ALLOWANCE_METERS +
                    maximumForwardSpeedMetersPerSecond * elapsed
                when {
                    abs(rawDelta) > maximumPlausibleStep -> {
                        clearProgressMeasurement()
                    }
                    rawDelta <= -SUBSTANTIAL_BACKWARD_STEP_METERS -> {
                        previousArcDistanceMeters = arcDistanceMeters
                        filteredArcDistanceMeters = arcDistanceMeters
                        progressWindowElapsedSeconds = 0f
                        progressWindowNetMeters = 0f
                        pendingForwardMeters = 0f
                        recoveryWindowSeconds = 0f
                    }
                    else -> {
                        previousArcDistanceMeters = arcDistanceMeters
                        val previousFiltered = filteredArcDistanceMeters ?: arcDistanceMeters
                        val alpha = (elapsed / (
                            progressFilterTimeConstantSeconds + elapsed
                        )).coerceIn(0f, 1f)
                        val filtered = previousFiltered +
                            (arcDistanceMeters - previousFiltered) * alpha
                        filteredArcDistanceMeters = filtered
                        progressWindowNetMeters += filtered - previousFiltered
                        progressWindowElapsedSeconds += elapsed

                        if (progressWindowElapsedSeconds >= PROGRESS_WINDOW_SECONDS) {
                            when {
                                progressWindowNetMeters >= minOf(
                                    MINIMUM_PROGRESS_WINDOW_METERS,
                                    progressRewardMeters
                                ) -> {
                                    pendingForwardMeters += progressWindowNetMeters
                                }
                                progressWindowNetMeters <= -SUBSTANTIAL_BACKWARD_WINDOW_METERS -> {
                                    pendingForwardMeters = 0f
                                    recoveryWindowSeconds = 0f
                                }
                                else -> {
                                    // Small negative windows are jitter, but still
                                    // reduce stale uncommitted progress naturally.
                                    pendingForwardMeters = (
                                        pendingForwardMeters + progressWindowNetMeters
                                    ).coerceAtLeast(0f)
                                }
                            }
                            progressWindowElapsedSeconds = 0f
                            progressWindowNetMeters = 0f
                        }
                    }
                }
            }
        } else {
            clearProgressMeasurement()
        }

        var rewarded = false
        if (pendingForwardMeters >= progressRewardMeters) {
            pendingForwardMeters -= progressRewardMeters
            secondsSinceProgress = 0f
            recoveryWindowSeconds = RECOVERY_WINDOW_SECONDS
            rewarded = true
        }

        if (recoveryWindowSeconds > 0f || rewarded) {
            density = (density - recoveryRatePerSecond * elapsed).coerceAtLeast(0f)
            recoveryWindowSeconds = (recoveryWindowSeconds - elapsed).coerceAtLeast(0f)
        } else if (activeSeconds >= startupGraceSeconds && secondsSinceProgress >= stagnationGraceSeconds) {
            density = (density + fadeRatePerSecond * elapsed).coerceAtMost(1f)
        }
        return density
    }

    fun update(isGuiding: Boolean, arcDistanceMeters: Float, dtSeconds: Float): Float = update(
        isGuiding = isGuiding,
        localizationValid = isGuiding,
        progressValid = isGuiding,
        arcDistanceMeters = arcDistanceMeters,
        dtSeconds = dtSeconds
    )

    fun currentDensity(): Float = density

    fun reset() {
        clearProgressMeasurement()
        activeSeconds = 0f
        secondsSinceProgress = 0f
        recoveryWindowSeconds = 0f
        density = 0f
    }

    private fun clearProgressMeasurement() {
        previousArcDistanceMeters = null
        filteredArcDistanceMeters = null
        progressWindowElapsedSeconds = 0f
        progressWindowNetMeters = 0f
        pendingForwardMeters = 0f
    }

    companion object {
        fun forFadeDurationSeconds(
            seconds: Int,
            progressRewardMeters: Float = 0.08f
        ): FadeController {
            require(seconds > 0)
            return FadeController(
                fadeRatePerSecond = 1f / seconds.toFloat(),
                progressRewardMeters = progressRewardMeters
            )
        }

        private const val MAX_STEP_SECONDS = 0.25f
        private const val MAX_STEP_ALLOWANCE_METERS = 0.05f
        private const val PROGRESS_WINDOW_SECONDS = 0.5f
        private const val MINIMUM_PROGRESS_WINDOW_METERS = 0.02f
        private const val SUBSTANTIAL_BACKWARD_STEP_METERS = 0.02f
        private const val SUBSTANTIAL_BACKWARD_WINDOW_METERS = 0.04f
        private const val RECOVERY_WINDOW_SECONDS = 0.75f
    }
}

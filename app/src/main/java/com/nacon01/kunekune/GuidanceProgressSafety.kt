package com.nacon01.kunekune

import kotlin.math.abs

/** Bounds projection plausibility independently of arbitrarily long frame gaps. */
object GuidanceProgressSafety {
    const val PROJECTION_JUMP_ALLOWANCE_METERS = 0.35f
    const val MAX_WALKING_SPEED_METERS_PER_SECOND = 3f
    const val MAX_PROJECTION_DT_SECONDS = 0.25f

    fun isPlausibleProjectionDelta(
        previousMeters: Float,
        currentMeters: Float,
        dtSeconds: Float
    ): Boolean {
        if (!previousMeters.isFinite() || !currentMeters.isFinite() || !dtSeconds.isFinite()) {
            return false
        }
        val boundedDt = dtSeconds.coerceIn(0f, MAX_PROJECTION_DT_SECONDS)
        return abs(currentMeters - previousMeters) <=
            PROJECTION_JUMP_ALLOWANCE_METERS + MAX_WALKING_SPEED_METERS_PER_SECOND * boundedDt
    }
}

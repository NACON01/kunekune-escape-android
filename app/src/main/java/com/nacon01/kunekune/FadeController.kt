package com.nacon01.kunekune

/** 停滞時間を画面の暗さへ変換するARCore非依存ロジック。 */
class FadeController(
    private val forwardThresholdMeters: Float = DEFAULT_FORWARD_THRESHOLD_METERS,
    private val blackoutDurationSeconds: Float = DEFAULT_BLACKOUT_DURATION_SECONDS
) {
    private var previousArcDistanceMeters: Float? = null
    private var density = 0f

    init {
        require(forwardThresholdMeters >= 0f)
        require(blackoutDurationSeconds > 0f)
    }

    fun update(
        isGuiding: Boolean,
        arcDistanceMeters: Float,
        dtSeconds: Float
    ): Float {
        require(arcDistanceMeters.isFinite())
        require(dtSeconds.isFinite())
        val elapsedSeconds = dtSeconds.coerceAtLeast(0f)

        if (!isGuiding) {
            density = 0f
            previousArcDistanceMeters = arcDistanceMeters
            return density
        }

        val previous = previousArcDistanceMeters
        val movedForward = previous != null &&
            arcDistanceMeters - previous > forwardThresholdMeters
        previousArcDistanceMeters = arcDistanceMeters

        if (movedForward) {
            density = 0f
        } else {
            density = (density + elapsedSeconds / blackoutDurationSeconds).coerceAtMost(1f)
        }
        return density
    }

    fun reset() {
        previousArcDistanceMeters = null
        density = 0f
    }

    companion object {
        const val DEFAULT_FORWARD_THRESHOLD_METERS = 0.03f
        const val DEFAULT_BLACKOUT_DURATION_SECONDS = 30f
    }
}

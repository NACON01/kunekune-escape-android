package com.nacon01.kunekune

data class OverlayOpacity(
    val windowAlpha: Float,
    val scrimAlpha: Float
)

object OverlayOpacityPolicy {
    const val TOUCH_SAFE_WINDOW_ALPHA = 0.70f

    fun forDesiredDensity(requestedDensity: Float): OverlayOpacity {
        val density = requestedDensity.coerceIn(0f, 1f)
        return if (density <= TOUCH_SAFE_WINDOW_ALPHA) {
            OverlayOpacity(
                windowAlpha = TOUCH_SAFE_WINDOW_ALPHA,
                scrimAlpha = density / TOUCH_SAFE_WINDOW_ALPHA
            )
        } else {
            OverlayOpacity(windowAlpha = density, scrimAlpha = 1f)
        }
    }
}

package com.nacon01.kunekune

data class OverlayOpacity(
    val windowAlpha: Float,
    val scrimAlpha: Float,
    val fullyOpaque: Boolean
)

object OverlayOpacityPolicy {
    const val TOUCH_SAFE_WINDOW_ALPHA = 0.70f
    const val FULLY_OPAQUE_DENSITY_THRESHOLD = 0.999f

    fun forDesiredDensity(requestedDensity: Float): OverlayOpacity {
        val density = requestedDensity.coerceIn(0f, 1f)
        if (density >= FULLY_OPAQUE_DENSITY_THRESHOLD) {
            return OverlayOpacity(
                windowAlpha = 1f,
                scrimAlpha = 1f,
                fullyOpaque = true
            )
        }
        return if (density <= TOUCH_SAFE_WINDOW_ALPHA) {
            OverlayOpacity(
                windowAlpha = TOUCH_SAFE_WINDOW_ALPHA,
                scrimAlpha = density / TOUCH_SAFE_WINDOW_ALPHA,
                fullyOpaque = false
            )
        } else {
            OverlayOpacity(
                windowAlpha = density,
                scrimAlpha = 1f,
                fullyOpaque = false
            )
        }
    }
}

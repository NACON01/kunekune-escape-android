package com.nacon01.kunekune

object GuidanceColorPolicy {
    fun markerColor(trackingLost: Boolean): Int =
        if (trackingLost) TRACKING_LOST_COLOR else ACTIVE_COLOR

    val ACTIVE_COLOR: Int = argb(alpha = 165, red = 40, green = 220, blue = 255)
    val TRACKING_LOST_COLOR: Int = argb(alpha = 165, red = 170, green = 170, blue = 170)

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

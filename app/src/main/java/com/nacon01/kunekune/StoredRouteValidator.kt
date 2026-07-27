package com.nacon01.kunekune

import kotlin.math.hypot

/** Validates persisted marker-frame routes without assuming marker axes are horizontal. */
object StoredRouteValidator {
    const val MINIMUM_ROUTE_LENGTH_METERS = 0.20f

    fun isValid(route: List<GuidanceVector3>): Boolean {
        if (route.size < 2 || route.any { point ->
                !point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()
            }) {
            return false
        }

        var totalLength = 0f
        for (index in 0 until route.lastIndex) {
            val start = route[index]
            val end = route[index + 1]
            val segmentLength = hypot(
                hypot(end.x - start.x, end.y - start.y),
                end.z - start.z
            )
            if (!segmentLength.isFinite()) return false
            totalLength += segmentLength
            if (!totalLength.isFinite()) return false
        }
        return totalLength >= MINIMUM_ROUTE_LENGTH_METERS
    }
}

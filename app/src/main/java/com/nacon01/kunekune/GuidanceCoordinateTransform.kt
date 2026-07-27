package com.nacon01.kunekune

/**
 * Converts a route persisted in marker coordinates into the coordinate system
 * used by [GuidanceEngine]. The supplied transform must map marker-frame
 * points into the current gravity-aligned VIO world frame.
 */
object GuidanceCoordinateTransform {
    fun routeToWorld(
        markerRoute: List<GuidanceVector3>,
        transformPoint: (GuidanceVector3) -> GuidanceVector3
    ): List<GuidanceVector3> = markerRoute.map(transformPoint)
}

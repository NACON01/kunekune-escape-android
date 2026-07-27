package com.nacon01.kunekune

import kotlin.math.atan2
import kotlin.math.hypot

/** ARCore に依存しない、水平面上の経路追従計算。 */
data class GuidanceVector3(val x: Float, val y: Float, val z: Float)

data class GuidanceResult(
    val projectedPoint: GuidanceVector3,
    val targetPoint: GuidanceVector3,
    val projectedDistanceMeters: Float,
    val remainingDistanceMeters: Float,
    val progressPercent: Float,
    val signedAngleDegrees: Float,
    val crossTrackDistanceMeters: Float,
    val endpointDistanceMeters: Float,
    val arrived: Boolean
)

private data class RouteProjection(
    val point: GuidanceVector3,
    val distanceMeters: Float,
    val crossTrackDistanceMeters: Float
)

class GuidanceEngine(
    private val lookaheadMeters: Float = 1.0f,
    private val arrivalThresholdMeters: Float = DEFAULT_ARRIVAL_THRESHOLD_METERS,
    private val arrivalCorridorMeters: Float = DEFAULT_ARRIVAL_CORRIDOR_METERS,
    private val minimumRouteLengthMeters: Float = MINIMUM_USEFUL_ROUTE_METERS
) {
    private var cachedRoute: List<GuidanceVector3>? = null
    private var cachedGeometry: RouteGeometry? = null

    init {
        require(lookaheadMeters >= 0f)
        require(arrivalThresholdMeters >= 0f)
        require(arrivalCorridorMeters >= 0f)
        require(minimumRouteLengthMeters > 0f)
    }

    fun isValidRoute(route: List<GuidanceVector3>): Boolean {
        if (route.size < 2) return false
        return geometryFor(route).totalDistanceMeters >= minimumRouteLengthMeters
    }

    fun calculate(
        route: List<GuidanceVector3>,
        currentPosition: GuidanceVector3,
        currentForward: GuidanceVector3
    ): GuidanceResult {
        require(route.size >= 2) { "route must contain at least two points" }
        val geometry = geometryFor(route)
        require(geometry.totalDistanceMeters >= minimumRouteLengthMeters) {
            "経路には異なる2点以上と${minimumRouteLengthMeters}m以上の水平長が必要です"
        }

        val projection = geometry.project(currentPosition)
        val targetDistance = (projection.distanceMeters + lookaheadMeters)
            .coerceAtMost(geometry.totalDistanceMeters)
        val targetPoint = geometry.pointAtDistance(targetDistance)
        val remainingDistance = (geometry.totalDistanceMeters - projection.distanceMeters)
            .coerceAtLeast(0f)
        val endpointDistance = currentPosition.horizontalDistanceTo(route.last())

        return GuidanceResult(
            projectedPoint = projection.point,
            targetPoint = targetPoint,
            projectedDistanceMeters = projection.distanceMeters,
            remainingDistanceMeters = remainingDistance,
            progressPercent = projection.distanceMeters / geometry.totalDistanceMeters * 100f,
            signedAngleDegrees = signedHorizontalAngle(currentForward, targetPoint - currentPosition),
            crossTrackDistanceMeters = projection.crossTrackDistanceMeters,
            endpointDistanceMeters = endpointDistance,
            arrived = endpointDistance <= arrivalThresholdMeters &&
                projection.crossTrackDistanceMeters <= arrivalCorridorMeters
        )
    }

    private fun signedHorizontalAngle(
        currentForward: GuidanceVector3,
        directionToTarget: GuidanceVector3
    ): Float {
        val forward = currentForward.horizontalNormalizedOrNull()
        val target = directionToTarget.horizontalNormalizedOrNull()
        if (forward == null || target == null) return 0f
        val crossY = forward.z * target.x - forward.x * target.z
        val dot = (forward.x * target.x + forward.z * target.z).coerceIn(-1f, 1f)
        return Math.toDegrees(atan2(crossY.toDouble(), dot.toDouble())).toFloat()
    }

    private inner class RouteGeometry(private val points: List<GuidanceVector3>) {
        private val segmentLengths = FloatArray(points.size - 1)
        private val cumulativeLengths = FloatArray(points.size)
        val totalDistanceMeters: Float

        init {
            for (index in segmentLengths.indices) {
                segmentLengths[index] = points[index].horizontalDistanceTo(points[index + 1])
                cumulativeLengths[index + 1] = cumulativeLengths[index] + segmentLengths[index]
            }
            totalDistanceMeters = cumulativeLengths.last()
        }

        fun project(position: GuidanceVector3): RouteProjection {
            var bestPoint = points.first()
            var bestDistanceSquared = Float.POSITIVE_INFINITY
            var bestArcDistance = 0f
            for (index in segmentLengths.indices) {
                val start = points[index]
                val end = points[index + 1]
                val dx = end.x - start.x
                val dz = end.z - start.z
                val lengthSquared = dx * dx + dz * dz
                val t = if (lengthSquared <= EPSILON) 0f else {
                    (((position.x - start.x) * dx + (position.z - start.z) * dz) / lengthSquared)
                        .coerceIn(0f, 1f)
                }
                val candidate = interpolate(start, end, t)
                val distanceSquared = square(position.x - candidate.x) + square(position.z - candidate.z)
                if (distanceSquared < bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared
                    bestPoint = candidate
                    bestArcDistance = cumulativeLengths[index] + segmentLengths[index] * t
                }
            }
            return RouteProjection(bestPoint, bestArcDistance, kotlin.math.sqrt(bestDistanceSquared))
        }

        fun pointAtDistance(distanceMeters: Float): GuidanceVector3 {
            val distance = distanceMeters.coerceIn(0f, totalDistanceMeters)
            for (index in segmentLengths.indices) {
                if (distance <= cumulativeLengths[index + 1] || index == segmentLengths.lastIndex) {
                    val length = segmentLengths[index]
                    if (length <= EPSILON) continue
                    val t = ((distance - cumulativeLengths[index]) / length).coerceIn(0f, 1f)
                    return interpolate(points[index], points[index + 1], t)
                }
            }
            return points.last()
        }
    }

    private fun geometryFor(route: List<GuidanceVector3>): RouteGeometry {
        if (cachedRoute === route) return cachedGeometry!!
        return RouteGeometry(route).also {
            cachedRoute = route
            cachedGeometry = it
        }
    }

    private fun interpolate(a: GuidanceVector3, b: GuidanceVector3, t: Float) = GuidanceVector3(
        a.x + (b.x - a.x) * t,
        a.y + (b.y - a.y) * t,
        a.z + (b.z - a.z) * t
    )

    private operator fun GuidanceVector3.minus(other: GuidanceVector3) = GuidanceVector3(
        x - other.x, y - other.y, z - other.z
    )

    private fun GuidanceVector3.horizontalNormalizedOrNull(): GuidanceVector3? {
        val length = hypot(x, z)
        return if (length <= EPSILON) null else GuidanceVector3(x / length, 0f, z / length)
    }

    private fun GuidanceVector3.horizontalDistanceTo(other: GuidanceVector3) =
        hypot(x - other.x, z - other.z)

    private fun square(value: Float) = value * value

    companion object {
        const val MINIMUM_USEFUL_ROUTE_METERS = 0.20f
        const val DEFAULT_PROGRESS_CORRIDOR_METERS = 0.90f
        const val DEFAULT_ARRIVAL_THRESHOLD_METERS = 0.60f
        const val DEFAULT_ARRIVAL_CORRIDOR_METERS = 0.75f
        /** Exit thresholds are wider than arrival thresholds to prevent edge jitter. */
        const val ARRIVAL_EXIT_THRESHOLD_METERS = 0.95f
        const val ARRIVAL_EXIT_CORRIDOR_METERS = 1.00f
        private const val EPSILON = 0.000001f
    }
}

/** 到着候補が連続した場合だけ一度だけ成立する、純粋な到着ラッチ。 */
class GuidanceArrivalLatch(private val dwellSeconds: Float = 1.0f) {
    private var candidateSeconds = 0f
    var arrived: Boolean = false
        private set

    init { require(dwellSeconds >= 0f) }

    fun update(isArrivalCandidate: Boolean, dtSeconds: Float): Boolean {
        require(dtSeconds.isFinite())
        if (arrived) return true
        candidateSeconds = if (isArrivalCandidate) {
            candidateSeconds + dtSeconds.coerceIn(0f, MAX_STEP_SECONDS)
        } else {
            0f
        }
        arrived = candidateSeconds >= dwellSeconds
        return arrived
    }

    fun reset() {
        candidateSeconds = 0f
        arrived = false
    }

    companion object {
        private const val MAX_STEP_SECONDS = 0.25f
    }
}

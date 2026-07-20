package com.nacon01.kunekune

import kotlin.math.atan2
import kotlin.math.sqrt

/** VIOワールド座標系で保存経路を追従する純粋ロジック。 */
data class GuidanceVector3(val x: Float, val y: Float, val z: Float)

data class GuidanceResult(
    val projectedPoint: GuidanceVector3,
    val targetPoint: GuidanceVector3,
    val projectedDistanceMeters: Float,
    val remainingDistanceMeters: Float,
    val progressPercent: Float,
    val signedAngleDegrees: Float,
    val arrived: Boolean
)

private data class RouteProjection(
    val point: GuidanceVector3,
    val distanceMeters: Float
)

class GuidanceEngine(
    private val lookaheadMeters: Float = 1.0f,
    private val arrivalThresholdMeters: Float = 0.6f
) {
    init {
        require(lookaheadMeters >= 0f)
        require(arrivalThresholdMeters >= 0f)
    }

    fun calculate(
        route: List<GuidanceVector3>,
        currentPosition: GuidanceVector3,
        currentForward: GuidanceVector3
    ): GuidanceResult {
        require(route.isNotEmpty()) { "経路には1点以上必要です" }

        val geometry = RouteGeometry(route)
        val projection = geometry.project(currentPosition)
        val targetDistance = (projection.distanceMeters + lookaheadMeters)
            .coerceAtMost(geometry.totalDistanceMeters)
        val targetPoint = geometry.pointAtDistance(targetDistance)
        val direction = targetPoint - currentPosition
        val signedAngleDegrees = signedHorizontalAngle(currentForward, direction)
        val remainingDistance = (geometry.totalDistanceMeters - projection.distanceMeters)
            .coerceAtLeast(0f)

        return GuidanceResult(
            projectedPoint = projection.point,
            targetPoint = targetPoint,
            projectedDistanceMeters = projection.distanceMeters,
            remainingDistanceMeters = remainingDistance,
            progressPercent = if (geometry.totalDistanceMeters > EPSILON) {
                projection.distanceMeters / geometry.totalDistanceMeters * 100f
            } else {
                100f
            },
            signedAngleDegrees = signedAngleDegrees,
            arrived = remainingDistance <= arrivalThresholdMeters
        )
    }

    private fun signedHorizontalAngle(
        currentForward: GuidanceVector3,
        directionToTarget: GuidanceVector3
    ): Float {
        val forward = GuidanceVector3(currentForward.x, 0f, currentForward.z).normalizedOrNull()
        val target = GuidanceVector3(directionToTarget.x, 0f, directionToTarget.z).normalizedOrNull()
        if (forward == null || target == null) return 0f

        // +Y回りの右手系。カメラの-Zから+Xへ向く場合は負の角度。
        val crossY = forward.z * target.x - forward.x * target.z
        val dot = (forward.x * target.x + forward.z * target.z).coerceIn(-1f, 1f)
        return Math.toDegrees(atan2(crossY.toDouble(), dot.toDouble())).toFloat()
    }

    private inner class RouteGeometry(private val points: List<GuidanceVector3>) {
        private val segmentLengths: FloatArray
        private val cumulativeLengths: FloatArray
        val totalDistanceMeters: Float

        init {
            segmentLengths = FloatArray((points.size - 1).coerceAtLeast(0))
            cumulativeLengths = FloatArray(points.size)
            for (index in segmentLengths.indices) {
                segmentLengths[index] = points[index].distanceTo(points[index + 1])
                cumulativeLengths[index + 1] = cumulativeLengths[index] + segmentLengths[index]
            }
            totalDistanceMeters = cumulativeLengths.lastOrNull() ?: 0f
        }

        fun project(position: GuidanceVector3): RouteProjection {
            if (points.size == 1) return RouteProjection(points[0], 0f)

            var bestPoint = points[0]
            var bestDistanceSquared = Float.POSITIVE_INFINITY
            var bestArcDistance = 0f
            for (index in segmentLengths.indices) {
                val start = points[index]
                val end = points[index + 1]
                val segment = end - start
                val lengthSquared = segment.lengthSquared()
                val t = if (lengthSquared <= EPSILON * EPSILON) {
                    0f
                } else {
                    ((position - start).dot(segment) / lengthSquared).coerceIn(0f, 1f)
                }
                val candidate = start + segment * t
                val distanceSquared = (position - candidate).lengthSquared()
                if (distanceSquared < bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared
                    bestPoint = candidate
                    bestArcDistance = cumulativeLengths[index] + segmentLengths[index] * t
                }
            }
            return RouteProjection(bestPoint, bestArcDistance)
        }

        fun pointAtDistance(distanceMeters: Float): GuidanceVector3 {
            if (points.size == 1 || totalDistanceMeters <= EPSILON) return points.last()
            val distance = distanceMeters.coerceIn(0f, totalDistanceMeters)
            for (index in segmentLengths.indices) {
                val segmentEnd = cumulativeLengths[index + 1]
                if (distance <= segmentEnd || index == segmentLengths.lastIndex) {
                    val length = segmentLengths[index]
                    if (length <= EPSILON) return points[index + 1]
                    val t = ((distance - cumulativeLengths[index]) / length).coerceIn(0f, 1f)
                    return points[index] + (points[index + 1] - points[index]) * t
                }
            }
            return points.last()
        }
    }

    private operator fun GuidanceVector3.plus(other: GuidanceVector3) = GuidanceVector3(
        x + other.x, y + other.y, z + other.z
    )

    private operator fun GuidanceVector3.minus(other: GuidanceVector3) = GuidanceVector3(
        x - other.x, y - other.y, z - other.z
    )

    private operator fun GuidanceVector3.times(value: Float) = GuidanceVector3(
        x * value, y * value, z * value
    )

    private fun GuidanceVector3.dot(other: GuidanceVector3) = x * other.x + y * other.y + z * other.z

    private fun GuidanceVector3.lengthSquared() = dot(this)

    private fun GuidanceVector3.distanceTo(other: GuidanceVector3) = (this - other).length()

    private fun GuidanceVector3.length() = sqrt(lengthSquared())

    private fun GuidanceVector3.normalizedOrNull(): GuidanceVector3? {
        val length = sqrt(x * x + z * z)
        return if (length <= EPSILON) null else GuidanceVector3(x / length, 0f, z / length)
    }

    companion object {
        private const val EPSILON = 0.000001f
    }
}





package com.nacon01.kunekune

import kotlin.math.hypot

data class RoutePosition(val x: Float, val y: Float, val z: Float)

data class RoutePoint(val x: Float, val y: Float, val z: Float, val elapsedMillis: Long)

data class RecordedRoute(
    val recordedAtEpochMillis: Long,
    val points: List<RoutePoint>,
    val totalDistanceMeters: Float
)

data class RouteSummary(val pointCount: Int, val totalDistanceMeters: Float)

data class RecordingSnapshot(
    val isRecording: Boolean,
    val pointCount: Int,
    val totalDistanceMeters: Float,
    val savedRoute: RouteSummary?
)

/** 距離だけを基準に経路点を追加する、ARCore非依存のサンプラー。 */
class RoutePointSampler(private val minimumDistanceMeters: Float = 0.3f) {
    private val points = mutableListOf<RoutePoint>()
    private var totalDistanceMeters = 0f

    init {
        require(minimumDistanceMeters > 0f)
    }

    fun reset() {
        points.clear()
        totalDistanceMeters = 0f
    }

    fun tryAdd(position: RoutePosition, elapsedMillis: Long): Boolean {
        val previous = points.lastOrNull()
        if (previous != null) {
            val distance = distance(previous.position(), position)
            if (distance < minimumDistanceMeters) return false
            totalDistanceMeters += distance
        }
        points += RoutePoint(position.x, position.y, position.z, elapsedMillis)
        return true
    }

    fun points(): List<RoutePoint> = points.toList()

    fun totalDistanceMeters(): Float = totalDistanceMeters

    private fun RoutePoint.position() = RoutePosition(x, y, z)

    private fun distance(a: RoutePosition, b: RoutePosition): Float = hypot(
        hypot(a.x - b.x, a.y - b.y),
        a.z - b.z
    )
}

class RouteRecorder(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sampler: RoutePointSampler = RoutePointSampler()
) {
    private var startedAtMillis = 0L

    var isRecording: Boolean = false
        private set

    fun start(startedAtMillis: Long = clockMillis()) {
        sampler.reset()
        this.startedAtMillis = startedAtMillis
        isRecording = true
    }

    /** PAUSED中はcameraTracking=falseとして呼び出し、点を追加しない。 */
    fun sample(position: RoutePosition?, cameraTracking: Boolean, nowMillis: Long = clockMillis()): Boolean {
        if (!isRecording || !cameraTracking || position == null) return false
        val elapsedMillis = (nowMillis - startedAtMillis).coerceAtLeast(0L)
        return sampler.tryAdd(position, elapsedMillis)
    }

    fun stop(recordedAtEpochMillis: Long = System.currentTimeMillis()): RecordedRoute? {
        if (!isRecording) return null
        isRecording = false
        return RecordedRoute(
            recordedAtEpochMillis = recordedAtEpochMillis,
            points = sampler.points(),
            totalDistanceMeters = sampler.totalDistanceMeters()
        )
    }

    fun snapshot(savedRoute: RouteSummary? = null): RecordingSnapshot = RecordingSnapshot(
        isRecording = isRecording,
        pointCount = sampler.points().size,
        totalDistanceMeters = sampler.totalDistanceMeters(),
        savedRoute = savedRoute
    )
}

fun RecordedRoute.summary() = RouteSummary(points.size, totalDistanceMeters)

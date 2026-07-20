package com.nacon01.kunekune

import android.content.Context
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import kotlin.math.hypot

enum class GuidanceState {
    INACTIVE,
    GUIDING,
    ARRIVED
}

data class GuidanceSnapshot(
    val state: GuidanceState,
    val angleDifferenceDegrees: Float?,
    val remainingDistanceMeters: Float?,
    val progressPercent: Float?,
    val trackingLost: Boolean
)

data class TrackingSnapshot(
    val state: TrackingState,
    val failureReason: TrackingFailureReason,
    val position: FloatArray?,
    val cumulativeDistance: Float,
    val straightDistance: Float,
    val framesPerSecond: Float,
    val marker: MarkerTrackingSnapshot,
    val recording: RecordingSnapshot,
    val guidance: GuidanceSnapshot
)

class ArTrackingManager(context: Context) {
    private val appContext = context.applicationContext
    private val markerAnchor = MarkerAnchor(appContext)
    private val routeRecorder = RouteRecorder()
    private val routeStore = RouteStore(appContext)
    private var savedRoute: RecordedRoute? = loadSavedRoute()
    private var savedRouteSummary = savedRoute?.summary()
    private var session: Session? = null
    private var cameraTextureName: Int? = null
    private var displayRotation = 0
    private var displayWidth = 0
    private var displayHeight = 0
    private var origin: FloatArray? = null
    private var previousPosition: FloatArray? = null
    private var cumulativeDistance = 0f
    private var frameCount = 0
    private var fpsWindowStartNanos = System.nanoTime()
    private var framesPerSecond = 0f
    private val guidanceEngine = GuidanceEngine()
    private var guidanceState = GuidanceState.INACTIVE
    private var lastGuidanceResult: GuidanceResult? = null

    @Volatile
    private var latestMarkerState = MarkerDetectionState.NOT_DETECTED

    var onSnapshot: ((TrackingSnapshot) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val hasSession: Boolean
        get() = session != null

    val isRecording: Boolean
        get() = routeRecorder.isRecording

    fun startRecording(): Boolean {
        if (routeRecorder.isRecording || latestMarkerState != MarkerDetectionState.TRACKING) return false
        routeRecorder.start()
        return true
    }

    fun stopRecording(): Boolean {
        val route = routeRecorder.stop() ?: return false
        return try {
            routeStore.save(route)
            savedRoute = route
            savedRouteSummary = route.summary()
            true
        } catch (_: Exception) {
            reportError("経路を保存できませんでした。")
            false
        }
    }

    fun startGuidance(): Boolean {
        if (guidanceState == GuidanceState.GUIDING ||
            savedRoute?.points.isNullOrEmpty() ||
            latestMarkerState != MarkerDetectionState.TRACKING
        ) {
            return false
        }
        guidanceState = GuidanceState.GUIDING
        lastGuidanceResult = null
        return true
    }

    fun stopGuidance(): Boolean {
        if (guidanceState == GuidanceState.INACTIVE) return false
        guidanceState = GuidanceState.INACTIVE
        lastGuidanceResult = null
        return true
    }

    fun createSession(): String? {
        if (session != null) return null

        return try {
            session = Session(appContext).also { arSession ->
                cameraTextureName?.let(arSession::setCameraTextureName)
                if (displayWidth > 0 && displayHeight > 0) {
                    arSession.setDisplayGeometry(displayRotation, displayWidth, displayHeight)
                }
                val config = Config(arSession).apply {
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                }
                markerAnchor.configure(config, arSession)
                arSession.configure(config)
            }
            resetTrackingStats()
            null
        } catch (_: UnavailableArcoreNotInstalledException) {
            reportError("Google Play 開発者サービス（AR向け）がインストールされていません。")
        } catch (_: UnavailableDeviceNotCompatibleException) {
            reportError("この端末はARCoreに対応していません。")
        } catch (_: UnavailableApkTooOldException) {
            reportError("Google Play 開発者サービス（AR向け）を更新してください。")
        } catch (_: UnavailableSdkTooOldException) {
            reportError("ARCore SDKが古すぎます。")
        } catch (_: ImageInsufficientQualityException) {
            reportError("マーカー画像の品質が不足しています")
        } catch (_: Exception) {
            reportError("ARCoreセッションを作成できませんでした。")
        }
    }

    fun setCameraTextureName(textureName: Int) {
        cameraTextureName = textureName
        session?.setCameraTextureName(textureName)
    }

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        displayRotation = rotation
        displayWidth = width
        displayHeight = height
        session?.setDisplayGeometry(rotation, width, height)
    }

    fun resume() {
        try {
            session?.resume()
        } catch (_: Exception) {
            reportError("カメラを開始できませんでした。カメラが他のアプリで使用されていないか確認してください。")
        }
    }

    fun pause() {
        session?.pause()
        publishStopped()
    }

    fun close() {
        session?.close()
        session = null
        markerAnchor.close()
        latestMarkerState = MarkerDetectionState.NOT_DETECTED
        guidanceState = GuidanceState.INACTIVE
        lastGuidanceResult = null
        publishStopped()
    }

    fun updateFrame(): Frame? {
        val currentSession = session ?: return null
        val frame = try {
            currentSession.update()
        } catch (_: Exception) {
            reportError("ARCoreのフレームを取得できませんでした。")
            return null
        }

        val camera = frame.camera
        val marker = markerAnchor.update(frame)
        latestMarkerState = marker.state
        updateFps()
        val position = if (camera.trackingState == TrackingState.TRACKING) {
            camera.pose.translation.copyOf().also(::updateDistance)
        } else {
            null
        }
        val markerPosition = marker.cameraPoseInMarkerSpace?.translation?.let {
            RoutePosition(it[0], it[1], it[2])
        }
        routeRecorder.sample(
            position = markerPosition,
            cameraTracking = camera.trackingState == TrackingState.TRACKING
        )
        val straightDistance = if (position != null && origin != null) {
            origin!!.distanceTo(position)
        } else {
            0f
        }
        val guidance = updateGuidance(camera.pose, camera.trackingState, marker.markerPoseInWorld, position)

        onSnapshot?.invoke(
            TrackingSnapshot(
                state = camera.trackingState,
                failureReason = camera.trackingFailureReason,
                position = position,
                cumulativeDistance = cumulativeDistance,
                straightDistance = straightDistance,
                framesPerSecond = framesPerSecond,
                marker = marker,
                recording = routeRecorder.snapshot(savedRouteSummary),
                guidance = guidance
            )
        )
        return frame
    }

    private fun updateGuidance(
        cameraPose: Pose,
        cameraTrackingState: TrackingState,
        markerPoseInWorld: Pose?,
        currentPosition: FloatArray?
    ): GuidanceSnapshot {
        if (guidanceState == GuidanceState.INACTIVE) {
            return GuidanceSnapshot(GuidanceState.INACTIVE, null, null, null, false)
        }

        val trackingLost = cameraTrackingState != TrackingState.TRACKING
        if (guidanceState == GuidanceState.GUIDING &&
            !trackingLost &&
            markerPoseInWorld != null &&
            currentPosition != null
        ) {
            val route = savedRoute?.points?.map { point ->
                val world = markerPoseInWorld.transformPoint(floatArrayOf(point.x, point.y, point.z))
                GuidanceVector3(world[0], world[1], world[2])
            }
            if (!route.isNullOrEmpty()) {
                val forwardPoint = cameraPose.transformPoint(floatArrayOf(0f, 0f, -1f))
                val forward = GuidanceVector3(
                    forwardPoint[0] - currentPosition[0],
                    forwardPoint[1] - currentPosition[1],
                    forwardPoint[2] - currentPosition[2]
                )
                val result = guidanceEngine.calculate(
                    route = route,
                    currentPosition = GuidanceVector3(
                        currentPosition[0], currentPosition[1], currentPosition[2]
                    ),
                    currentForward = forward
                )
                lastGuidanceResult = result
                if (result.arrived) guidanceState = GuidanceState.ARRIVED
            }
        }

        return lastGuidanceResult.toSnapshot(guidanceState, trackingLost)
    }

    private fun GuidanceResult?.toSnapshot(
        state: GuidanceState,
        trackingLost: Boolean
    ) = GuidanceSnapshot(
        state = state,
        angleDifferenceDegrees = this?.signedAngleDegrees,
        remainingDistanceMeters = this?.remainingDistanceMeters,
        progressPercent = this?.progressPercent,
        trackingLost = trackingLost
    )

    private fun updateDistance(position: FloatArray) {
        if (origin == null) origin = position.copyOf()
        previousPosition?.let { previous -> cumulativeDistance += previous.distanceTo(position) }
        previousPosition = position.copyOf()
    }

    private fun updateFps() {
        frameCount++
        val elapsedNanos = System.nanoTime() - fpsWindowStartNanos
        if (elapsedNanos >= 1_000_000_000L) {
            framesPerSecond = frameCount * 1_000_000_000f / elapsedNanos
            frameCount = 0
            fpsWindowStartNanos = System.nanoTime()
        }
    }

    private fun resetTrackingStats() {
        origin = null
        previousPosition = null
        cumulativeDistance = 0f
        frameCount = 0
        framesPerSecond = 0f
        fpsWindowStartNanos = System.nanoTime()
    }

    private fun publishStopped() {
        onSnapshot?.invoke(
            TrackingSnapshot(
                state = TrackingState.STOPPED,
                failureReason = TrackingFailureReason.NONE,
                position = null,
                cumulativeDistance = cumulativeDistance,
                straightDistance = 0f,
                framesPerSecond = 0f,
                marker = markerAnchor.stoppedSnapshot(),
                recording = routeRecorder.snapshot(savedRouteSummary),
                guidance = lastGuidanceResult.toSnapshot(
                    guidanceState,
                    trackingLost = true
                )
            )
        )
    }

    private fun loadSavedRoute(): RecordedRoute? = try {
        routeStore.load()
    } catch (_: Exception) {
        null
    }

    private fun reportError(message: String): String {
        onError?.invoke(message)
        return message
    }

    private fun FloatArray.distanceTo(other: FloatArray): Float = hypot(
        hypot(this[0] - other[0], this[1] - other[1]),
        this[2] - other[2]
    )
}

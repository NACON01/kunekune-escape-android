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
    private val routeRepository = RouteRepository(appContext)
    private var selectedRouteIdValue: String? = null
    private var savedRoute: RecordedRoute? = loadInitialRoute()
    private var savedRouteSummary = savedRoute?.summary()
    private var savedGuidanceRoute = savedRoute?.points?.map { GuidanceVector3(it.x, it.y, it.z) }
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
    private val arrivalLatch = GuidanceArrivalLatch()
    private var cachedMarkerPoseKey: FloatArray? = null
    private var cachedMarkerRoute: List<GuidanceVector3>? = null
    private var cachedWorldRoute: List<GuidanceVector3>? = null
    private var guidanceState = GuidanceState.INACTIVE
    private var lastGuidanceResult: GuidanceResult? = null
    private var previousFrameNanos = System.nanoTime()

    @Volatile
    private var latestMarkerState = MarkerDetectionState.NOT_DETECTED

    var onSnapshot: ((TrackingSnapshot) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val hasSession: Boolean
        get() = session != null

    val isRecording: Boolean
        get() = routeRecorder.isRecording

    val selectedRouteId: String?
        get() = selectedRouteIdValue

    val selectedRouteIdentifier: String?
        get() = selectedRouteIdValue

    fun availableRoutes(): List<DestinationRoute> = try {
        routeRepository.list()
    } catch (_: Exception) {
        emptyList()
    }

    fun selectRoute(routeId: String): Boolean {
        val route = try {
            routeRepository.get(routeId)
        } catch (_: Exception) {
            null
        } ?: return false
        selectedRouteIdValue = route.id
        applySavedRoute(route.route)
        return true
    }

    fun selectRouteById(routeId: String): Boolean = selectRoute(routeId)

    fun hasValidSelectedRoute(): Boolean =
        savedGuidanceRoute?.let(StoredRouteValidator::isValid) == true

    fun clearSelectedRoute() {
        selectedRouteIdValue = null
        savedRoute = null
        savedRouteSummary = null
        savedGuidanceRoute = null
        invalidateWorldRouteCache()
    }

    fun startRecording(): Boolean {
        if (routeRecorder.isRecording || latestMarkerState != MarkerDetectionState.TRACKING) return false
        routeRecorder.start()
        return true
    }

    fun stopRecording(): Boolean {
        val route = routeRecorder.stop() ?: return false
        applySavedRoute(route)
        return true
    }

    fun finishRecordingIntoCatalog(name: String): DestinationRoute? {
        val route = routeRecorder.stop() ?: return null
        return try {
            routeRepository.create(name, route).also { destination ->
                selectedRouteIdValue = destination.id
                applySavedRoute(destination.route)
            }
        } catch (_: Exception) {
            reportError("経路を保存できませんでした。")
            null
        }
    }

    fun startGuidance(): Boolean {
        val route = savedGuidanceRoute
        if (guidanceState == GuidanceState.GUIDING ||
            route == null ||
            !StoredRouteValidator.isValid(route) ||
            latestMarkerState != MarkerDetectionState.TRACKING
        ) {
            return false
        }
        guidanceState = GuidanceState.GUIDING
        lastGuidanceResult = null
        arrivalLatch.reset()
        return true
    }

    fun stopGuidance(): Boolean {
        if (guidanceState == GuidanceState.INACTIVE) return false
        guidanceState = GuidanceState.INACTIVE
        lastGuidanceResult = null
        arrivalLatch.reset()
        return true
    }

    fun createSession(): String? {
        if (session != null) return null

        return try {
            val arSession = Session(appContext)
            session = arSession
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
            resetTrackingStats()
            previousFrameNanos = System.nanoTime()
            arrivalLatch.reset()
            null
        } catch (_: UnavailableArcoreNotInstalledException) {
            discardFailedSession()
            reportError("Google Play 開発者サービス（AR向け）がインストールされていません。")
        } catch (_: UnavailableDeviceNotCompatibleException) {
            discardFailedSession()
            reportError("この端末はARCoreに対応していません。")
        } catch (_: UnavailableApkTooOldException) {
            discardFailedSession()
            reportError("Google Play 開発者サービス（AR向け）を更新してください。")
        } catch (_: UnavailableSdkTooOldException) {
            discardFailedSession()
            reportError("ARCore SDKが古すぎます。")
        } catch (_: ImageInsufficientQualityException) {
            discardFailedSession()
            reportError("マーカー画像の品質が不足しています")
        } catch (_: Exception) {
            discardFailedSession()
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
        try {
            session?.pause()
        } catch (_: Exception) {
            // Pausing is idempotent for lifecycle purposes; still publish STOPPED.
        } finally {
            publishStopped()
        }
    }

    fun close() {
        try {
            session?.close()
        } finally {
            session = null
            try {
                markerAnchor.close()
            } catch (_: Exception) {
                // Preserve the failed-session state even if anchor detachment races.
            }
            latestMarkerState = MarkerDetectionState.NOT_DETECTED
            guidanceState = GuidanceState.INACTIVE
            lastGuidanceResult = null
            invalidateWorldRouteCache()
            arrivalLatch.reset()
            publishStopped()
        }
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
        val nowNanos = System.nanoTime()
        val dtSeconds = ((nowNanos - previousFrameNanos).coerceAtLeast(0L)) / 1_000_000_000f
        previousFrameNanos = nowNanos
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
        val guidance = updateGuidance(
            camera.pose,
            camera.trackingState,
            marker.markerPoseInWorld,
            position,
            dtSeconds
        )

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
        currentPosition: FloatArray?,
        dtSeconds: Float
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
            val route = savedGuidanceRoute
            if (!route.isNullOrEmpty()) {
                val worldRoute = worldRouteFor(markerPoseInWorld, route)
                if (!guidanceEngine.isValidRoute(worldRoute)) {
                    stopGuidance()
                    reportError("保存した経路を水平面に変換できないため、誘導を停止しました。")
                    return lastGuidanceResult.toSnapshot(guidanceState, trackingLost)
                }
                val forwardPoint = cameraPose.transformPoint(floatArrayOf(0f, 0f, -1f))
                val forward = GuidanceVector3(
                    forwardPoint[0] - currentPosition[0],
                    forwardPoint[1] - currentPosition[1],
                    forwardPoint[2] - currentPosition[2]
                )
                val result = guidanceEngine.calculate(
                    route = worldRoute,
                    currentPosition = GuidanceVector3(
                        currentPosition[0], currentPosition[1], currentPosition[2]
                    ),
                    currentForward = forward
                )
                lastGuidanceResult = result
                if (arrivalLatch.update(result.arrived, dtSeconds)) {
                    guidanceState = GuidanceState.ARRIVED
                }
            }
        } else if (guidanceState == GuidanceState.GUIDING) {
            arrivalLatch.update(false, dtSeconds)
        }

        return lastGuidanceResult.toSnapshot(guidanceState, trackingLost)
    }

    private fun worldRouteFor(
        markerPoseInWorld: Pose,
        markerRoute: List<GuidanceVector3>
    ): List<GuidanceVector3> {
        val poseKey = markerPoseKey(markerPoseInWorld)
        if (cachedMarkerRoute === markerRoute && cachedMarkerPoseKey.contentEqualsOrNull(poseKey)) {
            return cachedWorldRoute!!
        }
        return GuidanceCoordinateTransform.routeToWorld(markerRoute) { point ->
            val world = markerPoseInWorld.transformPoint(floatArrayOf(point.x, point.y, point.z))
            GuidanceVector3(world[0], world[1], world[2])
        }.also {
            cachedMarkerRoute = markerRoute
            cachedMarkerPoseKey = poseKey
            cachedWorldRoute = it
        }
    }

    private fun invalidateWorldRouteCache() {
        cachedMarkerPoseKey = null
        cachedMarkerRoute = null
        cachedWorldRoute = null
    }

    private fun markerPoseKey(pose: Pose): FloatArray =
        pose.translation + pose.rotationQuaternion

    private fun FloatArray?.contentEqualsOrNull(other: FloatArray): Boolean =
        this != null && contentEquals(other)

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

    private fun loadInitialRoute(): RecordedRoute? = try {
        val routes = routeRepository.list()
        routes.firstOrNull()?.also { selectedRouteIdValue = it.id }?.route
            ?: routeStore.load()
    } catch (_: Exception) {
        try { routeStore.load() } catch (_: Exception) { null }
    }

    private fun applySavedRoute(route: RecordedRoute) {
        savedRoute = route
        savedRouteSummary = route.summary()
        savedGuidanceRoute = route.points.map { GuidanceVector3(it.x, it.y, it.z) }
        invalidateWorldRouteCache()
    }

    private fun discardFailedSession() {
        try {
            session?.close()
        } catch (_: Exception) {
            // Preserve the original session-creation error for the user.
        } finally {
            session = null
            try {
                markerAnchor.close()
            } catch (_: Exception) {
                // Preserve the original session-creation error for the user.
            }
        }
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

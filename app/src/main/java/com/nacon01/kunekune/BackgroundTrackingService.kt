package com.nacon01.kunekune

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot

enum class BackgroundTrackingState {
    IDLE,
    PREPARING,
    SEARCHING_MARKER,
    WAITING_FOR_VIEWING,
    GUIDING,
    TRACKING_LOST,
    ARRIVED,
    FAILED,
    STOPPING
}

fun interface BackgroundTrackingStateListener {
    fun onStateChanged(state: BackgroundTrackingState)
}

internal data class UsagePollObservation(
    val generation: Long,
    val result: ForegroundPackageResult,
    val completedNanos: Long
)

internal fun mergeUsagePollObservation(
    pending: UsagePollObservation?,
    incoming: UsagePollObservation,
    currentGeneration: Long
): UsagePollObservation? {
    if (incoming.generation != currentGeneration) return pending
    val previous = pending?.takeIf { it.generation == incoming.generation }
        ?: return incoming
    return UsagePollObservation(
        generation = incoming.generation,
        result = ForegroundPackageResult(
            packageName = incoming.result.packageName,
            accessGranted = previous.result.accessGranted && incoming.result.accessGranted,
            hasUsableData = previous.result.hasUsableData && incoming.result.hasUsableData,
            differentPackageSincePreviousObservation =
                previous.result.differentPackageSincePreviousObservation ||
                    incoming.result.differentPackageSincePreviousObservation,
            screenNonInteractiveSincePreviousObservation =
                previous.result.screenNonInteractiveSincePreviousObservation ||
                    incoming.result.screenNonInteractiveSincePreviousObservation,
            reconciliationPending = incoming.result.reconciliationPending
        ),
        completedNanos = incoming.completedNanos
    )
}

class BackgroundTrackingService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): BackgroundTrackingService = this@BackgroundTrackingService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<BackgroundTrackingStateListener>()
    private val cancellation = AtomicBoolean(false)
    private val finishRequested = AtomicBoolean(false)
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var trackingOverlay: TrackingOverlay? = null
    private var guidanceOverlay: GuidanceOverlay? = null
    private var guidanceMode = false
    private val guidanceEngine = GuidanceEngine()
    private var fadeController = FadeController()
    private val arrivalLatch = GuidanceArrivalLatch()
    private val terminalFailureStatus = TerminalFailureStatus()
    private var arrivalBehavior = ArrivalBehavior.FADE_OUT
    private var arrivalController = ArrivalController(
        ArrivalBehavior.FADE_OUT,
        InterventionPreferences.DEFAULT_ARRIVAL_FADE_MINUTES,
        InterventionPreferences.DEFAULT_LEAVE_DESTINATION_FADE_MINUTES
    )
    private val pendingViewingLaunch = PendingViewingLaunch()
    private var viewingUsageTracker: ContinuousViewingTracker? = null
    @Volatile
    private var launchedViewingPackage: String? = null
    @Volatile
    private var usageStatsReader: UsageStatsForegroundReader? = null
    private var usagePollExecutor: ScheduledExecutorService? = null
    private val usagePollGeneration = AtomicLong(0L)
    private val pendingUsageObservation = AtomicReference<UsagePollObservation?>(null)
    private val lastUsagePollCompletedNanos = AtomicLong(0L)
    @Volatile
    private var latestUsageObservation: ForegroundPackageResult? = null
    private var latestUsageObservationCompletedNanos = 0L
    private var viewingLaunchAcknowledgedNanos = 0L
    @Volatile
    private var latestUsageAccessGranted = true
    @Volatile
    private var latestUsageDataAvailable = false
    private var noUsageDataSinceNanos: Long? = null
    private var viewingInterventionArmed = false
    private var viewingCurrentlyVisible = false
    private var cachedMarkerPoseKey: FloatArray? = null
    private var cachedMarkerRoute: List<GuidanceVector3>? = null
    private var cachedWorldRoute: List<GuidanceVector3>? = null

    private val uiLock = Any()
    private var pendingGuidance: GuidanceOverlaySnapshot? = null
    private var pendingTracking: TrackingOverlaySnapshot? = null
    private var uiPostScheduled = false
    private val publishUi = object : Runnable {
        override fun run() {
            val guidance: GuidanceOverlaySnapshot?
            val tracking: TrackingOverlaySnapshot?
            synchronized(uiLock) {
                guidance = pendingGuidance
                tracking = pendingTracking
                pendingGuidance = null
                pendingTracking = null
                uiPostScheduled = false
            }
            guidance?.let { guidanceOverlay?.update(it) }
            tracking?.let { trackingOverlay?.update(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        transitionTo(BackgroundTrackingState.IDLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_OVERLAY -> {
                if (guidanceMode) guidanceOverlay?.toggleVisibility() else trackingOverlay?.toggleVisibility()
                return START_NOT_STICKY
            }
        }

        if (currentState != BackgroundTrackingState.IDLE) return START_NOT_STICKY
        guidanceMode = intent?.action == ACTION_START_GUIDANCE
        launchedViewingPackage = null
        stopUsagePolling()
        noUsageDataSinceNanos = null
        viewingInterventionArmed = false
        viewingCurrentlyVisible = false
        pendingViewingLaunch.prepare(
            if (guidanceMode) {
                intent?.getStringExtra(EXTRA_VIEWING_TARGET)?.let { stored ->
                    ViewingTarget.entries.firstOrNull { it.name == stored }
                }
            } else {
                null
            }
        )
        terminalFailureStatus.acknowledge()
        cancellation.set(false)
        finishRequested.set(false)
        fadeController = FadeController.forFadeDurationSeconds(
            InterventionPreferences.fadeToBlackSeconds(this)
        )
        arrivalLatch.reset()
        arrivalBehavior = intent?.getStringExtra(EXTRA_ARRIVAL_BEHAVIOR)
            ?.let { stored -> ArrivalBehavior.entries.firstOrNull { it.name == stored } }
            ?: InterventionPreferences.arrivalBehavior(this)
        arrivalController = ArrivalController(
            behavior = arrivalBehavior,
            arrivalFadeMinutes = InterventionPreferences.arrivalFadeMinutes(this),
            leaveDestinationFadeMinutes = InterventionPreferences.leaveDestinationFadeMinutes(this)
        )
        viewingUsageTracker = if (guidanceMode) {
            ContinuousViewingTracker(InterventionPreferences.viewingThresholdMinutes(this))
        } else null
        transitionTo(BackgroundTrackingState.PREPARING)

        try {
            check(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                "カメラ権限がありません"
            }
            startForegroundCompat()
            if (!ensureOverlay()) throw IllegalStateException("オーバーレイ権限が必要です")
            startTrackingWorker()
        } catch (exception: Exception) {
            Log.e(TAG, "service startup failed", exception)
            failBeforeWorker(exceptionMessage(exception))
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        cancellation.set(true)
        stopUsagePolling()
        pendingViewingLaunch.clear()
        viewingUsageTracker?.reset()
        viewingUsageTracker = null
        val ownerThread = workerThread
        ownerThread?.interrupt()
        ownerThread?.quitSafely()
        try {
            ownerThread?.join(TimeUnit.SECONDS.toMillis(2))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        workerHandler = null
        workerThread = null
        mainHandler.removeCallbacks(publishUi)
        synchronized(uiLock) {
            pendingGuidance = null
            pendingTracking = null
            uiPostScheduled = false
        }
        removeOverlays()
        fadeController.reset()
        if (ownerThread == null || !ownerThread.isAlive) {
            transitionTo(BackgroundTrackingState.IDLE)
        }
        super.onDestroy()
    }

    fun addStateListener(listener: BackgroundTrackingStateListener) {
        listeners += listener
        listener.onStateChanged(currentState)
    }

    fun removeStateListener(listener: BackgroundTrackingStateListener) {
        listeners -= listener
    }

    fun isGuidanceSession(): Boolean = guidanceMode

    fun terminalFailureMessage(): String? = terminalFailureStatus.current()

    fun acknowledgeTerminalError() {
        terminalFailureStatus.acknowledge()
    }

    fun pendingViewingTargetIfReady(): ViewingTarget? =
        pendingViewingLaunch.pendingTargetIfReady()

    fun acknowledgeViewingTargetLaunched(target: ViewingTarget, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val acknowledged = pendingViewingLaunch.complete(target, packageName)
        if (acknowledged) {
            launchedViewingPackage = packageName
            viewingLaunchAcknowledgedNanos = System.nanoTime()
            startUsagePolling()
        }
        return acknowledged
    }

    fun launchedViewingPackage(): String? = launchedViewingPackage

    fun hasPendingViewingTarget(): Boolean = pendingViewingLaunch.isPending()

    private fun requestStop() {
        if (currentState == BackgroundTrackingState.IDLE) {
            stopSelf()
            return
        }
        if (currentState != BackgroundTrackingState.STOPPING) {
            transitionTo(BackgroundTrackingState.STOPPING)
        }
        cancellation.set(true)
        stopUsagePolling()
        pendingViewingLaunch.clear()
        workerThread?.interrupt()
        if (workerThread == null) finishForegroundWork()
    }

    private fun startTrackingWorker() {
        val threadName = if (guidanceMode) "phase2-guidance-owner" else "phase2-vio-owner"
        val thread = HandlerThread(threadName).also { it.start() }
        workerThread = thread
        workerHandler = Handler(thread.looper).also { handler ->
            handler.post { runTrackingOwnerLoop() }
        }
    }

    /** Session/EGL/Anchor はこの owner thread だけが生成・更新・解放する。 */
    private fun runTrackingOwnerLoop() {
        var egl: HeadlessEgl? = null
        var session: Session? = null
        var markerAnchor: MarkerAnchor? = null
        var terminalError: String? = null
        try {
            check(!cancellation.get()) { "tracking cancelled before owner initialization" }
            check(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                "カメラ権限がありません"
            }

            val markerRoute = if (guidanceMode) loadAndValidateRoute() else null
            markerAnchor = if (guidanceMode) MarkerAnchor(applicationContext) else null
            egl = HeadlessEgl()
            egl.create()
            check(!cancellation.get()) { "tracking cancelled during EGL initialization" }
            session = Session(applicationContext)
            session!!.setCameraTextureName(egl.textureName)
            val config = Config(session!!).apply {
                planeFindingMode = Config.PlaneFindingMode.DISABLED
                lightEstimationMode = Config.LightEstimationMode.DISABLED
            }
            markerAnchor?.configure(config, session!!)
            session!!.configure(config)
            check(!cancellation.get()) { "tracking cancelled before ARCore resume" }
            session!!.resume()
            pendingViewingLaunch.markReady()
            /*
             * Session and EGL are assigned before any operation that can throw.
             * The finally block therefore owns partial initialization as well as
             * the normal running case.
             */
            if (guidanceMode) {
                transitionTo(BackgroundTrackingState.SEARCHING_MARKER)
                enqueueGuidance(GuidanceOverlaySnapshot(GuidanceOverlayState.WAITING_FOR_VIEWING))
            } else {
                transitionTo(BackgroundTrackingState.GUIDING)
            }
            runUpdateLoop(session!!, markerAnchor, markerRoute)
        } catch (exception: InterruptedException) {
            if (!cancellation.get()) terminalError = exceptionMessage(exception)
            Thread.currentThread().interrupt()
        } catch (exception: Exception) {
            if (!cancellation.get()) {
                Log.e(TAG, "ARCore owner loop failed", exception)
                terminalError = exceptionMessage(exception)
            }
        } finally {
            try {
                markerAnchor?.close()
            } catch (exception: Exception) {
                Log.w(TAG, "marker close failed", exception)
            }
            try {
                session?.pause()
            } catch (exception: Exception) {
                Log.w(TAG, "pause failed", exception)
            }
            try {
                session?.close()
            } catch (exception: Exception) {
                Log.w(TAG, "close failed", exception)
            }
            try {
                egl?.release()
            } catch (exception: Exception) {
                Log.w(TAG, "EGL release failed", exception)
            }
            val error = terminalError
            mainHandler.post {
                if (error != null && !cancellation.get()) {
                    terminalFailureStatus.record(error)
                    transitionTo(BackgroundTrackingState.FAILED)
                    enqueueTerminalError(error)
                } else if (currentState != BackgroundTrackingState.STOPPING) {
                    transitionTo(BackgroundTrackingState.STOPPING)
                }
                finishForegroundWork()
            }
        }
    }

    private fun runUpdateLoop(
        session: Session,
        markerAnchor: MarkerAnchor?,
        markerRoute: List<GuidanceVector3>?
    ) {
        val startedAt = System.nanoTime()
        var previousFrameNanos = startedAt
        var origin: FloatArray? = null
        var frameCount = 0
        var rateStart = startedAt
        var rateHz = 0f
        var localizationEstablished = false
        var previousProjectedDistance: Float? = null

        while (!cancellation.get()) {
            val loopStart = System.nanoTime()
            val frame = session.update()
            if (cancellation.get()) break
            val now = System.nanoTime()
            val dtSeconds = ((now - previousFrameNanos).coerceAtLeast(0L)) / NANOS_PER_SECOND
            previousFrameNanos = now
            frameCount++
            if (now - rateStart >= NANOS_PER_SECOND.toLong()) {
                rateHz = frameCount * NANOS_PER_SECOND / (now - rateStart)
                frameCount = 0
                rateStart = now
            }

            if (guidanceMode) {
                val marker = markerAnchor!!.update(frame)
                val cameraTracking = frame.camera.trackingState == TrackingState.TRACKING
                val localizationValid = cameraTracking &&
                    marker.state == MarkerDetectionState.TRACKING &&
                    marker.markerPoseInWorld != null
                val recoveryDecision = TrackingRecoveryPolicy.decide(
                    localizationEstablished = localizationEstablished,
                    camera = frame.camera.trackingState.toAvailability(),
                    anchor = marker.anchorTrackingState?.toAvailability(),
                    markerTracking = localizationValid,
                    elapsedSinceStartNanos = now - startedAt
                )
                val viewingGate = if (launchedViewingPackage != null) {
                    updateViewingGate(now)
                } else {
                    ViewingGateState(armed = false, currentlyVisible = false)
                }

                if (recoveryDecision == TrackingRecoveryDecision.TERMINAL) {
                    check(false) {
                        if (frame.camera.trackingState == TrackingState.STOPPED ||
                            marker.anchorTrackingState == TrackingState.STOPPED
                        ) "ARCoreトラッキングがSTOPPEDになったため、安全に停止しました"
                        else "マーカーの位置合わせを8秒以内に確立できませんでした"
                    }
                }
                if (recoveryDecision == TrackingRecoveryDecision.RECOVERABLE_LOSS) {
                    // PAUSED has no trusted pose. Keep the same Session, Anchor,
                    // route progress, and arrival latch. A full scrim is fail-safe
                    // until a trusted pose is available again.
                    previousProjectedDistance = null
                    if (arrivalController.isArrived()) {
                        arrivalController.update(
                            elapsedSeconds = dtSeconds,
                            targetForeground = viewingGate.currentlyVisible,
                            atDestination = null
                        )
                    }
                    transitionTo(BackgroundTrackingState.TRACKING_LOST)
                    enqueueGuidance(
                        if (viewingGate.currentlyVisible) {
                            GuidanceOverlaySnapshot(
                                state = GuidanceOverlayState.TRACKING_PAUSED,
                                guidance = GuidanceSnapshot(
                                    state = GuidanceState.GUIDING,
                                    angleDifferenceDegrees = null,
                                    remainingDistanceMeters = null,
                                    progressPercent = null,
                                    trackingLost = true
                                ),
                                fadeDensity = 1f
                            )
                        } else {
                            GuidanceOverlaySnapshot(GuidanceOverlayState.WAITING_FOR_VIEWING)
                        }
                    )
                    paceOwnerLoop(loopStart)
                    continue
                }

                if (!localizationValid) {
                    previousProjectedDistance = null
                    arrivalLatch.update(false, dtSeconds)
                    fadeController.update(
                        isGuiding = true,
                        localizationValid = false,
                        progressValid = false,
                        arcDistanceMeters = 0f,
                        dtSeconds = dtSeconds
                    )
                    if (!localizationEstablished) {
                        val overlayState = if (viewingGate.currentlyVisible) {
                            GuidanceOverlayState.SEARCHING_MARKER
                        } else {
                            GuidanceOverlayState.WAITING_FOR_VIEWING
                        }
                        transitionTo(
                            if (viewingGate.currentlyVisible) BackgroundTrackingState.SEARCHING_MARKER
                            else BackgroundTrackingState.WAITING_FOR_VIEWING
                        )
                        enqueueGuidance(GuidanceOverlaySnapshot(overlayState))
                    }
                } else {
                    localizationEstablished = true
                    if (!viewingGate.currentlyVisible && !arrivalController.isArrived()) {
                        transitionTo(BackgroundTrackingState.WAITING_FOR_VIEWING)
                        enqueueGuidance(GuidanceOverlaySnapshot(GuidanceOverlayState.WAITING_FOR_VIEWING))
                        paceOwnerLoop(loopStart)
                        continue
                    }
                    val result = calculateGuidance(
                        cameraPoseInWorld = frame.camera.pose,
                        markerPoseInWorld = marker.markerPoseInWorld!!,
                        markerRoute = markerRoute!!
                    )
                    val previousProjection = previousProjectedDistance
                    val implausibleJump = previousProjection != null &&
                        !GuidanceProgressSafety.isPlausibleProjectionDelta(
                            previousProjection,
                            result.projectedDistanceMeters,
                            dtSeconds
                        )
                    if (implausibleJump) {
                        previousProjectedDistance = null
                        if (arrivalController.isArrived()) {
                            arrivalController.update(
                                elapsedSeconds = dtSeconds,
                                targetForeground = viewingGate.currentlyVisible,
                                atDestination = null
                            )
                        } else {
                            fadeController.update(true, false, false, result.projectedDistanceMeters, dtSeconds)
                        }
                        transitionTo(BackgroundTrackingState.TRACKING_LOST)
                        enqueueGuidance(
                            if (viewingGate.currentlyVisible) {
                                trackingLostGuidanceSnapshot()
                            } else {
                                GuidanceOverlaySnapshot(GuidanceOverlayState.WAITING_FOR_VIEWING)
                            }
                        )
                        paceOwnerLoop(loopStart)
                        continue
                    }
                    previousProjectedDistance = result.projectedDistanceMeters
                    val onRoute = result.crossTrackDistanceMeters <= GuidanceEngine.DEFAULT_PROGRESS_CORRIDOR_METERS
                    if (arrivalController.isArrived()) {
                        val atDestination = result.endpointDistanceMeters <=
                            GuidanceEngine.ARRIVAL_EXIT_THRESHOLD_METERS &&
                            result.crossTrackDistanceMeters <=
                            GuidanceEngine.ARRIVAL_EXIT_CORRIDOR_METERS
                        val arrivalState = arrivalController.update(
                            elapsedSeconds = dtSeconds,
                            targetForeground = viewingGate.currentlyVisible,
                            atDestination = atDestination
                        )
                        if (viewingGate.currentlyVisible) {
                            val overlayState = if (arrivalBehavior == ArrivalBehavior.FADE_OUT ||
                                atDestination
                            ) GuidanceOverlayState.ARRIVED else GuidanceOverlayState.GUIDING
                            val guidanceState = if (overlayState == GuidanceOverlayState.ARRIVED) {
                                GuidanceState.ARRIVED
                            } else {
                                GuidanceState.GUIDING
                            }
                            transitionTo(
                                if (overlayState == GuidanceOverlayState.ARRIVED) {
                                    BackgroundTrackingState.ARRIVED
                                } else {
                                    BackgroundTrackingState.GUIDING
                                }
                            )
                            enqueueGuidance(GuidanceOverlaySnapshot(
                                state = overlayState,
                                guidance = result.toGuidanceSnapshot(guidanceState),
                                arcDistanceMeters = result.projectedDistanceMeters,
                                fadeDensity = arrivalState.density
                            ))
                        } else {
                            transitionTo(BackgroundTrackingState.ARRIVED)
                            enqueueGuidance(GuidanceOverlaySnapshot(GuidanceOverlayState.WAITING_FOR_VIEWING))
                        }
                    } else {
                        val density = fadeController.update(
                            isGuiding = true,
                            localizationValid = true,
                            progressValid = onRoute,
                            arcDistanceMeters = result.projectedDistanceMeters,
                            dtSeconds = dtSeconds
                        )
                        if (arrivalLatch.update(result.arrived, dtSeconds)) {
                            arrivalController.onArrival(density)
                            val arrivalState = arrivalController.update(
                                elapsedSeconds = 0f,
                                targetForeground = viewingGate.currentlyVisible,
                                atDestination = true
                            )
                            transitionTo(BackgroundTrackingState.ARRIVED)
                            enqueueGuidance(GuidanceOverlaySnapshot(
                                state = GuidanceOverlayState.ARRIVED,
                                guidance = result.toGuidanceSnapshot(GuidanceState.ARRIVED),
                                arcDistanceMeters = result.projectedDistanceMeters,
                                fadeDensity = arrivalState.density
                            ))
                        } else {
                            transitionTo(BackgroundTrackingState.GUIDING)
                            if (viewingGate.currentlyVisible) {
                                enqueueGuidance(GuidanceOverlaySnapshot(
                                    state = GuidanceOverlayState.GUIDING,
                                    guidance = result.toGuidanceSnapshot(GuidanceState.GUIDING),
                                    arcDistanceMeters = result.projectedDistanceMeters,
                                    fadeDensity = density
                                ))
                            } else {
                                enqueueGuidance(GuidanceOverlaySnapshot(GuidanceOverlayState.WAITING_FOR_VIEWING))
                            }
                        }
                    }
                }
            } else {
                val camera = frame.camera
                val tracking = camera.trackingState == TrackingState.TRACKING
                val position = if (tracking) camera.pose.translation.copyOf() else null
                if (position != null && origin == null) origin = position.copyOf()
                transitionTo(if (tracking) BackgroundTrackingState.GUIDING else BackgroundTrackingState.TRACKING_LOST)
                enqueueTracking(TrackingOverlaySnapshot(
                    state = stateText(camera.trackingState),
                    failureReason = if (camera.trackingState == TrackingState.PAUSED) {
                        reasonText(camera.trackingFailureReason)
                    } else null,
                    position = position,
                    straightDistance = if (position != null) origin?.distanceTo(position) else null,
                    updateRateHz = rateHz,
                    elapsedSeconds = (now - startedAt) / NANOS_PER_SECOND
                ))
            }

            paceOwnerLoop(loopStart)
        }
    }

    private fun paceOwnerLoop(loopStartNanos: Long) {
        val remainingNanos = TARGET_FRAME_NANOS - (System.nanoTime() - loopStartNanos)
        if (remainingNanos > 0) TimeUnit.NANOSECONDS.sleep(remainingNanos)
    }

    private fun updateViewingGate(nowNanos: Long): ViewingGateState {
        if (!guidanceMode) return ViewingGateState(armed = true, currentlyVisible = true)
        val expectedPackage = launchedViewingPackage
            ?: return ViewingGateState(armed = false, currentlyVisible = false)
        val pending = pendingUsageObservation.getAndSet(null)
        val freshObservation = pending?.takeIf { it.generation == usagePollGeneration.get() }
        if (freshObservation != null) {
            val result = freshObservation.result
            latestUsageObservation = result
            latestUsageObservationCompletedNanos = freshObservation.completedNanos
            latestUsageAccessGranted = result.accessGranted
            latestUsageDataAvailable = result.hasUsableData
        }
        check(latestUsageAccessGranted) {
            "視聴確認の利用状況アクセスが取り消されたため、安全に停止しました"
        }
        val observationAge = if (latestUsageObservationCompletedNanos == 0L) {
            nowNanos - viewingLaunchAcknowledgedNanos
        } else {
            nowNanos - latestUsageObservationCompletedNanos
        }
        check(observationAge < USAGE_DATA_TIMEOUT_NANOS) {
            "usage foreground observation became stale"
        }
        val noDataSince = if (latestUsageDataAvailable) {
            noUsageDataSinceNanos = null
            null
        } else {
            noUsageDataSinceNanos ?: nowNanos.also { noUsageDataSinceNanos = it }
        }
        if (noDataSince != null) {
            check(nowNanos - noDataSince < USAGE_DATA_TIMEOUT_NANOS) {
                "視聴確認データを取得できないため、安全に停止しました"
            }
        }
        if (freshObservation == null) {
            val power = getSystemService(PowerManager::class.java)
            val keyguard = getSystemService(android.app.KeyguardManager::class.java)
            val visible = viewingInterventionArmed &&
                latestUsageDataAvailable &&
                latestUsageObservation?.packageName == expectedPackage &&
                power?.isInteractive == true &&
                keyguard?.isKeyguardLocked != true
            viewingCurrentlyVisible = visible
            return ViewingGateState(viewingInterventionArmed, visible)
        }

        val freshResult = freshObservation.result
        if (freshResult.reconciliationPending) {
            viewingUsageTracker?.suspendPreviousObservationBaseline()
            viewingCurrentlyVisible = false
            return ViewingGateState(viewingInterventionArmed, currentlyVisible = false)
        }
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        val state = viewingUsageTracker?.update(
            nowNanos = freshObservation.completedNanos,
            foregroundPackage = freshResult.packageName,
            expectedPackage = expectedPackage,
            screenInteractive = power?.isInteractive == true,
            keyguardLocked = keyguard?.isKeyguardLocked == true,
            usageDataAvailable = freshResult.hasUsableData,
            differentPackageSincePreviousObservation =
                freshResult.differentPackageSincePreviousObservation,
            screenNonInteractiveSincePreviousObservation =
                freshResult.screenNonInteractiveSincePreviousObservation
        ) ?: return ViewingGateState(
            armed = viewingInterventionArmed,
            currentlyVisible = false
        )
        viewingInterventionArmed = state.interventionArmed
        viewingCurrentlyVisible = state.currentlyVisible
        return ViewingGateState(
            armed = state.interventionArmed,
            currentlyVisible = state.currentlyVisible
        )
    }

    @Synchronized
    private fun publishUsageObservation(observation: UsagePollObservation) {
        val generation = usagePollGeneration.get()
        if (observation.generation != generation) return
        pendingUsageObservation.updateAndGet { pending ->
            mergeUsagePollObservation(pending, observation, generation)
        }
    }

    @Synchronized
    private fun startUsagePolling() {
        if (!guidanceMode || launchedViewingPackage == null) return
        val launchTimestamp = viewingLaunchAcknowledgedNanos.takeIf { it != 0L }
            ?: System.nanoTime()
        stopUsagePolling(clearLaunchTimestamp = false)
        viewingLaunchAcknowledgedNanos = launchTimestamp
        val generation = usagePollGeneration.get()
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "usage-foreground-poller").apply { isDaemon = true }
        }
        usagePollExecutor = executor
        executor.scheduleWithFixedDelay({
            if (generation != usagePollGeneration.get()) return@scheduleWithFixedDelay
            try {
                val reader = usageStatsReader ?: UsageStatsForegroundReader(applicationContext).also {
                    usageStatsReader = it
                }
                val result = reader.read()
                val completedNanos = System.nanoTime()
                lastUsagePollCompletedNanos.set(completedNanos)
                publishUsageObservation(UsagePollObservation(generation, result, completedNanos))
            } catch (_: Exception) {
                val completedNanos = System.nanoTime()
                lastUsagePollCompletedNanos.set(completedNanos)
                publishUsageObservation(
                    UsagePollObservation(
                        generation,
                        ForegroundPackageResult(
                            packageName = null,
                            accessGranted = true,
                            hasUsableData = false
                        ),
                        completedNanos
                    )
                )
            }
        }, 0L, 1L, TimeUnit.SECONDS)
    }

    @Synchronized
    private fun stopUsagePolling(clearLaunchTimestamp: Boolean = true) {
        usagePollGeneration.incrementAndGet()
        usagePollExecutor?.shutdownNow()
        usagePollExecutor = null
        usageStatsReader = null
        pendingUsageObservation.set(null)
        lastUsagePollCompletedNanos.set(0L)
        latestUsageObservation = null
        latestUsageObservationCompletedNanos = 0L
        if (clearLaunchTimestamp) viewingLaunchAcknowledgedNanos = 0L
        latestUsageAccessGranted = true
        latestUsageDataAvailable = false
        noUsageDataSinceNanos = null
        viewingInterventionArmed = false
        viewingCurrentlyVisible = false
    }

    private fun TrackingState.toAvailability(): TrackingAvailability = when (this) {
        TrackingState.TRACKING -> TrackingAvailability.TRACKING
        TrackingState.PAUSED -> TrackingAvailability.PAUSED
        TrackingState.STOPPED -> TrackingAvailability.STOPPED
    }

    private fun calculateGuidance(
        cameraPoseInWorld: com.google.ar.core.Pose,
        markerPoseInWorld: com.google.ar.core.Pose,
        markerRoute: List<GuidanceVector3>
    ): GuidanceResult {
        val position = cameraPoseInWorld.translation
        val forwardPoint = cameraPoseInWorld.transformPoint(CAMERA_FORWARD_POINT)
        val worldRoute = worldRouteFor(markerPoseInWorld, markerRoute)
        check(guidanceEngine.isValidRoute(worldRoute)) {
            "変換後の経路が水平面上で短すぎます"
        }
        return guidanceEngine.calculate(
            route = worldRoute,
            currentPosition = GuidanceVector3(position[0], position[1], position[2]),
            currentForward = GuidanceVector3(
                forwardPoint[0] - position[0],
                forwardPoint[1] - position[1],
                forwardPoint[2] - position[2]
            )
        )
    }

    private fun worldRouteFor(
        markerPoseInWorld: com.google.ar.core.Pose,
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

    private fun markerPoseKey(pose: com.google.ar.core.Pose): FloatArray =
        pose.translation + pose.rotationQuaternion

    private fun FloatArray?.contentEqualsOrNull(other: FloatArray): Boolean =
        this != null && contentEquals(other)

    private fun GuidanceResult.toGuidanceSnapshot(state: GuidanceState) = GuidanceSnapshot(
        state = state,
        angleDifferenceDegrees = signedAngleDegrees,
        remainingDistanceMeters = remainingDistanceMeters,
        progressPercent = progressPercent,
        trackingLost = false
    )

    private fun loadAndValidateRoute(): List<GuidanceVector3> {
        val route = RouteStore(applicationContext).load()
            ?: throw IllegalStateException("保存済み経路がありません")
        val points = route.points.map { GuidanceVector3(it.x, it.y, it.z) }
        check(StoredRouteValidator.isValid(points)) {
            "経路が短すぎるか、有効な2点を含んでいません"
        }
        return points
    }

    private fun ensureOverlay(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        return try {
            if (guidanceMode) {
                guidanceOverlay = GuidanceOverlay(this).also { it.show() }
            } else {
                trackingOverlay = TrackingOverlay(this).also { it.show() }
            }
            true
        } catch (exception: Exception) {
            Log.e(TAG, "overlay creation failed", exception)
            false
        }
    }

    private fun removeOverlays() {
        guidanceOverlay?.remove()
        trackingOverlay?.remove()
        guidanceOverlay = null
        trackingOverlay = null
    }

    private fun enqueueGuidance(snapshot: GuidanceOverlaySnapshot) {
        synchronized(uiLock) {
            pendingGuidance = snapshot
            scheduleUiLocked()
        }
    }

    private fun trackingLostGuidanceSnapshot() = GuidanceOverlaySnapshot(
        state = GuidanceOverlayState.TRACKING_PAUSED,
        guidance = GuidanceSnapshot(
            state = GuidanceState.GUIDING,
            angleDifferenceDegrees = null,
            remainingDistanceMeters = null,
            progressPercent = null,
            trackingLost = true
        ),
        fadeDensity = 1f
    )

    private fun enqueueTracking(snapshot: TrackingOverlaySnapshot) {
        synchronized(uiLock) {
            pendingTracking = snapshot
            scheduleUiLocked()
        }
    }

    private fun scheduleUiLocked() {
        if (uiPostScheduled) return
        uiPostScheduled = true
        mainHandler.postDelayed(publishUi, UI_UPDATE_INTERVAL_MILLIS)
    }

    private fun enqueueTerminalError(message: String) {
        if (guidanceMode) {
            guidanceOverlay?.update(GuidanceOverlaySnapshot(
                state = GuidanceOverlayState.ERROR,
                errorMessage = message,
                fadeDensity = fadeController.currentDensity()
            ))
        } else {
            trackingOverlay?.update(TrackingOverlaySnapshot("ERROR", errorMessage = message))
        }
    }

    private fun failBeforeWorker(message: String) {
        terminalFailureStatus.record(message)
        transitionTo(BackgroundTrackingState.FAILED)
        enqueueTerminalError(message)
        finishForegroundWork()
    }

    private fun finishForegroundWork() {
        if (!finishRequested.compareAndSet(false, true)) return
        cancellation.set(true)
        if (currentState != BackgroundTrackingState.STOPPING) {
            transitionTo(BackgroundTrackingState.STOPPING)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        removeOverlays()
        stopSelf()
    }

    private fun transitionTo(newState: BackgroundTrackingState) {
        if (cancellation.get() &&
            newState != BackgroundTrackingState.STOPPING &&
            newState != BackgroundTrackingState.IDLE
        ) return
        if (currentState == newState) return
        currentState = newState
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { it.onStateChanged(newState) }
        } else {
            mainHandler.post { listeners.forEach { it.onStateChanged(newState) } }
        }
    }

    private fun startForegroundCompat() {
        val stopPending = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, BackgroundTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPending = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val togglePending = PendingIntent.getService(
            this,
            REQUEST_TOGGLE,
            Intent(this, BackgroundTrackingService::class.java).setAction(ACTION_TOGGLE_OVERLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(if (guidanceMode) "バックグラウンド誘導" else "バックグラウンド追跡")
            .setContentText(if (guidanceMode) "マーカー基準の経路を案内します" else "ARCore VIOを検証中")
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "表示切替", togglePending).build())
            .addAction(Notification.Action.Builder(null, "停止", stopPending).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            @Suppress("DEPRECATION") startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "バックグラウンドAR", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun exceptionMessage(exception: Exception): String {
        val detail = exception.message?.replace('\n', ' ')?.take(180)
        return if (detail.isNullOrBlank()) exception::class.java.simpleName
        else "${exception::class.java.simpleName}: $detail"
    }

    private fun stateText(state: TrackingState): String = when (state) {
        TrackingState.TRACKING -> "TRACKING"
        TrackingState.PAUSED -> "PAUSED"
        TrackingState.STOPPED -> "STOPPED"
    }

    private fun reasonText(reason: TrackingFailureReason): String = when (reason) {
        TrackingFailureReason.NONE -> "NONE"
        TrackingFailureReason.BAD_STATE -> "BAD_STATE"
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "INSUFFICIENT_LIGHT"
        TrackingFailureReason.EXCESSIVE_MOTION -> "EXCESSIVE_MOTION"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "INSUFFICIENT_FEATURES"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "CAMERA_UNAVAILABLE"
    }

    private fun FloatArray.distanceTo(other: FloatArray): Float = hypot(
        hypot(this[0] - other[0], this[1] - other[1]),
        this[2] - other[2]
    )

    companion object {
        const val ACTION_STOP = "com.nacon01.kunekune.action.STOP_BACKGROUND_TRACKING"
        const val ACTION_TOGGLE_OVERLAY = "com.nacon01.kunekune.action.TOGGLE_OVERLAY"
        const val ACTION_START_GUIDANCE = "com.nacon01.kunekune.action.START_GUIDANCE"
        const val EXTRA_VIEWING_TARGET = "com.nacon01.kunekune.extra.VIEWING_TARGET"
        const val EXTRA_ARRIVAL_BEHAVIOR = "com.nacon01.kunekune.extra.ARRIVAL_BEHAVIOR"

        @Volatile
        var currentState: BackgroundTrackingState = BackgroundTrackingState.IDLE
            private set

        fun blocksPhase1Camera(): Boolean = currentState != BackgroundTrackingState.IDLE

        private const val CHANNEL_ID = "background_tracking_2a"
        private const val NOTIFICATION_ID = 2001
        private const val REQUEST_OPEN = 2002
        private const val REQUEST_STOP = 2003
        private const val REQUEST_TOGGLE = 2004
        private const val TAG = "BackgroundTracking"
        private const val UI_UPDATE_INTERVAL_MILLIS = 80L
        private const val TARGET_FRAME_NANOS = 33_333_333L
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val USAGE_DATA_TIMEOUT_NANOS = 10_000_000_000L
        private val CAMERA_FORWARD_POINT = floatArrayOf(0f, 0f, -1f)
    }

    private class HeadlessEgl {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var textureName: Int = 0
            private set

        fun create() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            check(EGL14.eglInitialize(display, null, 0, null, 0)) { "eglInitialize failed" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            check(EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_NONE
                ), 0, configs, 0, 1, numConfigs, 0
            ) && numConfigs[0] > 0) { "eglChooseConfig failed" }
            surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureName = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        fun release() {
            try {
                if (textureName != 0 && context != EGL14.EGL_NO_CONTEXT) {
                    GLES20.glDeleteTextures(1, intArrayOf(textureName), 0)
                }
            } finally {
                textureName = 0
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
                display = EGL14.EGL_NO_DISPLAY
                context = EGL14.EGL_NO_CONTEXT
                surface = EGL14.EGL_NO_SURFACE
            }
        }
    }
}

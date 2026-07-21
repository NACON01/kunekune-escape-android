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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

class BackgroundTrackingService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var running = false
    private var overlay: TrackingOverlay? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        overlay = TrackingOverlay(this)
        if (Settings.canDrawOverlays(this)) {
            try { overlay?.show() } catch (exception: Exception) {
                showError("オーバーレイ表示失敗: " + exception.message)
            }
        } else {
            showError("SYSTEM_ALERT_WINDOW権限がありません")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_OVERLAY -> {
                mainHandler.post { overlay?.toggleVisibility() }
                return START_NOT_STICKY
            }
        }
        startForegroundCompat()
        if (workerThread == null) startTracking()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        workerThread?.quitSafely()
        workerThread?.join(TimeUnit.SECONDS.toMillis(2))
        workerHandler = null
        workerThread = null
        mainHandler.post { overlay?.remove() }
        overlay = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.nacon01.kunekune.action.STOP_BACKGROUND_TRACKING"
        const val ACTION_TOGGLE_OVERLAY = "com.nacon01.kunekune.action.TOGGLE_OVERLAY"
        private const val CHANNEL_ID = "background_tracking_2a"
        private const val NOTIFICATION_ID = 2001
        private const val REQUEST_OPEN = 2002
        private const val REQUEST_STOP = 2003
        private const val REQUEST_TOGGLE = 2004
        private const val TAG = "BackgroundTracking"

        @Volatile
        var isRunning = false
            private set
    }

    private fun startTracking() {
        running = true
        val thread = HandlerThread("2a-arcore-vio").also { it.start() }
        workerThread = thread
        workerHandler = Handler(thread.looper)
        workerHandler?.post { runTrackingLoop() }
    }

    private fun runTrackingLoop() {
        var egl: HeadlessEgl? = null
        var session: Session? = null
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                showError("カメラ権限がありません")
                return
            }
            egl = HeadlessEgl().also { it.create() }
            session = Session(applicationContext).also { arSession ->
                arSession.setCameraTextureName(egl!!.textureName)
                arSession.configure(Config(arSession).apply {
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                })
                arSession.resume()
            }
            runUpdateLoop(session!!)
        } catch (exception: Exception) {
            Log.e(TAG, "headless ARCore startup failed", exception)
            showError(exceptionMessage(exception))
        } finally {
            try { session?.pause() } catch (exception: Exception) {
                Log.w(TAG, "pause failed", exception)
            }
            try { session?.close() } catch (exception: Exception) {
                Log.w(TAG, "close failed", exception)
            }
            egl?.release()
        }
    }

    private fun runUpdateLoop(session: Session) {
        val startedAt = System.nanoTime()
        var origin: FloatArray? = null
        var frames = 0
        var rateStart = startedAt
        var rateHz = 0f
        while (running) {
            val loopStart = System.nanoTime()
            try {
                val frame = session.update()
                val now = System.nanoTime()
                frames++
                if (now - rateStart >= 1_000_000_000L) {
                    rateHz = frames * 1_000_000_000f / (now - rateStart)
                    frames = 0
                    rateStart = now
                }
                val camera = frame.camera
                val tracking = camera.trackingState == TrackingState.TRACKING
                val position = camera.pose.translation.copyOf()
                if (tracking && origin == null) origin = position.copyOf()
                val distance = if (tracking && origin != null) origin!!.distanceTo(position) else null
                publish(TrackingOverlaySnapshot(
                    state = stateText(camera.trackingState),
                    failureReason = if (camera.trackingState == TrackingState.PAUSED) {
                        reasonText(camera.trackingFailureReason)
                    } else null,
                    position = if (tracking) position else null,
                    straightDistance = distance,
                    updateRateHz = rateHz,
                    elapsedSeconds = (now - startedAt) / 1_000_000_000f
                ))
            } catch (exception: Exception) {
                Log.e(TAG, "ARCore update() failed", exception)
                val now = System.nanoTime()
                publish(TrackingOverlaySnapshot(
                    state = "ERROR",
                    updateRateHz = rateHz,
                    elapsedSeconds = (now - startedAt) / 1_000_000_000f,
                    errorMessage = exceptionMessage(exception)
                ))
            }
            val remaining = 33_333_333L - (System.nanoTime() - loopStart)
            if (remaining > 0) {
                try { TimeUnit.NANOSECONDS.sleep(remaining) } catch (_: InterruptedException) {
                    if (!running) break
                }
            }
        }
    }

    private fun startForegroundCompat() {
        val stopIntent = Intent(this, BackgroundTrackingService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, REQUEST_STOP, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPending = PendingIntent.getActivity(
            this, REQUEST_OPEN, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = Intent(this, BackgroundTrackingService::class.java).setAction(ACTION_TOGGLE_OVERLAY)
        val togglePending = PendingIntent.getService(
            this, REQUEST_TOGGLE, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("2a 裏で追跡テスト")
            .setContentText("ARCoreのVIOトラッキングを実行中")
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
        isRunning = true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "2a 裏で追跡テスト", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun publish(snapshot: TrackingOverlaySnapshot) {
        mainHandler.post { overlay?.update(snapshot) }
    }

    private fun showError(message: String) {
        publish(TrackingOverlaySnapshot("ERROR", errorMessage = message))
    }

    private fun exceptionMessage(exception: Exception): String {
        val detail = exception.message?.replace('\n', ' ')?.take(180)
        return if (detail.isNullOrBlank()) exception::class.java.simpleName
        else exception::class.java.simpleName + ": " + detail
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
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "eglMakeCurrent failed"
            }
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
            if (textureName != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureName), 0)
                textureName = 0
            }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
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

package com.nacon01.kunekune

import android.content.Context
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import kotlin.math.hypot

data class TrackingSnapshot(
    val state: TrackingState,
    val failureReason: TrackingFailureReason,
    val position: FloatArray?,
    val cumulativeDistance: Float,
    val straightDistance: Float,
    val framesPerSecond: Float,
    val marker: MarkerTrackingSnapshot
)

class ArTrackingManager(context: Context) {
    private val appContext = context.applicationContext
    private val markerAnchor = MarkerAnchor(appContext)
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

    var onSnapshot: ((TrackingSnapshot) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val hasSession: Boolean
        get() = session != null

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
            reportError("ARCore SDKが古すぎます。アプリを更新してください。")
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
        updateFps()
        val position = if (camera.trackingState == TrackingState.TRACKING) {
            camera.pose.translation.copyOf().also(::updateDistance)
        } else {
            null
        }
        val straightDistance = if (position != null && origin != null) {
            origin!!.distanceTo(position)
        } else {
            0f
        }

        onSnapshot?.invoke(
            TrackingSnapshot(
                state = camera.trackingState,
                failureReason = camera.trackingFailureReason,
                position = position,
                cumulativeDistance = cumulativeDistance,
                straightDistance = straightDistance,
                framesPerSecond = framesPerSecond,
                marker = marker
            )
        )
        return frame
    }

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
                marker = markerAnchor.stoppedSnapshot()
            )
        )
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

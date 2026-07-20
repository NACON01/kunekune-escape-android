package com.nacon01.kunekune

import android.content.Context
import android.graphics.BitmapFactory
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.io.IOException
import kotlin.math.sqrt

enum class MarkerDetectionState {
    NOT_DETECTED,
    TRACKING,
    LOST
}

data class MarkerTrackingSnapshot(
    val state: MarkerDetectionState,
    val cameraPoseInMarkerSpace: Pose?,
    val distanceMeters: Float?
)

class MarkerAnchor(private val context: Context) {
    private var markerIndex = -1
    private var anchor: Anchor? = null
    private var markerPose: Pose? = null
    private var state = MarkerDetectionState.NOT_DETECTED
    private var latestCameraPose: Pose? = null

    val detectionState: MarkerDetectionState
        get() = state

    val cameraPoseInMarkerSpace: Pose?
        get() = latestCameraPose

    @Throws(IOException::class)
    fun configure(config: Config, session: Session) {
        context.assets.open(MARKER_ASSET).use { input ->
            val bitmap = BitmapFactory.decodeStream(input)
                ?: throw IOException("マーカー画像を読み込めません: $MARKER_ASSET")
            val database = AugmentedImageDatabase(session)
            markerIndex = database.addImage(MARKER_NAME, bitmap, MARKER_PHYSICAL_WIDTH_METERS)
            bitmap.recycle()
            config.augmentedImageDatabase = database
        }
    }

    fun update(frame: Frame): MarkerTrackingSnapshot {
        var markerUpdated = false
        for (image in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
            if (image.index != markerIndex) continue
            markerUpdated = true
            if (image.trackingState == TrackingState.TRACKING &&
                image.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
            ) {
                if (anchor == null) {
                    anchor = image.createAnchor(image.centerPose)
                    markerPose = anchor?.pose
                }
                state = MarkerDetectionState.TRACKING
            } else if (anchor != null) {
                state = MarkerDetectionState.LOST
            }
        }

        anchor?.let { currentAnchor ->
            if (currentAnchor.trackingState == TrackingState.TRACKING) {
                markerPose = currentAnchor.pose
            }
        }
        if (anchor == null && markerUpdated && state != MarkerDetectionState.TRACKING) {
            state = MarkerDetectionState.NOT_DETECTED
        }

        val cameraPose = if (frame.camera.trackingState == TrackingState.TRACKING) {
            markerPose?.inverse()?.compose(frame.camera.pose)
        } else {
            null
        }
        latestCameraPose = cameraPose
        val translation = cameraPose?.translation
        val distance = translation?.let {
            sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2])
        }
        return MarkerTrackingSnapshot(state, cameraPose, distance)
    }

    fun stoppedSnapshot(): MarkerTrackingSnapshot {
        if (anchor != null) state = MarkerDetectionState.LOST
        latestCameraPose = null
        return MarkerTrackingSnapshot(state, null, null)
    }

    fun close() {
        anchor?.detach()
        anchor = null
        markerPose = null
        latestCameraPose = null
        markerIndex = -1
        state = MarkerDetectionState.NOT_DETECTED
    }

    companion object {
        private const val MARKER_ASSET = "marker.png"
        private const val MARKER_NAME = "kunekune-marker"
        private const val MARKER_PHYSICAL_WIDTH_METERS = 0.15f
    }
}

package com.nacon01.kunekune

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.google.ar.core.Pose
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.util.Locale

class DebugHud(context: Context) : TextView(context) {
    private var errorMessage: String? = null

    init {
        val padding = (context.resources.displayMetrics.density * 12).toInt()
        setPadding(padding, padding, padding, padding)
        setTextColor(Color.WHITE)
        textSize = 12f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.TOP or Gravity.START
        background = ColorDrawable(Color.argb(185, 0, 0, 0))
        text = stoppedText()
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        )
    }

    fun update(snapshot: TrackingSnapshot) {
        post {
            if (errorMessage != null) return@post
            text = snapshotText(snapshot)
        }
    }

    fun showError(message: String) {
        post {
            errorMessage = message
            text = "状態: STOPPED\nエラー: $message"
        }
    }

    fun clearError() {
        post {
            errorMessage = null
            text = stoppedText()
        }
    }

    private fun snapshotText(snapshot: TrackingSnapshot): String {
        val state = when (snapshot.state) {
            TrackingState.TRACKING -> "TRACKING"
            TrackingState.PAUSED -> "PAUSED"
            TrackingState.STOPPED -> "STOPPED"
        }
        val reason = if (snapshot.state == TrackingState.PAUSED) {
            "\n理由: ${failureReasonText(snapshot.failureReason)}"
        } else {
            ""
        }
        val position = snapshot.position?.let {
            "x=${it[0].formatMeters()}  y=${it[1].formatMeters()}  z=${it[2].formatMeters()}"
        } ?: "x=--  y=--  z=--"
        return "状態: $state$reason\n" +
            "ポーズ: $position m\n" +
            "累積移動距離: ${snapshot.cumulativeDistance.formatMeters()} m\n" +
            "直線距離: ${snapshot.straightDistance.formatMeters()} m\n" +
            "FPS: ${snapshot.framesPerSecond.formatFps()}\n" +
            "マーカー: ${markerStateText(snapshot.marker.state)}\n" +
            "マーカーまでの距離: ${snapshot.marker.distanceMeters?.formatMeters() ?: "--"} m\n" +
            "マーカー座標系: ${markerPositionText(snapshot.marker.cameraPoseInMarkerSpace)} m"
    }

    private fun stoppedText() = "状態: STOPPED\nARCoreセッションを開始しています..."

    private fun failureReasonText(reason: TrackingFailureReason): String = when (reason) {
        TrackingFailureReason.NONE -> "原因なし"
        TrackingFailureReason.BAD_STATE -> "不正な状態: セッションを再開してください"
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "照明不足: 明るい場所に向けてください"
        TrackingFailureReason.EXCESSIVE_MOTION -> "動きが速すぎます: ゆっくり端末を動かしてください"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "特徴点不足: カメラを模様のある場所に向けてください"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "カメラ利用不可: カメラを確認してください"
    }

    private fun markerStateText(state: MarkerDetectionState): String = when (state) {
        MarkerDetectionState.NOT_DETECTED -> "未検出"
        MarkerDetectionState.TRACKING -> "追跡中"
        MarkerDetectionState.LOST -> "見失い(最後の位置を使用)"
    }

    private fun markerPositionText(pose: Pose?): String = pose?.translation?.let {
        "mx=${it[0].formatMeters()}  my=${it[1].formatMeters()}  mz=${it[2].formatMeters()}"
    } ?: "mx=--  my=--  mz=--"

    private fun Float.formatMeters() = String.format(Locale.US, "%.2f", this)

    private fun Float.formatFps() = String.format(Locale.US, "%.1f", this)
}

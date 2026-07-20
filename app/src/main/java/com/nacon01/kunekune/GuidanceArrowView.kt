package com.nacon01.kunekune

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale
import kotlin.math.abs

class GuidanceArrowView(context: Context) : View(context) {
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private var guidance = GuidanceSnapshot(GuidanceState.INACTIVE, null, null, null, false)
    private var smoothedAngle = 0f
    private var hasSmoothedAngle = false

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isClickable = false
    }

    fun update(snapshot: GuidanceSnapshot) {
        post {
            if (snapshot.state == GuidanceState.GUIDING && guidance.state != GuidanceState.GUIDING) {
                hasSmoothedAngle = false
            }
            guidance = snapshot
            snapshot.angleDifferenceDegrees?.let { angle ->
                if (!hasSmoothedAngle) {
                    smoothedAngle = angle
                    hasSmoothedAngle = true
                } else {
                    val delta = normalizeAngle(angle - smoothedAngle)
                    smoothedAngle = normalizeAngle(smoothedAngle + delta * EMA_ALPHA)
                }
            }
            visibility = if (snapshot.state == GuidanceState.INACTIVE) INVISIBLE else VISIBLE
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (guidance.state) {
            GuidanceState.INACTIVE -> Unit
            GuidanceState.ARRIVED -> drawArrived(canvas)
            GuidanceState.GUIDING -> drawGuidance(canvas)
        }
    }

    private fun drawGuidance(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f - ARROW_SIZE
        val color = if (guidance.trackingLost) Color.GRAY else Color.rgb(40, 220, 255)
        arrowPaint.color = color

        canvas.save()
        canvas.rotate(-smoothedAngle, centerX, centerY)
        val path = Path().apply {
            moveTo(centerX, centerY - ARROW_SIZE)
            lineTo(centerX - ARROW_SIZE * 0.62f, centerY + ARROW_SIZE * 0.35f)
            lineTo(centerX - ARROW_SIZE * 0.18f, centerY + ARROW_SIZE * 0.2f)
            lineTo(centerX - ARROW_SIZE * 0.18f, centerY + ARROW_SIZE)
            lineTo(centerX + ARROW_SIZE * 0.18f, centerY + ARROW_SIZE)
            lineTo(centerX + ARROW_SIZE * 0.18f, centerY + ARROW_SIZE * 0.2f)
            lineTo(centerX + ARROW_SIZE * 0.62f, centerY + ARROW_SIZE * 0.35f)
            close()
        }
        canvas.drawPath(path, arrowPaint)
        canvas.restore()

        textPaint.color = color
        textPaint.textSize = DISTANCE_TEXT_SIZE
        val remaining = guidance.remainingDistanceMeters?.let {
            String.format(Locale.US, "%.1f m", it)
        } ?: "--.- m"
        canvas.drawText(remaining, centerX, centerY + ARROW_SIZE + 58f, textPaint)

        if (guidance.trackingLost) {
            textPaint.color = Color.WHITE
            textPaint.textSize = LOST_TEXT_SIZE
            canvas.drawText("トラッキング喪失", centerX, centerY + ARROW_SIZE + 100f, textPaint)
        }
    }

    private fun drawArrived(canvas: Canvas) {
        textPaint.color = Color.WHITE
        textPaint.textSize = ARRIVED_TEXT_SIZE
        canvas.drawText("到着!", width / 2f, height / 2f, textPaint)
    }

    private fun normalizeAngle(angle: Float): Float {
        var result = angle % 360f
        if (result > 180f) result -= 360f
        if (result < -180f) result += 360f
        return result
    }

    companion object {
        private const val EMA_ALPHA = 0.18f
        private const val ARROW_SIZE = 72f
        private const val DISTANCE_TEXT_SIZE = 28f
        private const val LOST_TEXT_SIZE = 22f
        private const val ARRIVED_TEXT_SIZE = 52f
    }
}

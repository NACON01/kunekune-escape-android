package com.nacon01.kunekune

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

enum class GuidanceOverlayState {
    NO_ROUTE,
    SEARCHING_MARKER,
    TRACKING_PAUSED,
    GUIDING,
    ARRIVED,
    ERROR
}

data class GuidanceOverlaySnapshot(
    val state: GuidanceOverlayState,
    val guidance: GuidanceSnapshot = GuidanceSnapshot(
        state = GuidanceState.INACTIVE,
        angleDifferenceDegrees = null,
        remainingDistanceMeters = null,
        progressPercent = null,
        trackingLost = false
    ),
    val errorMessage: String? = null,
    val arcDistanceMeters: Float? = null
)

class GuidanceOverlay(context: Context) : FrameLayout(context.applicationContext) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val arrowView = GuidanceArrowView(context, compact = true)
    private val statusView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    private var attached = false
    private var userHidden = false
    private var lastState: GuidanceOverlayState? = null
    private var arrivalGeneration = 0

    init {
        setBackgroundColor(Color.argb(145, 0, 0, 0))
        setPadding(0, dp(4), 0, dp(4))
        addView(arrowView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(statusView, LayoutParams(LayoutParams.MATCH_PARENT, dp(24), Gravity.TOP))
        isClickable = false
    }

    fun show() {
        if (attached) return
        val screenWidth = context.resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            (screenWidth / 5f).toInt().coerceAtLeast(1),
            (screenWidth / 5f).toInt() + dp(56),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight()
        }
        windowManager.addView(this, params)
        attached = true
    }

    fun update(snapshot: GuidanceOverlaySnapshot) {
        post {
            if (!attached) return@post
            val stateChanged = lastState != snapshot.state
            lastState = snapshot.state
            if (snapshot.state == GuidanceOverlayState.ARRIVED) {
                arrowView.update(inactiveGuidance())
                statusView.text = "到着"
                if (stateChanged) {
                    val generation = ++arrivalGeneration
                    visibility = if (userHidden) GONE else VISIBLE
                    postDelayed({
                        if (generation == arrivalGeneration && lastState == GuidanceOverlayState.ARRIVED) {
                            visibility = GONE
                        }
                    }, ARRIVAL_MESSAGE_MILLIS)
                }
                return@post
            }

            arrivalGeneration++
            if (!userHidden) visibility = VISIBLE
            when (snapshot.state) {
                GuidanceOverlayState.NO_ROUTE -> {
                    arrowView.update(inactiveGuidance())
                    statusView.text = "経路がありません"
                }
                GuidanceOverlayState.SEARCHING_MARKER -> {
                    arrowView.update(inactiveGuidance())
                    statusView.text = "マーカーに向けてください"
                }
                GuidanceOverlayState.TRACKING_PAUSED -> {
                    arrowView.update(inactiveGuidance())
                    statusView.text = "トラッキング復帰中"
                }
                GuidanceOverlayState.GUIDING -> {
                    arrowView.update(snapshot.guidance)
                    statusView.text = "マーカー: 追跡中"
                }
                GuidanceOverlayState.ERROR -> {
                    arrowView.update(inactiveGuidance())
                    statusView.text = snapshot.errorMessage ?: "誘導を開始できません"
                }
                GuidanceOverlayState.ARRIVED -> Unit
            }
        }
    }

    fun toggleVisibility() {
        if (!attached) return
        userHidden = !userHidden
        visibility = if (userHidden) GONE else VISIBLE
    }

    fun remove() {
        if (!attached) return
        try { windowManager.removeView(this) } finally { attached = false }
    }

    private fun inactiveGuidance() = GuidanceSnapshot(
        state = GuidanceState.INACTIVE,
        angleDifferenceDegrees = null,
        remainingDistanceMeters = null,
        progressPercent = null,
        trackingLost = false
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId == 0) dp(24) else resources.getDimensionPixelSize(resourceId)
    }

    companion object {
        private const val ARRIVAL_MESSAGE_MILLIS = 2_500L
    }
}

package com.nacon01.kunekune


import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

enum class GuidanceOverlayState {
    NO_ROUTE,
    SEARCHING_MARKER,
    WAITING_FOR_VIEWING,
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
    val arcDistanceMeters: Float? = null,
    val fadeDensity: Float = 0f
)

/** 矢印と暗転膜を一つの安全な TYPE_APPLICATION_OVERLAY window で管理する。 */
class GuidanceOverlay(context: Context) : FrameLayout(context.applicationContext) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val scrimView = View(context.applicationContext).apply {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
    }
    private val arrowView = GuidanceArrowView(context, compact = true)
    private val statusView = createStatusView(context)
    private var attached = false
    private var userHidden = false
    private var windowParams: WindowManager.LayoutParams? = null
    private var fullyOpaque = false
    private var fullBlackoutView: FrameLayout? = null
    private var fullBlackoutArrowView: GuidanceArrowView? = null
    private var fullBlackoutStatusView: TextView? = null

    init {
        isClickable = false
        isFocusable = false
        addView(scrimView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addHud(this, arrowView, statusView)
    }

    fun show() {
        if (attached) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12 の untrusted-touch 判定は View.alpha ではなく window alpha を使う。
            alpha = OverlayOpacityPolicy.TOUCH_SAFE_WINDOW_ALPHA
        }
        windowManager.addView(this, params)
        windowParams = params
        attached = true
    }

    /** main thread から呼ぶ。完全暗転時は独立した不透明ウィンドウを重ねる。 */
    fun update(snapshot: GuidanceOverlaySnapshot) {
        if (!attached) return
        updateFadeDensity(snapshot.fadeDensity, snapshot)
        visibility = if (userHidden || snapshot.state == GuidanceOverlayState.WAITING_FOR_VIEWING) {
            GONE
        } else {
            VISIBLE
        }
        renderHud(arrowView, statusView, snapshot)
        val blackoutArrow = fullBlackoutArrowView
        val blackoutStatus = fullBlackoutStatusView
        if (blackoutArrow != null && blackoutStatus != null) {
            renderHud(blackoutArrow, blackoutStatus, snapshot)
        }
    }

    private fun renderHud(
        targetArrow: GuidanceArrowView,
        targetStatus: TextView,
        snapshot: GuidanceOverlaySnapshot
    ) {
        targetStatus.setTextColor(
            GuidanceColorPolicy.markerColor(snapshot.guidance.trackingLost)
        )
        when (snapshot.state) {
            GuidanceOverlayState.NO_ROUTE -> showInactive(
                targetArrow,
                targetStatus,
                "経路がありません"
            )
            GuidanceOverlayState.SEARCHING_MARKER -> showInactive(
                targetArrow,
                targetStatus,
                "マーカーに向けてください"
            )
            GuidanceOverlayState.WAITING_FOR_VIEWING -> showInactive(
                targetArrow,
                targetStatus,
                ""
            )
            GuidanceOverlayState.TRACKING_PAUSED -> {
                targetArrow.update(inactiveGuidance())
                targetStatus.text = "壁や床から離してください"
            }
            GuidanceOverlayState.GUIDING -> {
                targetArrow.update(snapshot.guidance)
                targetStatus.text = "誘導中"
            }
            GuidanceOverlayState.ARRIVED -> showInactive(targetArrow, targetStatus, "到着")
            GuidanceOverlayState.ERROR -> showInactive(
                targetArrow,
                targetStatus,
                snapshot.errorMessage ?: "誘導を継続できません"
            )
        }
    }

    fun toggleVisibility() {
        if (!attached) return
        userHidden = !userHidden
        visibility = if (userHidden) GONE else VISIBLE
        fullBlackoutView?.visibility = if (userHidden || !fullyOpaque) GONE else VISIBLE
    }

    fun remove() {
        if (!attached) return
        removeFullBlackout()
        try {
            windowManager.removeView(this)
        } catch (_: Exception) {
            // The system may already have removed the window after permission loss.
        } finally {
            attached = false
            windowParams = null
        }
    }

    private fun updateFadeDensity(
        requestedDensity: Float,
        snapshot: GuidanceOverlaySnapshot
    ) {
        val opacity = OverlayOpacityPolicy.forDesiredDensity(requestedDensity)
        scrimView.alpha = opacity.scrimAlpha
        if (fullyOpaque != opacity.fullyOpaque) {
            setBackgroundColor(if (opacity.fullyOpaque) Color.BLACK else Color.TRANSPARENT)
        }
        if (opacity.fullyOpaque) {
            ensureFullBlackout(snapshot)
        } else {
            removeFullBlackout()
        }

        val current = windowParams ?: return
        if (kotlin.math.abs(current.alpha - opacity.windowAlpha) < 0.001f &&
            fullyOpaque == opacity.fullyOpaque
        ) return
        val updated = WindowManager.LayoutParams().apply {
            copyFrom(current)
            alpha = opacity.windowAlpha
            format = PixelFormat.TRANSLUCENT
        }
        try {
            windowManager.updateViewLayout(this, updated)
            windowParams = updated
            fullyOpaque = opacity.fullyOpaque
        } catch (_: IllegalArgumentException) {
            // The overlay can be detached asynchronously when permission is revoked.
        }
    }

    /**
     * The terminal blackout is a separate touch-consuming opaque window.
     * It does not depend on the transparency or touch-through behavior of
     * the guidance window underneath it.
     */
    private fun ensureFullBlackout(snapshot: GuidanceOverlaySnapshot) {
        if (fullBlackoutView != null) return
        val blackout = FrameLayout(context.applicationContext).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            setOnTouchListener { _, _ -> true }
            visibility = if (userHidden) GONE else VISIBLE
        }
        val blackoutArrow = GuidanceArrowView(context, compact = true)
        val blackoutStatus = createStatusView(context)
        addHud(blackout, blackoutArrow, blackoutStatus)
        renderHud(blackoutArrow, blackoutStatus, snapshot)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 1f
        }
        try {
            windowManager.addView(blackout, params)
            fullBlackoutView = blackout
            fullBlackoutArrowView = blackoutArrow
            fullBlackoutStatusView = blackoutStatus
        } catch (_: Exception) {
            // The guidance window still provides the existing black scrim fallback.
        }
    }

    private fun removeFullBlackout() {
        val blackout = fullBlackoutView ?: return
        try {
            windowManager.removeView(blackout)
        } catch (_: Exception) {
            // The system may already have removed it after permission loss.
        } finally {
            fullBlackoutView = null
            fullBlackoutArrowView = null
            fullBlackoutStatusView = null
        }
    }

    private fun showInactive(
        targetArrow: GuidanceArrowView,
        targetStatus: TextView,
        message: String
    ) {
        targetArrow.update(inactiveGuidance())
        targetStatus.text = message
    }

    private fun createStatusView(viewContext: Context) = TextView(viewContext).apply {
        setTextColor(GuidanceColorPolicy.markerColor(trackingLost = false))
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }

    private fun addHud(
        container: FrameLayout,
        targetArrow: GuidanceArrowView,
        targetStatus: TextView
    ) {
        val arrowWidth = (resources.displayMetrics.widthPixels / 5f).toInt().coerceAtLeast(1)
        container.addView(
            targetArrow,
            LayoutParams(
                arrowWidth,
                arrowWidth + dp(56),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = statusBarHeight()
            }
        )
        container.addView(
            targetStatus,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(48),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = statusBarHeight()
            }
        )
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
        /** 0.8 未満で余裕を持たせ、一枚だけなので合成不透明度も同値。 */
        const val SAFE_WINDOW_ALPHA = OverlayOpacityPolicy.TOUCH_SAFE_WINDOW_ALPHA
    }
}

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
    private val statusView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    private var attached = false
    private var userHidden = false
    private var windowParams: WindowManager.LayoutParams? = null

    init {
        isClickable = false
        isFocusable = false
        addView(scrimView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val screenWidth = resources.displayMetrics.widthPixels
        val arrowWidth = (screenWidth / 5f).toInt().coerceAtLeast(1)
        addView(arrowView, LayoutParams(arrowWidth, arrowWidth + dp(56), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = statusBarHeight()
        })
        addView(statusView, LayoutParams(LayoutParams.MATCH_PARENT, dp(48), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = statusBarHeight()
        })
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

    /** main thread から呼ぶ。window alpha は固定し、暗さだけを子 View で変える。 */
    fun update(snapshot: GuidanceOverlaySnapshot) {
        if (!attached) return
        updateFadeDensity(snapshot.fadeDensity)
        visibility = if (userHidden) GONE else VISIBLE
        when (snapshot.state) {
            GuidanceOverlayState.NO_ROUTE -> showInactive("経路がありません")
            GuidanceOverlayState.SEARCHING_MARKER -> showInactive("マーカーに向けてください")
            GuidanceOverlayState.WAITING_FOR_VIEWING -> showWaiting()
            GuidanceOverlayState.TRACKING_PAUSED -> {
                arrowView.update(inactiveGuidance())
                statusView.text = "壁や床から離してください"
            }
            GuidanceOverlayState.GUIDING -> {
                arrowView.update(snapshot.guidance)
                statusView.text = "誘導中"
            }
            GuidanceOverlayState.ARRIVED -> showInactive("到着")
            GuidanceOverlayState.ERROR -> showInactive(snapshot.errorMessage ?: "誘導を継続できません")
        }
    }

    fun toggleVisibility() {
        if (!attached) return
        userHidden = !userHidden
        visibility = if (userHidden) GONE else VISIBLE
    }

    fun remove() {
        if (!attached) return
        try {
            windowManager.removeView(this)
        } catch (_: Exception) {
            // The system may already have removed the window after permission loss.
        } finally {
            attached = false
            windowParams = null
        }
    }

    private fun updateFadeDensity(requestedDensity: Float) {
        val opacity = OverlayOpacityPolicy.forDesiredDensity(requestedDensity)
        scrimView.alpha = opacity.scrimAlpha

        val params = windowParams ?: return
        if (kotlin.math.abs(params.alpha - opacity.windowAlpha) < 0.001f) return
        params.alpha = opacity.windowAlpha
        try {
            windowManager.updateViewLayout(this, params)
        } catch (_: IllegalArgumentException) {
            // The overlay can be detached asynchronously when permission is revoked.
        }
    }

    private fun showInactive(message: String) {
        arrowView.update(inactiveGuidance())
        statusView.text = message
    }

    private fun showWaiting() {
        // The intervention is not armed yet, or the launched package is away. The
        // window must be completely hidden in either case.
        visibility = GONE
        arrowView.update(inactiveGuidance())
        statusView.text = ""
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

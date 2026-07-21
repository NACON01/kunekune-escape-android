package com.nacon01.kunekune

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** YouTube操作を妨げず、画面全体を覆う黒い膜。 */
class ScrimOverlay(context: Context) : View(context.applicationContext) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var attached = false
    private var userHidden = false

    init {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
        isClickable = false
        isFocusable = false
    }

    fun show() {
        if (attached) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(this, params)
        attached = true
        visibility = if (userHidden) GONE else VISIBLE
    }

    fun updateDensity(density: Float) {
        post {
            if (attached) alpha = density.coerceIn(0f, 1f)
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
        } finally {
            attached = false
        }
    }
}

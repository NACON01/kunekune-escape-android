package com.nacon01.kunekune

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

/** Opaque, touch-consuming intervention window for one currently blocked app. */
class AppProtectionOverlay(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val root = FrameLayout(appContext).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        setOnTouchListener { _, _ -> true }
    }
    private val message = TextView(appContext).apply {
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        setPadding(32, 32, 32, 16)
    }
    private val openButton = Button(appContext).apply {
        text = "くねくねエスケープを開く"
        setOnClickListener {
            appContext.startActivity(Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
        }
    }
    private var attached = false

    init {
        val content = FrameLayout(appContext).apply {
            addView(message, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
            addView(openButton, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            ).apply { bottomMargin = 96 })
        }
        root.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    fun show(targetLabel: String) {
        message.text = "自宅内では「$targetLabel」をブロック中です。\nマーカーの手順で許可されるまで利用できません。"
        if (attached) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            windowManager.addView(root, params)
            attached = true
        } catch (exception: RuntimeException) {
            attached = false
            throw exception
        }
    }

    fun hide() {
        if (!attached) return
        try {
            windowManager.removeView(root)
        } catch (_: Exception) {
            // Permission revocation or the system may already have detached the window.
        } finally {
            attached = false
        }
    }
}

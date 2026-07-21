package com.nacon01.kunekune

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale

data class TrackingOverlaySnapshot(
    val state: String,
    val failureReason: String? = null,
    val position: FloatArray? = null,
    val straightDistance: Float? = null,
    val updateRateHz: Float = 0f,
    val elapsedSeconds: Float = 0f,
    val errorMessage: String? = null
)

class TrackingOverlay(context: Context) : TextView(context.applicationContext) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var attached = false

    init {
        val padding = (context.resources.displayMetrics.density * 12).toInt()
        setPadding(padding, padding, padding, padding)
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.TOP or Gravity.START
        background = ColorDrawable(Color.argb(195, 0, 0, 0))
    }

    fun show() {
        if (attached) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(this, params)
        attached = true
    }
    fun update(snapshot: TrackingOverlaySnapshot) {
        post {
            if (!attached) return@post
            val p = snapshot.position
            text = "2a ヘッドレスVIO" + System.lineSeparator() +
                "状態: " + snapshot.state + "  理由: " + (snapshot.failureReason ?: "なし") +
                System.lineSeparator() + "ポーズ: " + (p?.contentToString() ?: "なし") + " m" +
                System.lineSeparator() + "開始地点からの直線距離: " + (snapshot.straightDistance ?: 0f) + " m" +
                System.lineSeparator() + "更新レート: " + snapshot.updateRateHz + " Hz" +
                System.lineSeparator() + "経過: " + snapshot.elapsedSeconds + " 秒" +
                (snapshot.errorMessage?.let { System.lineSeparator() + "エラー: " + it } ?: "")
        }
    }

    fun remove() {
        if (!attached) return
        try { windowManager.removeView(this) } finally { attached = false }
    }
}

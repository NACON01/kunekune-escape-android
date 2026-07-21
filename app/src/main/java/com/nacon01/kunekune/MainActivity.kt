package com.nacon01.kunekune

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

class MainActivity : Activity() {
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var debugHud: DebugHud
    private lateinit var guidanceView: GuidanceArrowView
    private lateinit var recordButton: Button
    private lateinit var guidanceButton: Button
    private lateinit var backgroundTrackingButton: Button
    private lateinit var departureButton: Button
    private lateinit var guidanceHint: TextView
    private lateinit var trackingManager: ArTrackingManager
    private var latestGuidanceState = GuidanceState.INACTIVE
    private var installRequested = false
    private var phase2aPendingStart = false
    private var phase2aAwaitingNotification = false
    private var phase2aAwaitingCamera = false
    private var guidancePendingStart = false

    private val renderer by lazy {
        CameraBackgroundRenderer(
            trackingManager = trackingManager,
            displayRotation = { windowManager.defaultDisplay.rotation }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        trackingManager = ArTrackingManager(this).apply {
            onError = { debugHud.showError(it) }
        }

        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setPreserveEGLContextOnPause(true)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        debugHud = DebugHud(this)
        guidanceView = GuidanceArrowView(this)
        recordButton = Button(this).apply {
            text = "マーカーを映してください"
            isEnabled = false
            setOnClickListener {
                if (trackingManager.isRecording) {
                    trackingManager.stopRecording()
                } else if (trackingManager.startRecording()) {
                    text = "記録終了"
                    isEnabled = true
                }
            }
        }
        guidanceButton = Button(this).apply {
            text = "誘導開始"
            isEnabled = false
            setOnClickListener {
                if (latestGuidanceState == GuidanceState.GUIDING) {
                    trackingManager.stopGuidance()
                } else {
                    trackingManager.startGuidance()
                }
            }
        }
        backgroundTrackingButton = Button(this).apply {
            text = "2a: 裏で追跡テスト"
            setOnClickListener { onBackgroundTrackingButtonClicked() }
        }
        departureButton = Button(this).apply {
            text = "離脱開始"
            setOnClickListener { onDepartureButtonClicked() }
        }
        guidanceHint = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(8, 2, 8, 2)
        }
        trackingManager.onSnapshot = { snapshot ->
            debugHud.update(snapshot)
            guidanceView.update(snapshot.guidance)
            runOnUiThread { updateControls(snapshot) }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(recordButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(guidanceButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttonRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(backgroundTrackingButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(departureButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(guidanceHint, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(glSurfaceView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(guidanceView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(debugHud, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(bottomControls, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                val margin = (resources.displayMetrics.density * 12).toInt()
                setMargins(margin, margin, margin, margin)
            })
        }
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        if (phase2aPendingStart || guidancePendingStart) {
            continueBackgroundTrackingStart()
        } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startArCore()
        } else {
            debugHud.showError("カメラ権限が必要です。設定からカメラの使用を許可してください。")
        }
    }

    override fun onPause() {
        trackingManager.pause()
        glSurfaceView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        trackingManager.close()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            phase2aAwaitingNotification = false
            continueBackgroundTrackingStart()
            return
        }
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            phase2aAwaitingCamera = false
            if (phase2aPendingStart || guidancePendingStart) {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    continueBackgroundTrackingStart()
                } else {
                    phase2aPendingStart = false
                    guidancePendingStart = false
                    debugHud.showError("カメラ権限が拒否されたため、2aを開始できません。")
                }
                return
            }
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                debugHud.clearError()
                startArCore()
            } else {
                debugHud.showError("カメラ権限が拒否されました。ARトラッキングにはカメラ権限が必要です。")
            }
        }
    }

    private fun onBackgroundTrackingButtonClicked() {
        if (BackgroundTrackingService.isRunning) {
            stopService(Intent(this, BackgroundTrackingService::class.java))
            backgroundTrackingButton.text = "2a: 裏で追跡テスト"
            Handler(Looper.getMainLooper()).postDelayed({
                if (!BackgroundTrackingService.isRunning) trackingManager.resume()
            }, 300L)
            return
        }
        phase2aPendingStart = true
        continueBackgroundTrackingStart()
    }

    private fun onDepartureButtonClicked() {
        val savedRoute = try {
            RouteStore(this).load()
        } catch (_: Exception) {
            null
        }
        if (savedRoute?.points.isNullOrEmpty()) {
            guidanceHint.text = "保存済み経路がないため離脱できません"
            return
        }

        guidancePendingStart = true
        if (BackgroundTrackingService.isRunning) {
            stopService(Intent(this, BackgroundTrackingService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({
                continueBackgroundTrackingStart()
            }, 350L)
        } else {
            continueBackgroundTrackingStart()
        }
    }

    private fun continueBackgroundTrackingStart() {
        if (!phase2aPendingStart && !guidancePendingStart) return
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!phase2aAwaitingNotification) {
                phase2aAwaitingNotification = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            }
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!phase2aAwaitingCamera) {
                phase2aAwaitingCamera = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            }
            return
        }
        val startGuidance = guidancePendingStart
        phase2aPendingStart = false
        guidancePendingStart = false
        if (startGuidance) {
            glSurfaceView.onPause()
            trackingManager.close()
        } else {
            trackingManager.pause()
        }
        val serviceIntent = Intent(this, BackgroundTrackingService::class.java).apply {
            if (startGuidance) action = BackgroundTrackingService.ACTION_START_GUIDANCE
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        if (startGuidance) {
            departureButton.text = "誘導サービス実行中"
            moveTaskToBack(true)
        } else {
            backgroundTrackingButton.text = "2a: テスト停止"
        }
    }

    private fun updateControls(snapshot: TrackingSnapshot) {
        latestGuidanceState = snapshot.guidance.state
        if (snapshot.recording.isRecording) {
            recordButton.text = "記録終了"
            recordButton.isEnabled = true
        } else {
            val markerRecognized = snapshot.marker.state == MarkerDetectionState.TRACKING
            recordButton.text = if (markerRecognized) "記録開始" else "マーカーを映してください"
            recordButton.isEnabled = markerRecognized
        }

        if (snapshot.guidance.state == GuidanceState.GUIDING) {
            guidanceButton.text = "誘導終了"
            guidanceButton.isEnabled = true
            guidanceHint.text = if (snapshot.guidance.trackingLost) {
                "トラッキングを復帰してください"
            } else {
                ""
            }
        } else {
            guidanceButton.text = "誘導開始"
            val hasRoute = snapshot.recording.savedRoute != null
            val markerRecognized = snapshot.marker.state == MarkerDetectionState.TRACKING
            guidanceButton.isEnabled = hasRoute && markerRecognized
            guidanceHint.text = when {
                !hasRoute -> "保存済み経路がありません"
                !markerRecognized -> "マーカーを認識してください"
                snapshot.guidance.state == GuidanceState.ARRIVED -> "到着しました"
                else -> ""
            }
        }
    }

    private fun startArCore() {
        if (trackingManager.hasSession) {
            trackingManager.resume()
            return
        }

        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALLED -> {
                    installRequested = true
                    val error = trackingManager.createSession()
                    if (error == null) {
                        debugHud.clearError()
                        trackingManager.resume()
                    }
                }

                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                }
            }
        } catch (_: UnavailableDeviceNotCompatibleException) {
            debugHud.showError("この端末はARCoreに対応していません。")
        } catch (_: UnavailableUserDeclinedInstallationException) {
            debugHud.showError("Google Play 開発者サービス（AR向け）のインストールが必要です。")
        } catch (_: UnavailableApkTooOldException) {
            debugHud.showError("Google Play 開発者サービス（AR向け）を更新してください。")
        } catch (_: UnavailableSdkTooOldException) {
            debugHud.showError("ARCore SDKが古すぎます。アプリを更新してください。")
        } catch (_: Exception) {
            debugHud.showError("ARCoreを起動できませんでした。Google Play 開発者サービスを確認してください。")
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST = 1002
    }
}

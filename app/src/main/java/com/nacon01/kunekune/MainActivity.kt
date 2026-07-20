package com.nacon01.kunekune

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

class MainActivity : Activity() {
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var debugHud: DebugHud
    private lateinit var recordButton: Button
    private lateinit var trackingManager: ArTrackingManager
    private var installRequested = false

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
        trackingManager.onSnapshot = { snapshot ->
            debugHud.update(snapshot)
            runOnUiThread { updateRecordingButton(snapshot) }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(glSurfaceView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(debugHud, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(recordButton, FrameLayout.LayoutParams(
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
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
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
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                debugHud.clearError()
                startArCore()
            } else {
                debugHud.showError("カメラ権限が拒否されました。ARトラッキングにはカメラ権限が必要です。")
            }
        }
    }

    private fun updateRecordingButton(snapshot: TrackingSnapshot) {
        if (snapshot.recording.isRecording) {
            recordButton.text = "記録終了"
            recordButton.isEnabled = true
        } else {
            val markerRecognized = snapshot.marker.state == MarkerDetectionState.TRACKING
            recordButton.text = if (markerRecognized) "記録開始" else "マーカーを映してください"
            recordButton.isEnabled = markerRecognized
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
    }
}

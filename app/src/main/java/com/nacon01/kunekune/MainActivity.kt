package com.nacon01.kunekune

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.provider.Settings
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
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
    private lateinit var departureButton: Button
    private lateinit var fadeMinusButton: Button
    private lateinit var fadeValueButton: Button
    private lateinit var fadePlusButton: Button
    private lateinit var viewingThresholdMinusButton: Button
    private lateinit var viewingThresholdValueButton: Button
    private lateinit var viewingThresholdPlusButton: Button
    private lateinit var progressRewardValueButton: Button
    private lateinit var arrivalFadeMinusButton: Button
    private lateinit var arrivalFadeValueButton: Button
    private lateinit var arrivalFadePlusButton: Button
    private lateinit var leaveDestinationFadeMinusButton: Button
    private lateinit var leaveDestinationFadeValueButton: Button
    private lateinit var leaveDestinationFadePlusButton: Button
    private lateinit var viewingTargetButton: Button
    private lateinit var guidanceHint: TextView
    private lateinit var trackingManager: ArTrackingManager
    private var latestGuidanceState = GuidanceState.INACTIVE
    private var installRequested = false
    private var notificationPermissionDenied = false
    private var guidancePendingStart = false
    private var awaitingNotification = false
    private var awaitingCamera = false
    private var usageSettingsOpened = false
    private var overlaySettingsOpened = false
    private var pendingPictureInPicturePackage: String? = null
    private var pendingArrivalBehavior: ArrivalBehavior? = null
    private var arrivalDialogShown = false
    private var activityResumed = false
    private var glResumed = false
    private var serviceBound = false
    private var serviceBindRequested = false
    private var boundTrackingService: BackgroundTrackingService? = null
    private var activeGuidanceService = false
    private var awaitingServiceStart = false
    private var pendingTerminalServiceError: String? = null
    private var viewingStartPending = false
    private var latestMarkerRecognized = false
    private var viewingLaunchRetryScheduled = false
    private var viewingLaunchAttempts = 0
    private var guidanceStopCompletionPosted = false
    private val viewingLaunchRetry = Runnable {
        viewingLaunchRetryScheduled = false
        if (activityResumed) launchPendingViewingTargetIfReady()
    }
    private val guidanceStopCompletion = object : Runnable {
        override fun run() {
            if (!guidanceStopCompletionPosted) return
            if (BackgroundTrackingService.blocksPhase1Camera()) {
                guidanceHint.postDelayed(this, SERVICE_STOP_POLL_INTERVAL_MILLIS)
                return
            }
            guidanceStopCompletionPosted = false
            completeGuidanceStop()
        }
    }

    private val serviceStateListener = BackgroundTrackingStateListener { state ->
        runOnUiThread { onBackgroundServiceStateChanged(state) }
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!serviceBindRequested) return
            val service = (binder as? BackgroundTrackingService.LocalBinder)?.service() ?: return
            boundTrackingService = service
            serviceBound = true
            activeGuidanceService = service.isGuidanceSession()
            pendingTerminalServiceError = service.terminalFailureMessage()
            if (activeGuidanceService) departureButton.text = "誘導停止"
            service.addStateListener(serviceStateListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            pendingTerminalServiceError = pendingTerminalServiceError ?:
                boundTrackingService?.terminalFailureMessage()
            boundTrackingService = null
            serviceBound = false
            if (!BackgroundTrackingService.blocksPhase1Camera()) restorePhase1IfPossible()
        }
    }

    private val renderer by lazy {
        CameraBackgroundRenderer(
            trackingManager = trackingManager,
            displayRotation = { windowManager.defaultDisplay.rotation }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guidancePendingStart = savedInstanceState?.getBoolean(STATE_GUIDANCE_PENDING_START) ?: false
        viewingStartPending = savedInstanceState?.getBoolean(STATE_VIEWING_START_PENDING) ?: false
        pendingArrivalBehavior = savedInstanceState?.getString(STATE_PENDING_ARRIVAL_BEHAVIOR)
            ?.let { value -> ArrivalBehavior.entries.firstOrNull { it.name == value } }
        notificationPermissionDenied = savedInstanceState
            ?.getBoolean(STATE_NOTIFICATION_PERMISSION_DENIED) ?: false
        usageSettingsOpened = savedInstanceState?.getBoolean(STATE_USAGE_SETTINGS_OPENED) ?: false
        overlaySettingsOpened = savedInstanceState?.getBoolean(STATE_OVERLAY_SETTINGS_OPENED) ?: false
        pendingPictureInPicturePackage = savedInstanceState
            ?.getString(STATE_PENDING_PICTURE_IN_PICTURE_PACKAGE)
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
        departureButton = Button(this).apply {
            text = "位置合わせして視聴開始"
            setOnClickListener { onDepartureButtonClicked() }
        }
        fadeMinusButton = Button(this).apply {
            text = "−"
            setOnClickListener {
                InterventionPreferences.adjustFadeToBlackSeconds(this@MainActivity, -1)
                updateInterventionSettingsControls()
            }
        }
        fadeValueButton = Button(this)
        fadePlusButton = Button(this).apply {
            text = "+"
            setOnClickListener {
                InterventionPreferences.adjustFadeToBlackSeconds(this@MainActivity, 1)
                updateInterventionSettingsControls()
            }
        }
        viewingThresholdMinusButton = Button(this).apply {
            text = "−"
            setOnClickListener {
                InterventionPreferences.adjustViewingThreshold(this@MainActivity, -1)
                updateInterventionSettingsControls()
            }
        }
        viewingThresholdValueButton = Button(this)
        viewingThresholdPlusButton = Button(this).apply {
            text = "+"
            setOnClickListener {
                InterventionPreferences.adjustViewingThreshold(this@MainActivity, 1)
                updateInterventionSettingsControls()
            }
        }
        progressRewardValueButton = Button(this).apply {
            setOnClickListener { showProgressRewardDialog() }
        }
        arrivalFadeMinusButton = Button(this).apply {
            text = "−"
            setOnClickListener {
                InterventionPreferences.adjustArrivalFadeMinutes(this@MainActivity, -1)
                updateInterventionSettingsControls()
            }
        }
        arrivalFadeValueButton = Button(this)
        arrivalFadePlusButton = Button(this).apply {
            text = "+"
            setOnClickListener {
                InterventionPreferences.adjustArrivalFadeMinutes(this@MainActivity, 1)
                updateInterventionSettingsControls()
            }
        }
        leaveDestinationFadeMinusButton = Button(this).apply {
            text = "−"
            setOnClickListener {
                InterventionPreferences.adjustLeaveDestinationFadeMinutes(this@MainActivity, -1)
                updateInterventionSettingsControls()
            }
        }
        leaveDestinationFadeValueButton = Button(this)
        leaveDestinationFadePlusButton = Button(this).apply {
            text = "+"
            setOnClickListener {
                InterventionPreferences.adjustLeaveDestinationFadeMinutes(this@MainActivity, 1)
                updateInterventionSettingsControls()
            }
        }
        viewingTargetButton = Button(this).apply {
            setOnClickListener {
                InterventionPreferences.cycleViewingTarget(this@MainActivity)
                updateInterventionSettingsControls()
            }
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
        val fadeSettingsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@MainActivity).apply { text = "暗転" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(fadeMinusButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(fadeValueButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(fadePlusButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val viewingThresholdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@MainActivity).apply { text = "視聴閾値" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(viewingThresholdMinusButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(viewingThresholdValueButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(viewingThresholdPlusButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(viewingTargetButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val progressRewardRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                TextView(this@MainActivity).apply { text = "回復距離" },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                progressRewardValueButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
            )
        }
        val arrivalFadeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@MainActivity).apply { text = "到着後暗転" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(arrivalFadeMinusButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(arrivalFadeValueButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(arrivalFadePlusButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val leaveDestinationFadeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@MainActivity).apply { text = "目的地離脱後暗転" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(leaveDestinationFadeMinusButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(leaveDestinationFadeValueButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(leaveDestinationFadePlusButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttonRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(departureButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(fadeSettingsRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(viewingThresholdRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(progressRewardRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(arrivalFadeRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(leaveDestinationFadeRow, LinearLayout.LayoutParams(
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
        updateInterventionSettingsControls()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        viewingLaunchAttempts = 0
        pendingPictureInPicturePackage?.let { packageName ->
            InterventionPreferences.markPictureInPictureSetupGuidanceShown(this, packageName)
            pendingPictureInPicturePackage = null
        }
        if (BackgroundTrackingService.currentState == BackgroundTrackingState.STOPPING) {
            suspendPhase1ForService(closeSession = false)
            scheduleGuidanceStopCompletion()
        } else if (guidancePendingStart) {
            continueBackgroundTrackingStart()
        } else if (BackgroundTrackingService.blocksPhase1Camera()) {
            suspendPhase1ForService(closeSession = false)
            bindTrackingService()
        } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            restorePhase1IfPossible()
        } else {
            debugHud.showError("カメラ権限が必要です。設定からカメラの使用を許可してください。")
        }
        if (viewingStartPending && !BackgroundTrackingService.blocksPhase1Camera()) {
            departureButton.text = "マーカー待機中"
            guidanceHint.text = "位置合わせマーカーを映してください"
        }
        if (viewingStartPending && pendingArrivalBehavior == null) showArrivalBehaviorDialog()
        launchPendingViewingTargetIfReady()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_GUIDANCE_PENDING_START, guidancePendingStart)
        outState.putBoolean(STATE_VIEWING_START_PENDING, viewingStartPending)
        outState.putString(STATE_PENDING_ARRIVAL_BEHAVIOR, pendingArrivalBehavior?.name)
        outState.putBoolean(STATE_NOTIFICATION_PERMISSION_DENIED, notificationPermissionDenied)
        outState.putBoolean(STATE_USAGE_SETTINGS_OPENED, usageSettingsOpened)
        outState.putBoolean(STATE_OVERLAY_SETTINGS_OPENED, overlaySettingsOpened)
        outState.putString(
            STATE_PENDING_PICTURE_IN_PICTURE_PACKAGE,
            pendingPictureInPicturePackage
        )
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onPause() {
        activityResumed = false
        if (::guidanceHint.isInitialized) guidanceHint.removeCallbacks(viewingLaunchRetry)
        viewingLaunchRetryScheduled = false
        if (glResumed) {
            trackingManager.pause()
            glSurfaceView.onPause()
            glResumed = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::guidanceHint.isInitialized) guidanceHint.removeCallbacks(viewingLaunchRetry)
        if (::guidanceHint.isInitialized) guidanceHint.removeCallbacks(guidanceStopCompletion)
        guidanceStopCompletionPosted = false
        val guidanceLaunchStillPending = awaitingServiceStart ||
            boundTrackingService?.hasPendingViewingTarget() == true
        if (isFinishing && activeGuidanceService && guidanceLaunchStillPending &&
            BackgroundTrackingService.blocksPhase1Camera()
        ) {
            val stopIntent = Intent(this, BackgroundTrackingService::class.java)
                .setAction(BackgroundTrackingService.ACTION_STOP)
            startService(stopIntent)
        }
        unbindTrackingService()
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
            awaitingNotification = false
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionDenied = true
            }
            continueBackgroundTrackingStart()
            return
        }
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            awaitingCamera = false
            if (guidancePendingStart) {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    continueBackgroundTrackingStart()
                } else {
                    guidancePendingStart = false
                    clearPendingViewingStart()
                    debugHud.showError("カメラ権限が拒否されたため、誘導を開始できません。")
                }
                return
            }
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    debugHud.clearError()
                    restorePhase1IfPossible()
            } else {
                debugHud.showError("カメラ権限が拒否されました。ARトラッキングにはカメラ権限が必要です。")
            }
        }
    }

    private fun onDepartureButtonClicked() {
        if (activeGuidanceService && BackgroundTrackingService.blocksPhase1Camera()) {
            guidancePendingStart = false
            clearPendingViewingStart()
            requestBackgroundServiceStop()
            return
        }
        if (!hasValidSavedRoute()) {
            guidanceHint.text = "有効な保存済み経路がないため開始できません"
            return
        }
        usageSettingsOpened = false
        overlaySettingsOpened = false
        pendingPictureInPicturePackage = null
        viewingStartPending = true
        pendingArrivalBehavior = null
        departureButton.text = "マーカー待機中"
        showArrivalBehaviorDialog()
    }

    private fun requestGuidanceStart() {
        if (!hasValidSavedRoute()) {
            guidanceHint.text = "有効な保存済み経路がないため離脱できません"
            return
        }

        if (pendingArrivalBehavior == null) return

        if (!UsageStatsForegroundReader.hasUsageAccess(this)) {
            guidancePendingStart = true
            if (!usageSettingsOpened) {
                usageSettingsOpened = true
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } else {
                guidanceHint.text = "利用状況へのアクセスを許可してください"
            }
            return
        }

        guidancePendingStart = true
        if (BackgroundTrackingService.blocksPhase1Camera()) {
            requestBackgroundServiceStop()
        } else {
            continueBackgroundTrackingStart()
        }
    }

    private fun hasValidSavedRoute(): Boolean {
        val savedRoute = try {
            RouteStore(this).load()
        } catch (_: Exception) {
            null
        }
        val points = savedRoute?.points?.map { GuidanceVector3(it.x, it.y, it.z) }
        return points != null && StoredRouteValidator.isValid(points)
    }

    private fun updateInterventionSettingsControls() {
        if (!::fadeValueButton.isInitialized) return
        val fadeSeconds = InterventionPreferences.fadeToBlackSeconds(this)
        fadeValueButton.text = "${fadeSeconds}秒"
        fadeMinusButton.isEnabled = fadeSeconds > InterventionPreferences.FADE_MIN_SECONDS
        fadePlusButton.isEnabled = fadeSeconds < InterventionPreferences.FADE_MAX_SECONDS
        val thresholdSeconds = InterventionPreferences.viewingThresholdSeconds(this)
        viewingThresholdValueButton.text =
            InterventionPreferences.formatViewingThreshold(thresholdSeconds)
        viewingThresholdMinusButton.isEnabled =
            thresholdSeconds > InterventionPreferences.THRESHOLD_MIN_SECONDS
        viewingThresholdPlusButton.isEnabled =
            thresholdSeconds < InterventionPreferences.THRESHOLD_MAX_SECONDS
        val progressRewardCentimeters =
            InterventionPreferences.progressRewardCentimeters(this)
        progressRewardValueButton.text =
            "${InterventionPreferences.formatProgressRewardCentimeters(progressRewardCentimeters)}cm"
        val arrivalFadeMinutes = InterventionPreferences.arrivalFadeMinutes(this)
        arrivalFadeValueButton.text = "${arrivalFadeMinutes}分"
        arrivalFadeMinusButton.isEnabled =
            arrivalFadeMinutes > InterventionPreferences.ARRIVAL_FADE_MIN_MINUTES
        arrivalFadePlusButton.isEnabled =
            arrivalFadeMinutes < InterventionPreferences.ARRIVAL_FADE_MAX_MINUTES
        val leaveDestinationFadeMinutes = InterventionPreferences.leaveDestinationFadeMinutes(this)
        leaveDestinationFadeValueButton.text = "${leaveDestinationFadeMinutes}分"
        leaveDestinationFadeMinusButton.isEnabled =
            leaveDestinationFadeMinutes > InterventionPreferences.LEAVE_DESTINATION_FADE_MIN_MINUTES
        leaveDestinationFadePlusButton.isEnabled =
            leaveDestinationFadeMinutes < InterventionPreferences.LEAVE_DESTINATION_FADE_MAX_MINUTES
        viewingTargetButton.text = InterventionPreferences.viewingTarget(this).displayName
    }

    private fun showProgressRewardDialog() {
        val current = InterventionPreferences.progressRewardCentimeters(this)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(InterventionPreferences.formatProgressRewardCentimeters(current))
            selectAll()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("フェード回復に必要な移動距離")
            .setMessage("0.5〜300cmで入力してください")
            .setView(input)
            .setPositiveButton("保存", null)
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim().toFloatOrNull()
                if (value == null ||
                    !InterventionPreferences.isValidProgressRewardCentimeters(value)
                ) {
                    input.error = "0.5〜300cmで入力してください"
                } else {
                    InterventionPreferences.setProgressRewardCentimeters(this, value)
                    updateInterventionSettingsControls()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun continueBackgroundTrackingStart() {
        if (!guidancePendingStart) return
        if (BackgroundTrackingService.currentState == BackgroundTrackingState.STOPPING) {
            suspendPhase1ForService(closeSession = false)
            scheduleGuidanceStopCompletion()
            return
        }
        if (!UsageStatsForegroundReader.hasUsageAccess(this)) {
            if (!usageSettingsOpened) {
                usageSettingsOpened = true
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } else {
                guidanceHint.text = "利用状況へのアクセスを許可してください"
            }
            return
        }
        usageSettingsOpened = false
        val viewingPackage = preferredViewingPackage()
        if (viewingPackage != null && !ensurePictureInPictureSetup(viewingPackage)) {
            return
        }
        if (BackgroundTrackingService.blocksPhase1Camera()) {
            bindTrackingService()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            if (!overlaySettingsOpened) {
                overlaySettingsOpened = true
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            } else {
                guidanceHint.text = "他のアプリの上に重ねて表示する権限を許可してください"
            }
            return
        }
        overlaySettingsOpened = false
        if (BackgroundTrackingService.currentState == BackgroundTrackingState.STOPPING) {
            suspendPhase1ForService(closeSession = false)
            scheduleGuidanceStopCompletion()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            !notificationPermissionDenied &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!awaitingNotification) {
                awaitingNotification = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            }
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!awaitingCamera) {
                awaitingCamera = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            }
            return
        }
        if (BackgroundTrackingService.currentState == BackgroundTrackingState.STOPPING) {
            suspendPhase1ForService(closeSession = false)
            scheduleGuidanceStopCompletion()
            return
        }
        pendingTerminalServiceError = null
        boundTrackingService?.acknowledgeTerminalError()
        val startGuidance = guidancePendingStart
        guidancePendingStart = false
        suspendPhase1ForService(closeSession = startGuidance)
        val serviceIntent = Intent(this, BackgroundTrackingService::class.java).apply {
            if (startGuidance) {
                action = BackgroundTrackingService.ACTION_START_GUIDANCE
                putExtra(
                    BackgroundTrackingService.EXTRA_VIEWING_TARGET,
                    InterventionPreferences.viewingTarget(this@MainActivity).name
                )
                putExtra(
                    BackgroundTrackingService.EXTRA_ARRIVAL_BEHAVIOR,
                    pendingArrivalBehavior!!.name
                )
            }
        }
        try {
            awaitingServiceStart = true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (exception: Exception) {
            awaitingServiceStart = false
            debugHud.showError("バックグラウンド追跡を開始できませんでした。")
            restorePhase1IfPossible()
            return
        }
        activeGuidanceService = startGuidance
        bindTrackingService()
        if (startGuidance) {
            departureButton.text = "マーカー確認中"
            guidanceHint.text = "マーカーを映したままお待ちください"
        }
    }

    private fun updateControls(snapshot: TrackingSnapshot) {
        latestGuidanceState = snapshot.guidance.state
        val markerRecognized = snapshot.marker.state == MarkerDetectionState.TRACKING
        latestMarkerRecognized = markerRecognized
        if (viewingStartPending && markerRecognized && !BackgroundTrackingService.blocksPhase1Camera()) {
            viewingStartPending = false
            requestGuidanceStart()
            return
        }
        if (snapshot.recording.isRecording) {
            recordButton.text = "記録終了"
            recordButton.isEnabled = true
        } else {
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
        if (BackgroundTrackingService.blocksPhase1Camera()) return
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

    private fun suspendPhase1ForService(closeSession: Boolean) {
        if (glResumed) {
            trackingManager.pause()
            glSurfaceView.onPause()
            glResumed = false
        }
        if (closeSession) trackingManager.close()
    }

    private fun restorePhase1IfPossible() {
        if (!activityResumed || BackgroundTrackingService.blocksPhase1Camera()) return
        if (!glResumed) {
            glSurfaceView.onResume()
            glResumed = true
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startArCore()
        }
    }

    private fun bindTrackingService() {
        if (BackgroundTrackingService.currentState == BackgroundTrackingState.STOPPING) {
            suspendPhase1ForService(closeSession = false)
            scheduleGuidanceStopCompletion()
            return
        }
        if (serviceBindRequested || boundTrackingService != null) return
        serviceBindRequested = true
        try {
            if (!bindService(
                    Intent(this, BackgroundTrackingService::class.java),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
                )
            ) {
                serviceBindRequested = false
            }
        } catch (exception: Exception) {
            serviceBindRequested = false
            Log.e(TAG, "background service bind failed", exception)
        }
    }

    private fun unbindTrackingService() {
        boundTrackingService?.removeStateListener(serviceStateListener)
        if (serviceBindRequested || serviceBound) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) { }
        }
        boundTrackingService = null
        serviceBound = false
        serviceBindRequested = false
    }

    private fun requestBackgroundServiceStop() {
        guidanceHint.text = "誘導サービスを停止中"
        awaitingServiceStart = false
        val stopIntent = Intent(this, BackgroundTrackingService::class.java)
            .setAction(BackgroundTrackingService.ACTION_STOP)
        if (BackgroundTrackingService.currentState == BackgroundTrackingState.STOPPING) {
            suspendPhase1ForService(closeSession = false)
            scheduleGuidanceStopCompletion()
            return
        }
        startService(stopIntent)
        bindTrackingService()
    }

    private fun showArrivalBehaviorDialog() {
        if (arrivalDialogShown || !viewingStartPending || pendingArrivalBehavior != null || isFinishing) return
        arrivalDialogShown = true
        AlertDialog.Builder(this)
            .setTitle("到着時の動作")
            .setItems(
                arrayOf(ArrivalBehavior.RELEASE.displayName, ArrivalBehavior.FADE_OUT.displayName)
            ) { _, which ->
                arrivalDialogShown = false
                pendingArrivalBehavior = ArrivalBehavior.entries[which]
                if (latestMarkerRecognized) {
                    viewingStartPending = false
                    requestGuidanceStart()
                } else {
                    guidanceHint.text = "位置合わせマーカーを映してください"
                }
            }
            .setOnCancelListener {
                arrivalDialogShown = false
                clearPendingViewingStart()
            }
            .show()
    }

    private fun clearPendingViewingStart() {
        viewingStartPending = false
        guidancePendingStart = false
        pendingArrivalBehavior = null
        usageSettingsOpened = false
        overlaySettingsOpened = false
        pendingPictureInPicturePackage = null
        arrivalDialogShown = false
        departureButton.text = "位置合わせして視聴開始"
    }

    private fun preferredViewingPackage(): String? {
        val youtubeUri = Uri.parse(YOUTUBE_HOME_URL)
        if (InterventionPreferences.viewingTarget(this) == ViewingTarget.YOUTUBE_APP) {
            val youtubeIntent = Intent(Intent.ACTION_VIEW, youtubeUri).apply {
                setPackage(YOUTUBE_PACKAGE)
            }
            if (packageManager.resolveActivity(
                    youtubeIntent,
                    PackageManager.MATCH_DEFAULT_ONLY
                ) != null
            ) {
                return YOUTUBE_PACKAGE
            }
        }
        return browserIntents(youtubeUri).firstOrNull()?.`package`
    }

    private fun ensurePictureInPictureSetup(targetPackage: String): Boolean {
        if (!PictureInPicturePermission.shouldOpenInitialSetup(
                targetPackage,
                InterventionPreferences.isPictureInPictureSetupGuidanceShown(this, targetPackage)
            )
        ) {
            return true
        }
        guidancePendingStart = true
        pendingPictureInPicturePackage = targetPackage
        guidanceHint.text = "初回のみです。PiPをオフにして戻ってください"
        if (openPictureInPictureSettings(targetPackage)) return false

        markPictureInPictureSetupGuidance(targetPackage)
        return true
    }

    private fun openPictureInPictureSettings(targetPackage: String): Boolean {
        val packageUri = Uri.parse("package:$targetPackage")
        val directIntent = Intent(PICTURE_IN_PICTURE_SETTINGS_ACTION, packageUri)
        return try {
            startActivity(directIntent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun markPictureInPictureSetupGuidance(packageName: String) {
        InterventionPreferences.markPictureInPictureSetupGuidanceShown(this, packageName)
        if (pendingPictureInPicturePackage == packageName) {
            pendingPictureInPicturePackage = null
        }
    }

    private fun launchViewingTarget(selected: ViewingTarget): ViewingLaunchResult? {
        val youtubeUri = Uri.parse(YOUTUBE_HOME_URL)
        return when (selected) {
            ViewingTarget.YOUTUBE_APP -> {
                val appIntent = Intent(Intent.ACTION_VIEW, youtubeUri).apply {
                    setPackage(YOUTUBE_PACKAGE)
                }
                tryStartViewingIntent(appIntent)?.let { ViewingLaunchResult(it) }
                    ?: run {
                    val browserPackage = tryStartBrowser(youtubeUri)
                    if (browserPackage != null) {
                        guidanceHint.text = "YouTubeアプリが見つからないためブラウザ版を開きました"
                        ViewingLaunchResult(browserPackage)
                    } else {
                        guidanceHint.text = "YouTubeを開けませんでした。ブラウザを確認してください"
                        null
                    }
                }
            }
            ViewingTarget.BROWSER -> {
                tryStartBrowser(youtubeUri)?.let { ViewingLaunchResult(it) } ?: run {
                    guidanceHint.text = "既定ブラウザを開けませんでした。端末のブラウザ設定を確認してください"
                    null
                }
            }
        }
    }

    private fun tryStartViewingIntent(intent: Intent): String? {
        val targetPackage = intent.`package` ?: intent.component?.packageName
        if (targetPackage != null && !ensurePictureInPictureSetup(targetPackage)) return null
        return try {
            startActivity(intent)
            targetPackage
        } catch (exception: Exception) {
            Log.e(TAG, "viewing target launch failed", exception)
            null
        }
    }

    private fun tryStartBrowser(uri: Uri): String? =
        browserIntents(uri).firstNotNullOfOrNull(::tryStartViewingIntent)

    private fun browserIntents(uri: Uri): List<Intent> {
        val browserLauncher = Intent.makeMainSelectorActivity(
            Intent.ACTION_MAIN,
            Intent.CATEGORY_APP_BROWSER
        )
        val installedBrowserPackages = packageManager.queryIntentActivities(
            browserLauncher,
            PackageManager.MATCH_ALL
        ).map { it.activityInfo.packageName }.distinct()
        val defaultBrowserPackage = packageManager.resolveActivity(
            browserLauncher,
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        return ViewingTargetLaunchPolicy.orderedBrowserPackages(
            defaultBrowserPackage,
            installedBrowserPackages
        ).mapNotNull { browserPackage ->
            Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                setPackage(browserPackage)
            }.takeIf { packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }
        }
    }

    private fun onBackgroundServiceStateChanged(state: BackgroundTrackingState) {
        when (state) {
            BackgroundTrackingState.PREPARING -> {
                awaitingServiceStart = false
                guidanceHint.text = "ARCoreを準備中"
            }
            BackgroundTrackingState.SEARCHING_MARKER -> {
                guidanceHint.text = "バックグラウンドで位置を確立中"
                if (activeGuidanceService) launchPendingViewingTargetIfReady()
            }
            BackgroundTrackingState.WAITING_FOR_VIEWING -> {
                guidanceHint.text = "選択したアプリを連続視聴中です"
                if (activeGuidanceService) launchPendingViewingTargetIfReady()
            }
            BackgroundTrackingState.GUIDING -> {
                if (activeGuidanceService) {
                    departureButton.text = "誘導停止"
                    guidanceHint.text = "位置合わせ完了"
                    launchPendingViewingTargetIfReady()
                }
            }
            BackgroundTrackingState.TRACKING_LOST -> guidanceHint.text = "位置を復帰中"
            BackgroundTrackingState.ARRIVED -> guidanceHint.text = "到着しました"
            BackgroundTrackingState.FAILED -> {
                pendingTerminalServiceError = boundTrackingService?.terminalFailureMessage()
                guidanceHint.text = pendingTerminalServiceError ?: "誘導を安全停止しました"
            }
            BackgroundTrackingState.STOPPING -> {
                pendingTerminalServiceError = pendingTerminalServiceError ?:
                    boundTrackingService?.terminalFailureMessage()
                awaitingServiceStart = false
                unbindTrackingService()
                activeGuidanceService = false
                guidanceHint.text = "カメラを解放中"
                scheduleGuidanceStopCompletion()
            }
            BackgroundTrackingState.IDLE -> {
                if (awaitingServiceStart) return
                scheduleGuidanceStopCompletion()
            }
        }
    }

    private fun scheduleGuidanceStopCompletion() {
        if (guidanceStopCompletionPosted || !::guidanceHint.isInitialized) return
        guidanceStopCompletionPosted = true
        guidanceHint.post(guidanceStopCompletion)
    }

    private fun completeGuidanceStop() {
        val terminalError = pendingTerminalServiceError ?:
            boundTrackingService?.terminalFailureMessage()
        pendingTerminalServiceError = terminalError
        activeGuidanceService = false
        departureButton.text = "位置合わせして視聴開始"

        if (terminalError == null && guidancePendingStart && pendingArrivalBehavior != null) {
            if (activityResumed) continueBackgroundTrackingStart()
            return
        }

        clearPendingViewingStart()
        guidanceHint.text = terminalError ?: ""
        restorePhase1IfPossible()
    }

    private fun launchPendingViewingTargetIfReady() {
        if (!activityResumed || viewingLaunchRetryScheduled ||
            viewingLaunchAttempts >= ViewingTargetLaunchPolicy.MAX_AUTOMATIC_ATTEMPTS
        ) return
        val service = boundTrackingService ?: return
        val target = service.pendingViewingTargetIfReady() ?: return
        viewingLaunchAttempts++
        val launchResult = launchViewingTarget(target)
        if (launchResult != null) {
            service.acknowledgeViewingTargetLaunched(target, launchResult.packageName)
            guidanceHint.removeCallbacks(viewingLaunchRetry)
            viewingLaunchRetryScheduled = false
            viewingLaunchAttempts = 0
        } else if (!viewingLaunchRetryScheduled &&
            viewingLaunchAttempts < ViewingTargetLaunchPolicy.MAX_AUTOMATIC_ATTEMPTS
        ) {
            viewingLaunchRetryScheduled = true
            guidanceHint.postDelayed(
                viewingLaunchRetry,
                ViewingTargetLaunchPolicy.RETRY_DELAY_MILLIS
            )
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST = 1002
        private const val SERVICE_STOP_POLL_INTERVAL_MILLIS = 50L
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val YOUTUBE_HOME_URL = "https://www.youtube.com/"
        private const val PICTURE_IN_PICTURE_SETTINGS_ACTION =
            "android.settings.PICTURE_IN_PICTURE_SETTINGS"
        private const val TAG = "KunekuneMain"
        private const val STATE_GUIDANCE_PENDING_START = "guidance_pending_start"
        private const val STATE_VIEWING_START_PENDING = "viewing_start_pending"
        private const val STATE_PENDING_ARRIVAL_BEHAVIOR = "pending_arrival_behavior"
        private const val STATE_NOTIFICATION_PERMISSION_DENIED = "notification_permission_denied"
        private const val STATE_USAGE_SETTINGS_OPENED = "usage_settings_opened"
        private const val STATE_OVERLAY_SETTINGS_OPENED = "overlay_settings_opened"
        private const val STATE_PENDING_PICTURE_IN_PICTURE_PACKAGE =
            "pending_picture_in_picture_package"
    }
}

private data class ViewingLaunchResult(val packageName: String)

package com.nacon01.kunekune

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.CheckBox
import android.widget.Toast
import android.view.View
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
    private lateinit var guidanceHint: TextView
    private lateinit var screenContainer: FrameLayout
    private lateinit var guidanceControls: LinearLayout
    private lateinit var bottomNavigation: LinearLayout
    private lateinit var settingsScreenView: View
    private lateinit var homeLatitudeInput: EditText
    private lateinit var homeLongitudeInput: EditText
    private lateinit var homeRadiusInput: EditText
    private lateinit var homeStatusText: TextView
    private lateinit var homeRegistrationText: TextView
    private lateinit var homeProtectionStatusText: TextView
    private lateinit var homeDomainProtectionStatusText: TextView
    private lateinit var homeWarningText: TextView
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
    private var arrivalBehaviorDialog: AlertDialog? = null
    private var destinationSelectionDialog: AlertDialog? = null
    private var grantSelectionDialog: AlertDialog? = null
    private var initialTargetSelectionDialog: AlertDialog? = null
    private var clearingPendingViewingStart = false
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
    private var currentScreen = AppScreen.GUIDANCE
    private var currentAppProtectionStatus = AppProtectionStatus.OUTSIDE_OFF
    private var appProtectionStatusReceived = false
    private var currentDomainProtectionStatus = DomainProtectionStatus.OUTSIDE_OFF
    private var domainProtectionStatusReceived = false
    private var pendingRecordingRouteName: String? = null
    private var pendingRouteId: String? = null
    private var pendingGrantedTargetIds: Set<String> = emptySet()
    private var pendingInitialTargetId: String? = null
    private var pendingSessionGrant: SessionGrant? = null
    private val routeRepository by lazy { RouteRepository(this) }
    private val blockTargetStore by lazy { BlockTargetStore(this) }
    private val homeZonePreferences by lazy { HomeZonePreferences(this) }
    private val homeZoneCoordinator by lazy { HomeZoneRuntimeCoordinator(this) }
    private val homeZoneGeofenceManager by lazy {
        HomeZoneGeofenceManager(this, homeZonePreferences, homeZoneCoordinator)
    }
    private val homeZoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != HomeZoneGeofenceManager.ACTION_STATE_CHANGED) return
            runOnUiThread { onHomeZoneStateChanged() }
        }
    }
    private val appProtectionStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != AppProtectionController.ACTION_STATUS_CHANGED) return
            val status = intent.getStringExtra(AppProtectionController.EXTRA_STATUS)
                ?.let { value -> AppProtectionStatus.entries.firstOrNull { it.name == value } }
                ?: return
            runOnUiThread {
                currentAppProtectionStatus = status
                appProtectionStatusReceived = true
                if (::homeProtectionStatusText.isInitialized) updateHomeZoneScreenStatus()
            }
        }
    }
    private val domainProtectionStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != DomainProtectionController.ACTION_STATUS_CHANGED) return
            val status = intent.getStringExtra(DomainProtectionController.EXTRA_STATUS)
                ?.let { value -> DomainProtectionStatus.entries.firstOrNull { it.name == value } }
                ?: return
            runOnUiThread {
                currentDomainProtectionStatus = status
                domainProtectionStatusReceived = true
                if (::homeDomainProtectionStatusText.isInitialized) updateHomeZoneScreenStatus()
            }
        }
    }
    private val arrivalMessageController = ArrivalMessageController()
    private val arrivalMessageHandler = Handler(Looper.getMainLooper())
    private var pendingArrivalSnapshot: TrackingSnapshot? = null
    private val arrivalMessageRunnable = object : Runnable {
        override fun run() {
            if (arrivalMessageController.onArrived()) {
                scheduleArrivalMessage()
                return
            }
            val pending = pendingArrivalSnapshot
            pendingArrivalSnapshot = null
            if (::guidanceView.isInitialized) {
                guidanceView.update(GuidanceSnapshot(GuidanceState.INACTIVE, null, null, null, false))
            }
            pending?.let { snapshot ->
                if (snapshot.guidance.state == GuidanceState.ARRIVED) {
                    updateControls(snapshot, arrivalMessageVisible = false)
                }
            }
            if (BackgroundTrackingService.currentState == BackgroundTrackingState.ARRIVED) {
                guidanceHint.text = ""
            }
        }
    }
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
                    val routeName = pendingRecordingRouteName
                    if (routeName == null) {
                        trackingManager.stopRecording()
                    } else {
                        val saved = trackingManager.finishRecordingIntoCatalog(routeName)
                        if (saved == null) {
                            guidanceHint.text = "経路を保存できませんでした"
                        } else {
                            pendingRecordingRouteName = null
                            Toast.makeText(this@MainActivity, "経路を登録しました", Toast.LENGTH_SHORT).show()
                            showScreen(AppScreen.ROUTES)
                            rebuildRouteScreen()
                        }
                    }
                } else if (pendingRecordingRouteName != null && !isHomeZoneReady()) {
                    guidanceHint.text = "自宅内を確認できないため、マーカー記録はOFFです"
                    showScreen(AppScreen.HOME)
                } else if (pendingRecordingRouteName != null && trackingManager.startRecording()) {
                    text = "記録終了"
                    isEnabled = true
                } else if (pendingRecordingRouteName == null) {
                    guidanceHint.text = "経路画面から目的地名を入力してください"
                }
            }
        }
        guidanceButton = Button(this).apply {
            text = "誘導開始"
            isEnabled = false
            setOnClickListener {
                if (latestGuidanceState == GuidanceState.GUIDING) {
                    resetArrivalMessage()
                    trackingManager.stopGuidance()
                } else {
                    homeZoneCoordinator.reload()
                    if (homeZonePreferences.get() == null ||
                        !homeZoneGeofenceManager.hasRequiredLocationPermissions() ||
                        homeZoneCoordinator.snapshot().lastKnownInside != true
                    ) {
                        guidanceHint.text = "自宅内を確認できないため、誘導はOFFです"
                        return@setOnClickListener
                    }
                    resetArrivalMessage()
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
        guidanceHint = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(8, 2, 8, 2)
        }
        trackingManager.onSnapshot = { snapshot ->
            debugHud.update(snapshot)
            runOnUiThread {
                if (snapshot.guidance.state == GuidanceState.ARRIVED) {
                    pendingArrivalSnapshot = snapshot
                    if (arrivalMessageController.onArrived()) {
                        guidanceView.update(snapshot.guidance)
                        updateControls(snapshot, arrivalMessageVisible = true)
                        scheduleArrivalMessage()
                    } else {
                        guidanceView.update(snapshot.guidance.copy(state = GuidanceState.INACTIVE))
                        updateControls(snapshot, arrivalMessageVisible = false)
                        arrivalMessageHandler.removeCallbacks(arrivalMessageRunnable)
                    }
                } else {
                    pendingArrivalSnapshot = null
                    arrivalMessageController.onNonArrived()
                    arrivalMessageHandler.removeCallbacks(arrivalMessageRunnable)
                    guidanceView.update(snapshot.guidance)
                    updateControls(snapshot, arrivalMessageVisible = false)
                }
            }
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
        guidanceControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttonRow, LinearLayout.LayoutParams(
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

        val settingsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(18), dp(12), dp(96))
            addView(TextView(this@MainActivity).apply { text = "設定"; textSize = 22f })
            addView(fadeSettingsRow)
            addView(viewingThresholdRow)
            addView(progressRewardRow)
            addView(arrivalFadeRow)
            addView(leaveDestinationFadeRow)
        }
        val settingsScreen = ScrollView(this).apply { addView(settingsContent) }
        settingsScreenView = settingsScreen

        screenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(settingsScreen, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        bottomNavigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(28, 28, 28))
            listOf(
                AppScreen.GUIDANCE to "誘導",
                AppScreen.ROUTES to "経路",
                AppScreen.TARGETS to "対象",
                AppScreen.HOME to "自宅",
                AppScreen.SETTINGS to "設定"
            ).forEach { (screen, label) ->
                addView(Button(this@MainActivity).apply {
                    text = label
                    setOnClickListener { showScreen(screen) }
                }, LinearLayout.LayoutParams(0, dp(56), 1f))
            }
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
            addView(guidanceControls, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                val margin = (resources.displayMetrics.density * 12).toInt()
                setMargins(margin, margin, margin, dp(68))
            })
            addView(screenContainer, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(bottomNavigation, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.BOTTOM
            ))
        }
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                homeZoneStateReceiver,
                IntentFilter(HomeZoneGeofenceManager.ACTION_STATE_CHANGED),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(
                homeZoneStateReceiver,
                IntentFilter(HomeZoneGeofenceManager.ACTION_STATE_CHANGED)
            )
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                appProtectionStatusReceiver,
                IntentFilter(AppProtectionController.ACTION_STATUS_CHANGED),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(
                appProtectionStatusReceiver,
                IntentFilter(AppProtectionController.ACTION_STATUS_CHANGED)
            )
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                domainProtectionStatusReceiver,
                IntentFilter(DomainProtectionController.ACTION_STATUS_CHANGED),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(
                domainProtectionStatusReceiver,
                IntentFilter(DomainProtectionController.ACTION_STATUS_CHANGED)
            )
        }
        updateInterventionSettingsControls()
        restorePendingWorkflowState(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    private fun showScreen(screen: AppScreen) {
        currentScreen = screen
        if (screen == AppScreen.GUIDANCE) {
            screenContainer.visibility = View.GONE
            guidanceControls.visibility = View.VISIBLE
            return
        }
        guidanceControls.visibility = View.GONE
        screenContainer.visibility = View.VISIBLE
        screenContainer.removeAllViews()
        val view = when (screen) {
            AppScreen.ROUTES -> buildRouteScreen()
            AppScreen.TARGETS -> buildTargetScreen()
            AppScreen.HOME -> buildHomeScreen()
            AppScreen.SETTINGS -> settingsScreenView
            AppScreen.GUIDANCE -> error("unreachable")
        }
        screenContainer.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun rebuildRouteScreen() {
        if (currentScreen == AppScreen.ROUTES) showScreen(AppScreen.ROUTES)
    }

    private fun rebuildTargetScreen() {
        if (currentScreen == AppScreen.TARGETS) showScreen(AppScreen.TARGETS)
    }

    private fun buildRouteScreen(): View = ScrollView(this).apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(18), dp(12), dp(96))
            addView(TextView(this@MainActivity).apply { text = "経路"; textSize = 22f })
            addView(Button(this@MainActivity).apply {
                text = "新しい経路を登録"
                setOnClickListener { showRouteNameDialog() }
            })
            val routes = try { routeRepository.list() } catch (exception: Exception) {
                addView(TextView(this@MainActivity).apply {
                    text = "経路を読み込めませんでした: ${exception.message ?: "不明なエラー"}"
                })
                emptyList()
            }
            if (routes.isEmpty()) {
                addView(TextView(this@MainActivity).apply { text = "登録された経路はありません" })
            }
            routes.forEach { route ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(10), 0, dp(6))
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = "${route.name}${if (route.id == trackingManager.selectedRouteIdentifier) "（選択中）" else ""}"
                    textSize = 18f
                })
                row.addView(TextView(this@MainActivity).apply {
                    text = "${route.points.size} 点 / ${"%.1f".format(route.totalDistanceMeters)} m"
                })
                row.addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Button(this@MainActivity).apply {
                        text = "この経路を選択"
                        isEnabled = route.id != trackingManager.selectedRouteIdentifier
                        setOnClickListener {
                            if (trackingManager.selectRoute(route.id)) {
                                Toast.makeText(this@MainActivity, "経路を選択しました", Toast.LENGTH_SHORT).show()
                                rebuildRouteScreen()
                            }
                        }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(Button(this@MainActivity).apply {
                        text = "削除"
                        setOnClickListener { confirmDeleteRoute(route) }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
                addView(row)
            }
        })
    }

    private fun showRouteNameDialog() {
        val input = EditText(this).apply { hint = "目的地名" }
        val dialog = AlertDialog.Builder(this)
            .setTitle("経路を登録")
            .setMessage("マーカーを認識してから経路を記録します")
            .setView(input)
            .setPositiveButton("記録画面へ", null)
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                val duplicate = try {
                    routeRepository.list().any { routeNameKey(it.name) == routeNameKey(name) }
                } catch (_: Exception) { false }
                if (name.isEmpty()) input.error = "目的地名を入力してください"
                else if (duplicate) input.error = "同じ目的地名がすでにあります"
                else {
                    pendingRecordingRouteName = name
                    dialog.dismiss()
                    showScreen(AppScreen.GUIDANCE)
                    guidanceHint.text = "マーカーを認識して記録を開始してください"
                }
            }
        }
        dialog.setOnCancelListener { pendingRecordingRouteName = null }
        dialog.show()
    }

    private fun confirmDeleteRoute(route: DestinationRoute) {
        AlertDialog.Builder(this)
            .setTitle("経路を削除")
            .setMessage("「${route.name}」を削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                try {
                    routeRepository.delete(route.id)
                    if (trackingManager.selectedRouteIdentifier == route.id) {
                        routeRepository.list().firstOrNull()?.let { trackingManager.selectRoute(it.id) }
                            ?: trackingManager.clearSelectedRoute()
                    }
                    rebuildRouteScreen()
                } catch (_: Exception) {
                    guidanceHint.text = "経路を削除できませんでした"
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun buildTargetScreen(): View = ScrollView(this).apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(18), dp(12), dp(96))
            addView(TextView(this@MainActivity).apply { text = "対象"; textSize = 22f })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(this@MainActivity).apply {
                    text = "アプリを追加"
                    setOnClickListener { showAppTargetDialog() }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(this@MainActivity).apply {
                    text = "ドメインを追加"
                    setOnClickListener { showDomainTargetDialog() }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            val selectedIds = blockTargetStore.selectedTargetIds()
            val initialId = blockTargetStore.initialTargetId()
            blockTargetStore.all().forEach { target ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val selected = CheckBox(this@MainActivity).apply {
                        text = target.displayName()
                        isChecked = target.id in selectedIds
                        setOnClickListener {
                            blockTargetStore.setSelected(target.id, isChecked)
                            reconcileAppProtection()
                            rebuildTargetScreen()
                        }
                    }
                    addView(selected, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(RadioButton(this@MainActivity).apply {
                        text = "初回"
                        isChecked = target.id == initialId
                        isEnabled = target.id in selectedIds
                        setOnClickListener {
                            if (isChecked) blockTargetStore.setInitialTargetId(target.id)
                            rebuildTargetScreen()
                        }
                    })
                    addView(Button(this@MainActivity).apply {
                        text = "削除"
                        setOnClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("対象を削除")
                                .setMessage("${target.displayName()}を削除しますか？")
                                .setPositiveButton("削除") { _, _ ->
                                    blockTargetStore.remove(target.id)
                                    reconcileAppProtection()
                                    rebuildTargetScreen()
                                }
                                .setNegativeButton("キャンセル", null)
                                .show()
                        }
                    })
                })
            }
            if (blockTargetStore.all().isEmpty()) {
                addView(TextView(this@MainActivity).apply { text = "対象が登録されていません" })
            }
        })
    }

    private fun showAppTargetDialog() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != packageName }
            .groupBy { it.activityInfo.packageName }
            .map { (_, infos) -> infos.first() }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(this, "追加できるアプリがありません", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        val checked = BooleanArray(labels.size)
        AlertDialog.Builder(this)
            .setTitle("アプリを追加")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("追加") { _, _ ->
                apps.filterIndexed { index, _ -> checked[index] }.forEach { info ->
                    blockTargetStore.add(BlockTarget.app(
                        info.activityInfo.packageName,
                        info.loadLabel(packageManager).toString()
                    ))
                }
                reconcileAppProtection()
                rebuildTargetScreen()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showDomainTargetDialog() {
        val input = EditText(this).apply { hint = "example.com または https://example.com" }
        val includeSubdomains = CheckBox(this).apply { text = "サブドメインも含める" }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(input)
            addView(includeSubdomains)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("ドメインを追加")
            .setView(content)
            .setPositiveButton("追加", null)
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    blockTargetStore.add(BlockTarget.domain(input.text.toString(), includeSubdomains.isChecked))
                    dialog.dismiss()
                    reconcileAppProtection()
                    rebuildTargetScreen()
                } catch (exception: IllegalArgumentException) {
                    input.error = exception.message ?: "入力を確認してください"
                }
            }
        }
        dialog.show()
    }

    private fun buildHomeScreen(): View = ScrollView(this).apply {
        val config = homeZonePreferences.get()
        homeLatitudeInput = EditText(this@MainActivity).apply {
            hint = "緯度"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            config?.latitude?.let { setText(it.toString()) }
        }
        homeLongitudeInput = EditText(this@MainActivity).apply {
            hint = "経度"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            config?.longitude?.let { setText(it.toString()) }
        }
        homeRadiusInput = EditText(this@MainActivity).apply {
            hint = "半径（m）"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            config?.radiusMeters?.let { setText(it.toString()) }
        }
        homeStatusText = TextView(this@MainActivity)
        homeRegistrationText = TextView(this@MainActivity)
        homeProtectionStatusText = TextView(this@MainActivity)
        homeDomainProtectionStatusText = TextView(this@MainActivity)
        homeWarningText = TextView(this@MainActivity).apply {
            setTextColor(Color.YELLOW)
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(18), dp(12), dp(96))
            addView(TextView(this@MainActivity).apply { text = "自宅"; textSize = 22f })
            addView(TextView(this@MainActivity).apply {
                text = "保存した自宅を基準に、マーカーと誘導を有効化します。"
            })
            addView(TextView(this@MainActivity).apply {
                text = "選択したドメインのブロックには、初回のみ下のボタンでAndroid VPN権限を設定します。"
            })
            addView(homeStatusText)
            addView(homeProtectionStatusText)
            addView(homeDomainProtectionStatusText)
            addView(homeRegistrationText)
            addView(homeLatitudeInput); addView(homeLongitudeInput); addView(homeRadiusInput)
            addView(homeWarningText)
            addView(Button(this@MainActivity).apply {
                text = "正確な位置情報を許可"
                setOnClickListener { requestForegroundLocationPermission() }
            })
            addView(Button(this@MainActivity).apply {
                text = "常に位置情報を許可"
                setOnClickListener { requestBackgroundLocationPermission() }
            })
            addView(Button(this@MainActivity).apply {
                text = "利用状況へのアクセスを設定"
                setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            })
            addView(Button(this@MainActivity).apply {
                text = "他のアプリの上に表示を設定"
                setOnClickListener {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                }
            })
            addView(Button(this@MainActivity).apply {
                text = "VPN権限を設定（ドメイン保護）"
                setOnClickListener { requestDomainVpnPermission() }
            })
            addView(Button(this@MainActivity).apply {
                text = "現在地を自宅に設定"
                setOnClickListener {
                    if (!homeZoneGeofenceManager.hasForegroundPrecisePermission()) {
                        requestForegroundLocationPermission()
                        return@setOnClickListener
                    }
                    homeZoneGeofenceManager.requestCurrentLocation { result ->
                        val sample = result.sample
                        if (sample == null) {
                            homeWarningText.text = result.errorMessage ?: "現在地を取得できませんでした。"
                            homeWarningText.setTextColor(Color.RED)
                        } else {
                            try {
                                saveHomeZoneConfig(
                                    HomeZoneConfig(
                                        latitude = sample.latitude,
                                        longitude = sample.longitude,
                                        radiusMeters = homeRadiusInput.text.toString()
                                            .trim()
                                            .ifEmpty { "100" }
                                            .toDouble()
                                    )
                                )
                            } catch (exception: Exception) {
                                homeWarningText.text = exception.message ?: "入力を確認してください"
                                homeWarningText.setTextColor(Color.RED)
                            }
                        }
                    }
                }
            })
            addView(Button(this@MainActivity).apply {
                text = "保存"
                setOnClickListener {
                    try {
                        saveHomeZoneConfig(HomeZoneConfig(
                            homeLatitudeInput.text.toString().trim().toDouble(),
                            homeLongitudeInput.text.toString().trim().toDouble(),
                            homeRadiusInput.text.toString().trim().ifEmpty { "100" }.toDouble()
                        ))
                    } catch (exception: Exception) {
                        homeWarningText.text = exception.message ?: "入力を確認してください"
                        homeWarningText.setTextColor(Color.RED)
                    }
                }
            })
        })
        updateHomeZoneScreenStatus()
    }

    private fun saveHomeZoneConfig(config: HomeZoneConfig) {
        homeZonePreferences.save(config)
        homeLatitudeInput.setText(config.latitude.toString())
        homeLongitudeInput.setText(config.longitude.toString())
        homeRadiusInput.setText(config.radiusMeters.toString())
        homeWarningText.setTextColor(Color.YELLOW)
        homeWarningText.text = if (config.hasSmallRadiusWarning) {
            "半径が100m未満です。一般的な精度では100m以上が推奨されます。"
        } else "保存しました。ジオフェンスを登録中…"
        homeZoneGeofenceManager.registerOrUpdate { result ->
            updateHomeZoneScreenStatus()
            homeWarningText.text = result.message
            homeWarningText.setTextColor(if (result.success) Color.YELLOW else Color.RED)
        }
    }

    private fun requestForegroundLocationPermission() {
        if (homeZoneGeofenceManager.hasForegroundPrecisePermission()) {
            requestBackgroundLocationPermission()
            return
        }
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            HOME_FOREGROUND_LOCATION_PERMISSION_REQUEST
        )
    }

    private fun requestBackgroundLocationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            updateHomeZoneScreenStatus()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("常に位置情報を許可")
                .setMessage("自宅の出入りを画面外でも判定するため、アプリの位置情報設定で「常に許可」を選択してください。")
                .setPositiveButton("設定を開く") { _, _ ->
                    startActivity(Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    ))
                }
                .setNegativeButton("キャンセル", null)
                .show()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                HOME_BACKGROUND_LOCATION_PERMISSION_REQUEST
            )
        }
    }

    private fun updateHomeZoneScreenStatus() {
        if (!::homeStatusText.isInitialized) return
        val snapshot = homeZoneCoordinator.reload()
        if (!appProtectionStatusReceived) {
            val store = blockTargetStore
            val selectedIds = store.selectedTargetIds()
            currentAppProtectionStatus = AppProtectionStatusPolicy.resolve(
                snapshot = snapshot,
                selectedAppTargetCount = store.all().count {
                    it.id in selectedIds && it is BlockTarget.App
                },
                usageAccessGranted = UsageStatsForegroundReader.hasUsageAccess(this),
                overlayPermissionGranted = Settings.canDrawOverlays(this)
            )
        }
        homeStatusText.text = when {
            homeZonePreferences.get() == null -> "保存済み自宅: なし"
            snapshot.lastKnownInside == true -> "実行状態: 自宅内（ロック中）"
            snapshot.unknownWarning && snapshot.lastKnownInside == null -> "実行状態: 不明（履歴なし・OFF）"
            snapshot.unknownWarning -> "実行状態: 不明（直前の判定を維持）"
            else -> "実行状態: 自宅外（OFF）"
        }
        homeProtectionStatusText.text = "アプリ保護: ${currentAppProtectionStatus.displayName()}"
        homeDomainProtectionStatusText.text =
            "ドメイン保護: ${currentDomainProtectionStatus.displayName()}"
        homeRegistrationText.text = "ジオフェンス: ${when (homeZoneGeofenceManager.registrationStatus) {
            HomeZoneGeofenceRegistrationStatus.REGISTERED -> "登録済み"
            HomeZoneGeofenceRegistrationStatus.NOT_REGISTERED -> "未登録"
            HomeZoneGeofenceRegistrationStatus.FAILED -> "登録失敗"
            HomeZoneGeofenceRegistrationStatus.UNKNOWN -> "確認中"
        }}"
        val config = homeZonePreferences.get()
        homeWarningText.text = when {
            config?.hasSmallRadiusWarning == true -> "半径が100m未満です。一般的な精度では100m以上が推奨されます。"
            !homeZoneGeofenceManager.hasForegroundPrecisePermission() -> "正確な位置情報の権限が必要です。"
            !homeZoneGeofenceManager.hasRequiredLocationPermissions() -> "バックグラウンド位置情報（常に許可）が必要です。"
            else -> ""
        }
        homeWarningText.setTextColor(Color.YELLOW)
    }

    private fun BlockTarget.displayName(): String = when (this) {
        is BlockTarget.App -> label
        is BlockTarget.Domain -> "$host${if (includeSubdomains) "（サブドメイン含む）" else ""}"
    }

    private fun AppProtectionStatus.displayName(): String = when (this) {
        AppProtectionStatus.ACTIVE -> "有効"
        AppProtectionStatus.OUTSIDE_OFF -> "自宅外・OFF"
        AppProtectionStatus.NO_SELECTED_APP_TARGET -> "選択中のアプリ対象なし"
        AppProtectionStatus.USAGE_ACCESS_MISSING -> "利用状況へのアクセスが必要"
        AppProtectionStatus.OVERLAY_PERMISSION_MISSING -> "表示権限が必要"
        AppProtectionStatus.ERROR -> "エラー"
    }

    private fun DomainProtectionStatus.displayName(): String = when (this) {
        DomainProtectionStatus.ACTIVE -> "有効"
        DomainProtectionStatus.OUTSIDE_OFF -> "自宅外・OFF"
        DomainProtectionStatus.NO_SELECTED_DOMAIN_TARGET -> "選択中のドメイン対象なし"
        DomainProtectionStatus.VPN_PERMISSION_REQUIRED -> "VPN権限が必要"
        DomainProtectionStatus.STARTING -> "起動中"
        DomainProtectionStatus.ERROR -> "エラー"
    }

    private fun reconcileAppProtection() {
        val result = AppProtectionController.reconcile(this)
        currentAppProtectionStatus = result.status
        appProtectionStatusReceived = true
        val domainResult = DomainProtectionController.reconcile(this)
        currentDomainProtectionStatus = domainResult.status
        domainProtectionStatusReceived = true
        if (::homeProtectionStatusText.isInitialized) updateHomeZoneScreenStatus()
    }

    private fun requestDomainVpnPermission() {
        val prepareIntent = try {
            VpnService.prepare(this)
        } catch (_: Exception) {
            currentDomainProtectionStatus = DomainProtectionStatus.ERROR
            domainProtectionStatusReceived = true
            DomainProtectionController.publishStatus(this, DomainProtectionStatus.ERROR)
            if (::homeDomainProtectionStatusText.isInitialized) updateHomeZoneScreenStatus()
            return
        }
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, VPN_PERMISSION_REQUEST)
        } else {
            val result = DomainProtectionController.reconcile(this)
            currentDomainProtectionStatus = result.status
            domainProtectionStatusReceived = true
            updateHomeZoneScreenStatus()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        activityResumed = true
        viewingLaunchAttempts = 0
        reconcileAppProtection()
        if (homeZonePreferences.get() != null &&
            homeZoneGeofenceManager.hasRequiredLocationPermissions()
        ) {
            homeZoneGeofenceManager.registerOrUpdate {
                if (::homeStatusText.isInitialized) updateHomeZoneScreenStatus()
            }
        } else if (::homeStatusText.isInitialized && currentScreen == AppScreen.HOME) {
            updateHomeZoneScreenStatus()
        }
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
        outState.putBoolean(STATE_PENDING_WORKFLOW_PRESENT, hasPendingWorkflow())
        outState.putString(STATE_PENDING_ROUTE_ID, pendingRouteId)
        outState.putStringArrayList(
            STATE_PENDING_GRANTED_TARGET_IDS,
            ArrayList(pendingGrantedTargetIds.sorted())
        )
        outState.putString(STATE_PENDING_INITIAL_TARGET_ID, pendingInitialTargetId)
        if (pendingSessionGrant != null) {
            val grant = pendingSessionGrant!!
            outState.putBoolean(STATE_PENDING_SESSION_GRANT_PRESENT, true)
            outState.putLong(STATE_PENDING_SESSION_GRANT_VISIT_GENERATION, grant.visitGeneration)
            outState.putString(STATE_PENDING_SESSION_GRANT_ROUTE_ID, grant.routeId)
            outState.putStringArrayList(
                STATE_PENDING_SESSION_GRANT_TARGET_IDS,
                ArrayList(grant.grantedTargetIds.sorted())
            )
            outState.putString(
                STATE_PENDING_SESSION_GRANT_INITIAL_TARGET_ID,
                grant.initialTargetId
            )
        } else {
            outState.putBoolean(STATE_PENDING_SESSION_GRANT_PRESENT, false)
        }
        outState.putString(STATE_PENDING_RECORDING_ROUTE_NAME, pendingRecordingRouteName)
        outState.putString(STATE_CURRENT_SCREEN, currentScreen.name)
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
        try { unregisterReceiver(homeZoneStateReceiver) } catch (_: IllegalArgumentException) { }
        try { unregisterReceiver(appProtectionStatusReceiver) } catch (_: IllegalArgumentException) { }
        try { unregisterReceiver(domainProtectionStatusReceiver) } catch (_: IllegalArgumentException) { }
        if (::guidanceHint.isInitialized) guidanceHint.removeCallbacks(viewingLaunchRetry)
        if (::guidanceHint.isInitialized) guidanceHint.removeCallbacks(guidanceStopCompletion)
        resetArrivalMessage()
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
        if (requestCode == HOME_FOREGROUND_LOCATION_PERMISSION_REQUEST ||
            requestCode == HOME_BACKGROUND_LOCATION_PERMISSION_REQUEST
        ) {
            if (requestCode == HOME_FOREGROUND_LOCATION_PERMISSION_REQUEST &&
                grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            ) {
                requestBackgroundLocationPermission()
            }
            updateHomeZoneScreenStatus()
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

    @Deprecated("Android VPN consent uses the activity result contract on this legacy activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_PERMISSION_REQUEST) return
        if (resultCode == RESULT_OK) {
            val result = DomainProtectionController.reconcile(this)
            currentDomainProtectionStatus = result.status
            domainProtectionStatusReceived = true
        } else {
            currentDomainProtectionStatus = DomainProtectionStatus.VPN_PERMISSION_REQUIRED
            domainProtectionStatusReceived = true
            DomainProtectionController.publishStatus(
                this, DomainProtectionStatus.VPN_PERMISSION_REQUIRED
            )
        }
        if (::homeDomainProtectionStatusText.isInitialized) updateHomeZoneScreenStatus()
    }

    private fun onDepartureButtonClicked() {
        if (activeGuidanceService && BackgroundTrackingService.blocksPhase1Camera()) {
            guidancePendingStart = false
            clearPendingViewingStart()
            requestBackgroundServiceStop()
            return
        }
        if (homeZonePreferences.get() == null) {
            guidanceHint.text = "自宅を保存してから開始してください"
            showScreen(AppScreen.HOME)
            return
        }
        if (!homeZoneGeofenceManager.hasRequiredLocationPermissions()) {
            guidanceHint.text = "正確な位置情報と常に許可の権限が必要です"
            showScreen(AppScreen.HOME)
            return
        }
        homeZoneCoordinator.reload()
        if (homeZoneCoordinator.snapshot().lastKnownInside != true) {
            guidanceHint.text = if (homeZoneCoordinator.snapshot().unknownWarning) {
                "自宅内か確認できないため、誘導はOFFです"
            } else "自宅外のため、誘導はOFFです"
            return
        }
        if (!hasValidSavedRoute()) {
            guidanceHint.text = "有効な保存済み経路がないため開始できません"
            return
        }
        if (!hasValidTargetSelection()) {
            guidanceHint.text = "対象画面で初回起動対象を選択してください"
            showScreen(AppScreen.TARGETS)
            return
        }
        usageSettingsOpened = false
        overlaySettingsOpened = false
        pendingPictureInPicturePackage = null
        viewingStartPending = true
        pendingArrivalBehavior = null
        val awaiting = homeZoneCoordinator.awaitMarker()
        if (!awaiting.accepted) {
            clearPendingViewingStart()
            guidanceHint.text = awaiting.reason ?: "マーカー待機を開始できませんでした"
            return
        }
        departureButton.text = "マーカー待機中"
        showArrivalBehaviorDialog()
    }

    private fun requestGuidanceStart() {
        homeZoneCoordinator.reload()
        if (homeZonePreferences.get() == null ||
            !homeZoneGeofenceManager.hasRequiredLocationPermissions() ||
            homeZoneCoordinator.snapshot().lastKnownInside != true
        ) {
            clearPendingViewingStart()
            guidanceHint.text = "自宅内を確認できないため、誘導はOFFです"
            return
        }
        if (!hasValidSavedRoute()) {
            guidanceHint.text = "有効な保存済み経路がないため離脱できません"
            return
        }

        if (pendingArrivalBehavior == null) return

        if (pendingSessionGrant == null) {
            showDestinationSelectionDialog()
            return
        }

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
        return trackingManager.hasValidSelectedRoute()
    }

    private fun isHomeZoneReady(): Boolean {
        homeZoneCoordinator.reload()
        return homeZonePreferences.get() != null &&
            homeZoneGeofenceManager.hasRequiredLocationPermissions() &&
            homeZoneCoordinator.snapshot().lastKnownInside == true
    }

    private fun hasValidTargetSelection(): Boolean {
        val selected = blockTargetStore.selectedTargetIds()
        val initial = blockTargetStore.initialTargetId()
        return selected.isNotEmpty() && initial != null && initial in selected &&
            selected.all { blockTargetStore.get(it) != null }
    }

    private fun onHomeZoneStateChanged() {
        reconcileAppProtection()
        val before = homeZoneCoordinator.snapshot()
        val snapshot = homeZoneCoordinator.reload()
        if (snapshot.lastKnownInside == false && before.lastKnownInside != false) {
            clearPendingViewingStart()
            if (::trackingManager.isInitialized) {
                trackingManager.stopGuidance()
                if (trackingManager.isRecording) trackingManager.stopRecording()
            }
            if (BackgroundTrackingService.blocksPhase1Camera()) {
                stopService(Intent(this, BackgroundTrackingService::class.java))
                activeGuidanceService = false
            }
            if (::guidanceHint.isInitialized) guidanceHint.text = "自宅外・OFF（コンテンツは制限されません）"
            if (::departureButton.isInitialized) departureButton.text = "位置合わせして視聴開始"
        }
        if (currentScreen == AppScreen.HOME) updateHomeZoneScreenStatus()
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
    }

    private fun hasPendingWorkflow(): Boolean =
        guidancePendingStart || viewingStartPending || pendingArrivalBehavior != null ||
            pendingPictureInPicturePackage != null || pendingRouteId != null ||
            pendingGrantedTargetIds.isNotEmpty() || pendingInitialTargetId != null ||
            pendingSessionGrant != null

    private fun restorePendingWorkflowState(savedInstanceState: Bundle?) {
        val restoredScreen = try {
            savedInstanceState?.getString(STATE_CURRENT_SCREEN)
                ?.let { value -> AppScreen.entries.firstOrNull { it.name == value } }
        } catch (_: Exception) {
            null
        } ?: AppScreen.GUIDANCE

        var recordingStateInvalid = false
        val restoredRecordingRouteName = try {
            savedInstanceState?.getString(STATE_PENDING_RECORDING_ROUTE_NAME)
        } catch (_: Exception) {
            recordingStateInvalid = true
            null
        }
        pendingRecordingRouteName = restoredRecordingRouteName?.takeIf { it.isNotBlank() }
        if (restoredRecordingRouteName != null && pendingRecordingRouteName == null) {
            recordingStateInvalid = true
        }

        var pendingStateInvalid = recordingStateInvalid
        val restoredArrivalBehavior = try {
            savedInstanceState?.getString(STATE_PENDING_ARRIVAL_BEHAVIOR)
        } catch (_: Exception) {
            pendingStateInvalid = true
            null
        }
        if (restoredArrivalBehavior != null && pendingArrivalBehavior == null) {
            pendingStateInvalid = true
        }
        val savedPendingState = savedInstanceState?.getBoolean(
            STATE_PENDING_WORKFLOW_PRESENT,
            false
        ) == true
        val hasPendingStateFields = savedInstanceState?.let { state ->
            listOf(
                STATE_PENDING_ROUTE_ID,
                STATE_PENDING_GRANTED_TARGET_IDS,
                STATE_PENDING_INITIAL_TARGET_ID,
                STATE_PENDING_SESSION_GRANT_PRESENT,
                STATE_PENDING_SESSION_GRANT_VISIT_GENERATION,
                STATE_PENDING_SESSION_GRANT_ROUTE_ID,
                STATE_PENDING_SESSION_GRANT_TARGET_IDS,
                STATE_PENDING_SESSION_GRANT_INITIAL_TARGET_ID
            ).any(state::containsKey)
        } == true

        if (savedPendingState || hasPendingStateFields) {
            try {
                pendingRouteId = savedInstanceState?.getString(STATE_PENDING_ROUTE_ID)
                val restoredGrantedTargetIdList = savedInstanceState
                    ?.getStringArrayList(STATE_PENDING_GRANTED_TARGET_IDS)
                    ?: arrayListOf()
                pendingGrantedTargetIds = restoredGrantedTargetIdList.toSet()
                require(restoredGrantedTargetIdList.size == pendingGrantedTargetIds.size)
                pendingInitialTargetId = savedInstanceState
                    ?.getString(STATE_PENDING_INITIAL_TARGET_ID)

                val hasGrant = savedInstanceState?.getBoolean(
                    STATE_PENDING_SESSION_GRANT_PRESENT,
                    false
                ) == true
                if (!hasGrant) {
                    require(
                        listOf(
                            STATE_PENDING_SESSION_GRANT_VISIT_GENERATION,
                            STATE_PENDING_SESSION_GRANT_ROUTE_ID,
                            STATE_PENDING_SESSION_GRANT_TARGET_IDS,
                            STATE_PENDING_SESSION_GRANT_INITIAL_TARGET_ID
                        ).none { savedInstanceState?.containsKey(it) == true }
                    )
                }
                pendingSessionGrant = if (hasGrant) {
                    val state = checkNotNull(savedInstanceState)
                    require(state.containsKey(STATE_PENDING_SESSION_GRANT_VISIT_GENERATION))
                    val grant = SessionGrantFactory.create(
                        visitGeneration = state.getLong(
                            STATE_PENDING_SESSION_GRANT_VISIT_GENERATION,
                            -1L
                        ),
                        routeId = requireNotNull(
                            state.getString(STATE_PENDING_SESSION_GRANT_ROUTE_ID)
                        ),
                        configuredSelectedTargetIds = blockTargetStore.selectedTargetIds(),
                        grantedTargetIds = requireNotNull(
                            state.getStringArrayList(STATE_PENDING_SESSION_GRANT_TARGET_IDS)
                        ).also { targetIds ->
                            require(targetIds.size == targetIds.toSet().size)
                        }.toSet(),
                        initialTargetId = requireNotNull(
                            state.getString(STATE_PENDING_SESSION_GRANT_INITIAL_TARGET_ID)
                        )
                    )
                    require(pendingRouteId == grant.routeId)
                    require(pendingGrantedTargetIds == grant.grantedTargetIds)
                    require(pendingInitialTargetId == grant.initialTargetId)
                    require(routeRepository.get(grant.routeId) != null)
                    require(trackingManager.selectRoute(grant.routeId))
                    require(trackingManager.hasValidSelectedRoute())
                    grant
                } else {
                    require(pendingRouteId == null || routeRepository.get(pendingRouteId!!) != null)
                    pendingRouteId?.let { routeId ->
                        require(trackingManager.selectRoute(routeId))
                        require(trackingManager.hasValidSelectedRoute())
                    }
                    require(pendingRouteId != null || pendingGrantedTargetIds.isEmpty())
                    require(
                        pendingGrantedTargetIds.all { targetId ->
                            targetId in blockTargetStore.selectedTargetIds() &&
                                blockTargetStore.get(targetId) != null
                        }
                    )
                    require(
                        pendingInitialTargetId == null ||
                            pendingInitialTargetId in pendingGrantedTargetIds
                    )
                    null
                }
                require(!guidancePendingStart || pendingSessionGrant != null)
                require(pendingPictureInPicturePackage == null || pendingSessionGrant != null)
            } catch (_: Exception) {
                pendingStateInvalid = true
            }
        } else if (guidancePendingStart || pendingPictureInPicturePackage != null) {
            pendingStateInvalid = true
        }

        if (pendingStateInvalid) clearPendingViewingStart()
        showScreen(
            if (pendingRecordingRouteName != null) AppScreen.GUIDANCE else restoredScreen
        )
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
                pendingSessionGrant?.let { grant -> SessionGrantExtras.putInto(this, grant) }
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
        if (startGuidance) {
            val started = pendingSessionGrant?.let(homeZoneCoordinator::guidanceStarted)
            if (started == null || !started.accepted) {
                clearPendingViewingStart()
                guidanceHint.text = started?.reason ?: "誘導状態を開始できませんでした"
                stopService(Intent(this, BackgroundTrackingService::class.java))
                activeGuidanceService = false
                return
            }
        }
        bindTrackingService()
        if (startGuidance) {
            departureButton.text = "マーカー確認中"
            guidanceHint.text = "マーカーを映したままお待ちください"
        }
    }

    private fun updateControls(
        snapshot: TrackingSnapshot,
        arrivalMessageVisible: Boolean = false
    ) {
        latestGuidanceState = snapshot.guidance.state
        val markerRecognized = snapshot.marker.state == MarkerDetectionState.TRACKING
        latestMarkerRecognized = markerRecognized
        if (viewingStartPending && markerRecognized && !BackgroundTrackingService.blocksPhase1Camera()) {
            val found = homeZoneCoordinator.markerFound()
            if (!found.accepted) {
                clearPendingViewingStart()
                guidanceHint.text = found.reason ?: "マーカー待機を解除しました"
                return
            }
            viewingStartPending = false
            requestGuidanceStart()
            return
        }
        if (snapshot.recording.isRecording) {
            recordButton.text = "記録終了"
            recordButton.isEnabled = true
        } else {
            recordButton.text = when {
                pendingRecordingRouteName == null -> "経路画面から登録"
                markerRecognized -> "記録開始"
                else -> "マーカーを映してください"
            }
            recordButton.isEnabled = markerRecognized && pendingRecordingRouteName != null
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
                snapshot.guidance.state == GuidanceState.ARRIVED && arrivalMessageVisible -> "到着しました"
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

    private fun showDestinationSelectionDialog() {
        val routes = try { routeRepository.list() } catch (_: Exception) { emptyList() }
        if (routes.isEmpty()) {
            clearPendingViewingStart()
            guidanceHint.text = "登録された経路がありません"
            return
        }
        val checked = routes.indexOfFirst { it.id == trackingManager.selectedRouteIdentifier }
            .coerceAtLeast(0)
        var chosen = checked
        val dialog = AlertDialog.Builder(this)
            .setTitle("目的地を選択")
            .setSingleChoiceItems(routes.map { it.name }.toTypedArray(), checked) { _, which ->
                chosen = which
            }
            .setPositiveButton("次へ") { _, _ ->
                val route = routes[chosen]
                if (!trackingManager.selectRoute(route.id)) {
                    clearPendingViewingStart()
                    guidanceHint.text = "選択した経路を読み込めませんでした"
                } else {
                    val selected = homeZoneCoordinator.destinationSelected()
                    if (!selected.accepted) {
                        clearPendingViewingStart()
                        guidanceHint.text = selected.reason ?: "目的地選択を開始できませんでした"
                    } else {
                        pendingRouteId = route.id
                        showGrantSelectionDialog()
                    }
                }
            }
            .setNegativeButton("キャンセル") { _, _ -> clearPendingViewingStart() }
            .create()
        destinationSelectionDialog = dialog
        dialog.setOnDismissListener {
            if (destinationSelectionDialog === dialog) destinationSelectionDialog = null
        }
        dialog.setOnCancelListener {
            if (!clearingPendingViewingStart) clearPendingViewingStart()
        }
        dialog.show()
    }

    private fun showGrantSelectionDialog() {
        val targets = blockTargetStore.all().filter {
            it.id in blockTargetStore.selectedTargetIds()
        }
        if (targets.isEmpty()) {
            clearPendingViewingStart()
            guidanceHint.text = "対象画面で対象を選択してください"
            return
        }
        val checked = BooleanArray(targets.size) { true }
        val dialog = AlertDialog.Builder(this)
            .setTitle("今回許可する対象")
            .setMultiChoiceItems(
                targets.map { it.displayName() }.toTypedArray(), checked
            ) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("次へ") { _, _ ->
                val selected = targets.filterIndexed { index, _ -> checked[index] }.map { it.id }.toSet()
                if (selected.isEmpty()) {
                    guidanceHint.text = "少なくとも1つ選択してください"
                    showGrantSelectionDialog()
                } else {
                    pendingGrantedTargetIds = selected
                    if (selected.size == 1) {
                        finishTargetSelection(selected.single())
                    } else {
                        showInitialTargetSelectionDialog(targets.filter { it.id in selected })
                    }
                }
            }
            .setNegativeButton("キャンセル") { _, _ -> clearPendingViewingStart() }
            .create()
        grantSelectionDialog = dialog
        dialog.setOnDismissListener {
            if (grantSelectionDialog === dialog) grantSelectionDialog = null
        }
        dialog.setOnCancelListener {
            if (!clearingPendingViewingStart) clearPendingViewingStart()
        }
        dialog.show()
    }

    private fun showInitialTargetSelectionDialog(targets: List<BlockTarget>) {
        var chosen = targets.indexOfFirst { it.id == blockTargetStore.initialTargetId() }
            .coerceAtLeast(0)
        val dialog = AlertDialog.Builder(this)
            .setTitle("初回に起動する対象")
            .setSingleChoiceItems(targets.map { it.displayName() }.toTypedArray(), chosen) { _, which ->
                chosen = which
            }
            .setPositiveButton("開始") { _, _ -> finishTargetSelection(targets[chosen].id) }
            .setNegativeButton("キャンセル") { _, _ -> clearPendingViewingStart() }
            .create()
        initialTargetSelectionDialog = dialog
        dialog.setOnDismissListener {
            if (initialTargetSelectionDialog === dialog) initialTargetSelectionDialog = null
        }
        dialog.setOnCancelListener {
            if (!clearingPendingViewingStart) clearPendingViewingStart()
        }
        dialog.show()
    }

    private fun finishTargetSelection(initialTargetId: String) {
        val routeId = pendingRouteId
        if (routeId == null || pendingGrantedTargetIds.isEmpty()) {
            clearPendingViewingStart()
            return
        }
        val grant = try {
            SessionGrantFactory.create(
                visitGeneration = homeZoneCoordinator.snapshot().visitGeneration,
                routeId = routeId,
                configuredSelectedTargetIds = blockTargetStore.selectedTargetIds(),
                grantedTargetIds = pendingGrantedTargetIds,
                initialTargetId = initialTargetId
            )
        } catch (_: IllegalArgumentException) {
            clearPendingViewingStart()
            guidanceHint.text = "対象の選択が無効です"
            return
        }
        val selected = homeZoneCoordinator.selectTargets(grant)
        if (!selected.accepted) {
            clearPendingViewingStart()
            guidanceHint.text = selected.reason ?: "対象選択を開始できませんでした"
            return
        }
        pendingInitialTargetId = initialTargetId
        pendingSessionGrant = grant
        reconcileAppProtection()
        requestGuidanceStart()
    }

    private fun showArrivalBehaviorDialog() {
        if (arrivalDialogShown || !viewingStartPending || pendingArrivalBehavior != null || isFinishing) return
        arrivalDialogShown = true
        val dialog = AlertDialog.Builder(this)
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
                if (!clearingPendingViewingStart) clearPendingViewingStart()
            }
            .create()
        arrivalBehaviorDialog = dialog
        dialog.setOnDismissListener {
            arrivalDialogShown = false
            if (arrivalBehaviorDialog === dialog) arrivalBehaviorDialog = null
        }
        dialog.show()
    }

    private fun clearPendingViewingStart() {
        clearingPendingViewingStart = true
        try {
            homeZoneCoordinator.resetWorkflow()
            viewingStartPending = false
            guidancePendingStart = false
            pendingArrivalBehavior = null
            usageSettingsOpened = false
            overlaySettingsOpened = false
            pendingPictureInPicturePackage = null
            pendingRouteId = null
            pendingGrantedTargetIds = emptySet()
            pendingInitialTargetId = null
            pendingSessionGrant = null
            arrivalDialogShown = false
            arrivalBehaviorDialog?.dismiss()
            destinationSelectionDialog?.dismiss()
            grantSelectionDialog?.dismiss()
            initialTargetSelectionDialog?.dismiss()
            departureButton.text = "位置合わせして視聴開始"
        } finally {
            clearingPendingViewingStart = false
        }
    }

    private fun preferredViewingPackage(): String? {
        pendingSessionGrant?.initialTargetId?.let { id ->
            blockTargetStore.get(id)?.let { target ->
                return resolveBlockTargetPackage(target)
            }
        }
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

    private fun launchBlockTarget(target: BlockTarget): ViewingLaunchResult? {
        val intent = when (target) {
            is BlockTarget.App -> packageManager.getLaunchIntentForPackage(target.packageName)
                ?: run {
                    guidanceHint.text = "${target.label}を起動できませんでした"
                    return null
                }
            is BlockTarget.Domain -> Intent(Intent.ACTION_VIEW, Uri.parse(target.launchUrl)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        }
        return tryStartViewingIntent(intent)?.let(::ViewingLaunchResult)
    }

    private fun resolveBlockTargetPackage(target: BlockTarget): String? {
        val intent = when (target) {
            is BlockTarget.App -> packageManager.getLaunchIntentForPackage(target.packageName)
            is BlockTarget.Domain -> Intent(Intent.ACTION_VIEW, Uri.parse(target.launchUrl)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        } ?: return null
        return packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    private fun tryStartViewingIntent(intent: Intent): String? {
        val targetPackage = intent.`package` ?: intent.component?.packageName
            ?: packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
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
                resetArrivalMessage()
                if (activeGuidanceService) {
                    departureButton.text = "誘導停止"
                    guidanceHint.text = "位置合わせ完了"
                    launchPendingViewingTargetIfReady()
                }
            }
            BackgroundTrackingState.TRACKING_LOST -> guidanceHint.text = "位置を復帰中"
            BackgroundTrackingState.ARRIVED -> {
                if (arrivalMessageController.onArrived()) {
                    guidanceHint.text = "到着しました"
                    scheduleArrivalMessage()
                } else {
                    arrivalMessageHandler.removeCallbacks(arrivalMessageRunnable)
                    guidanceHint.text = ""
                }
            }
            BackgroundTrackingState.FAILED -> {
                pendingTerminalServiceError = boundTrackingService?.terminalFailureMessage()
                guidanceHint.text = pendingTerminalServiceError ?: "誘導を安全停止しました"
            }
            BackgroundTrackingState.STOPPING -> {
                resetArrivalMessage()
                pendingTerminalServiceError = pendingTerminalServiceError ?:
                    boundTrackingService?.terminalFailureMessage()
                awaitingServiceStart = false
                unbindTrackingService()
                activeGuidanceService = false
                guidanceHint.text = "カメラを解放中"
                scheduleGuidanceStopCompletion()
            }
            BackgroundTrackingState.IDLE -> {
                resetArrivalMessage()
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

    private fun scheduleArrivalMessage() {
        val deadline = arrivalMessageController.deadlineMonotonicMillis() ?: return
        val remaining = (deadline - System.nanoTime() / 1_000_000L).coerceAtLeast(0L)
        arrivalMessageHandler.removeCallbacks(arrivalMessageRunnable)
        arrivalMessageHandler.postDelayed(arrivalMessageRunnable, remaining)
    }

    private fun resetArrivalMessage() {
        arrivalMessageHandler.removeCallbacks(arrivalMessageRunnable)
        pendingArrivalSnapshot = null
        arrivalMessageController.reset()
        if (::guidanceView.isInitialized) {
            guidanceView.update(GuidanceSnapshot(GuidanceState.INACTIVE, null, null, null, false))
        }
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
        val targetId = service.pendingViewingTargetIdIfReady() ?: return
        viewingLaunchAttempts++
        val launchResult = blockTargetStore.get(targetId)?.let(::launchBlockTarget)
            ?: service.pendingViewingTargetIfReady()?.let(::launchViewingTarget)
        if (launchResult != null) {
            service.acknowledgeViewingTargetIdLaunched(targetId, launchResult.packageName)
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
        private const val HOME_FOREGROUND_LOCATION_PERMISSION_REQUEST = 1003
        private const val HOME_BACKGROUND_LOCATION_PERMISSION_REQUEST = 1004
        private const val VPN_PERMISSION_REQUEST = 1005
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
        private const val STATE_PENDING_WORKFLOW_PRESENT = "pending_workflow_present"
        private const val STATE_PENDING_ROUTE_ID = "pending_route_id"
        private const val STATE_PENDING_GRANTED_TARGET_IDS = "pending_granted_target_ids"
        private const val STATE_PENDING_INITIAL_TARGET_ID = "pending_initial_target_id"
        private const val STATE_PENDING_SESSION_GRANT_PRESENT =
            "pending_session_grant_present"
        private const val STATE_PENDING_SESSION_GRANT_VISIT_GENERATION =
            "pending_session_grant_visit_generation"
        private const val STATE_PENDING_SESSION_GRANT_ROUTE_ID =
            "pending_session_grant_route_id"
        private const val STATE_PENDING_SESSION_GRANT_TARGET_IDS =
            "pending_session_grant_target_ids"
        private const val STATE_PENDING_SESSION_GRANT_INITIAL_TARGET_ID =
            "pending_session_grant_initial_target_id"
        private const val STATE_PENDING_RECORDING_ROUTE_NAME =
            "pending_recording_route_name"
        private const val STATE_CURRENT_SCREEN = "current_screen"
    }
}

private enum class AppScreen { GUIDANCE, ROUTES, TARGETS, HOME, SETTINGS }

private data class ViewingLaunchResult(val packageName: String)

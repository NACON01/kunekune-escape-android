package com.nacon01.kunekune

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log

/** Dedicated special-use FGS that protects selected app targets while the user is home. */
class AppProtectionService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var overlay: AppProtectionOverlay
    private lateinit var usageReader: UsageStatsForegroundReader
    private var foregroundStarted = false
    private var pollScheduled = false
    private var lastPublishedStatus: AppProtectionStatus? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollScheduled = false
            reconcileAndPoll()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        overlay = AppProtectionOverlay(this)
        usageReader = UsageStatsForegroundReader(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foregroundStarted) {
            try {
                startForegroundCompat()
                foregroundStarted = true
            } catch (exception: Exception) {
                Log.e(TAG, "app protection foreground startup failed", exception)
                publishStatusIfChanged(AppProtectionStatus.ERROR)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        reconcileAndPoll()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        pollScheduled = false
        if (::overlay.isInitialized) overlay.hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun reconcileAndPoll() {
        val appContext = applicationContext
        val snapshot = HomeZoneRuntimeCoordinator(appContext).reload()
        val store = BlockTargetStore(appContext)
        val selectedIds = store.selectedTargetIds()
        val selectedApps = store.all().filter { it.id in selectedIds && it is BlockTarget.App }
        if (snapshot.lastKnownInside != true) {
            stopWithStatus(AppProtectionStatus.OUTSIDE_OFF)
            return
        }
        if (selectedApps.isEmpty()) {
            stopWithStatus(AppProtectionStatus.NO_SELECTED_APP_TARGET)
            return
        }
        if (!UsageStatsForegroundReader.hasUsageAccess(appContext)) {
            overlay.hide()
            publishAndSchedule(AppProtectionStatus.USAGE_ACCESS_MISSING)
            return
        }
        if (!Settings.canDrawOverlays(appContext)) {
            overlay.hide()
            publishAndSchedule(AppProtectionStatus.OVERLAY_PERMISSION_MISSING)
            return
        }

        val result = try {
            usageReader.read()
        } catch (exception: Exception) {
            overlay.hide()
            Log.e(TAG, "app protection usage poll failed", exception)
            publishAndSchedule(AppProtectionStatus.ERROR)
            return
        }
        if (!result.accessGranted) {
            overlay.hide()
            publishAndSchedule(AppProtectionStatus.USAGE_ACCESS_MISSING)
            return
        }
        val decision = AppProtectionPolicy.decide(
            snapshot = snapshot,
            selectedTargets = selectedApps,
            foregroundPackage = result.packageName,
            ownPackage = packageName
        )
        try {
            if (decision is AppProtectionDecision.Block) {
                overlay.show(decision.label)
            } else {
                overlay.hide()
            }
            publishAndSchedule(AppProtectionStatus.ACTIVE)
        } catch (exception: Exception) {
            overlay.hide()
            Log.e(TAG, "app protection overlay update failed", exception)
            val status = if (!Settings.canDrawOverlays(appContext)) {
                AppProtectionStatus.OVERLAY_PERMISSION_MISSING
            } else {
                AppProtectionStatus.ERROR
            }
            publishAndSchedule(status)
        }
    }

    private fun stopWithStatus(status: AppProtectionStatus) {
        overlay.hide()
        publishStatusIfChanged(status)
        stopSelf()
    }

    private fun publishAndSchedule(status: AppProtectionStatus) {
        publishStatusIfChanged(status)
        if (!pollScheduled) {
            pollScheduled = true
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MILLIS)
        }
    }

    private fun startForegroundCompat() {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("自宅内のアプリ保護")
            .setContentText("選択したアプリを自宅内で保護しています")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            @Suppress("DEPRECATION") startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun publishStatusIfChanged(status: AppProtectionStatus) {
        if (lastPublishedStatus == status) return
        lastPublishedStatus = status
        AppProtectionController.publishStatus(this, status)
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "自宅内のアプリ保護",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        const val ACTION_STOP = "com.nacon01.kunekune.action.STOP_APP_PROTECTION"
        private const val CHANNEL_ID = "app_protection"
        private const val NOTIFICATION_ID = 4102
        private const val POLL_INTERVAL_MILLIS = 750L
        private const val TAG = "KunekuneAppProtection"
    }
}

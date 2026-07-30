package com.nacon01.kunekune

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

data class AppProtectionReconciliation(
    val shouldRun: Boolean,
    val status: AppProtectionStatus
)

/** Reconciles the durable home-zone policy with the dedicated protection service. */
object AppProtectionController {
    fun reconcile(context: Context): AppProtectionReconciliation {
        val appContext = context.applicationContext
        val snapshot = HomeZoneRuntimeCoordinator(appContext).reload()
        val targetStore = BlockTargetStore(appContext)
        val selectedAppCount = targetStore.all().count {
            it.id in targetStore.selectedTargetIds() && it is BlockTarget.App
        }
        val shouldRun = snapshot.lastKnownInside == true && selectedAppCount > 0
        val status = AppProtectionStatusPolicy.resolve(
            snapshot = snapshot,
            selectedAppTargetCount = selectedAppCount,
            usageAccessGranted = UsageStatsForegroundReader.hasUsageAccess(appContext),
            overlayPermissionGranted = Settings.canDrawOverlays(appContext)
        )

        if (!shouldRun) {
            appContext.stopService(Intent(appContext, AppProtectionService::class.java))
        } else {
            try {
                val serviceIntent = Intent(appContext, AppProtectionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(serviceIntent)
                } else {
                    appContext.startService(serviceIntent)
                }
            } catch (_: Exception) {
                publishStatus(appContext, AppProtectionStatus.ERROR)
                return AppProtectionReconciliation(true, AppProtectionStatus.ERROR)
            }
        }
        publishStatus(appContext, status)
        return AppProtectionReconciliation(shouldRun, status)
    }

    fun publishStatus(context: Context, status: AppProtectionStatus) {
        context.sendBroadcast(Intent(ACTION_STATUS_CHANGED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_STATUS, status.name)
        })
    }

    const val ACTION_STATUS_CHANGED = "com.nacon01.kunekune.action.APP_PROTECTION_STATUS_CHANGED"
    const val EXTRA_STATUS = "com.nacon01.kunekune.extra.APP_PROTECTION_STATUS"
}

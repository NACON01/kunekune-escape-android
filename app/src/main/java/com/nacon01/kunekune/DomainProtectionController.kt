package com.nacon01.kunekune

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build

enum class DomainProtectionStatus {
    ACTIVE,
    OUTSIDE_OFF,
    NO_SELECTED_DOMAIN_TARGET,
    VPN_PERMISSION_REQUIRED,
    STARTING,
    ERROR
}

data class DomainProtectionReconciliation(
    val shouldRun: Boolean,
    val status: DomainProtectionStatus
)

/** Keeps the geofence-controlled DNS VPN aligned with durable app state. */
object DomainProtectionController {
    fun reconcile(context: Context): DomainProtectionReconciliation {
        val appContext = context.applicationContext
        val snapshot = HomeZoneRuntimeCoordinator(appContext).reload()
        val store = BlockTargetStore(appContext)
        val selectedIds = store.selectedTargetIds()
        val selectedDomainCount = store.all().count {
            it is BlockTarget.Domain && it.id in selectedIds
        }
        if (snapshot.lastKnownInside != true) {
            stopService(appContext)
            return publishAndReturn(appContext, false, DomainProtectionStatus.OUTSIDE_OFF)
        }
        if (selectedDomainCount == 0) {
            stopService(appContext)
            return publishAndReturn(
                appContext, false, DomainProtectionStatus.NO_SELECTED_DOMAIN_TARGET
            )
        }
        val permissionIntent = try {
            VpnService.prepare(appContext)
        } catch (_: Exception) {
            stopService(appContext)
            return publishAndReturn(appContext, false, DomainProtectionStatus.ERROR)
        }
        if (permissionIntent != null) {
            stopService(appContext)
            return publishAndReturn(
                appContext, false, DomainProtectionStatus.VPN_PERMISSION_REQUIRED
            )
        }
        if (DomainProtectionVpnService.isRunning) {
            return publishAndReturn(appContext, true, DomainProtectionStatus.ACTIVE)
        }
        return try {
            val serviceIntent = Intent(appContext, DomainProtectionVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }
            publishAndReturn(appContext, true, DomainProtectionStatus.STARTING)
        } catch (_: Exception) {
            publishAndReturn(appContext, false, DomainProtectionStatus.ERROR)
        }
    }

    fun stop(context: Context, status: DomainProtectionStatus = DomainProtectionStatus.OUTSIDE_OFF) {
        val appContext = context.applicationContext
        stopService(appContext)
        publishStatus(appContext, status)
    }

    fun publishStatus(context: Context, status: DomainProtectionStatus) {
        context.sendBroadcast(Intent(ACTION_STATUS_CHANGED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_STATUS, status.name)
        })
    }

    private fun stopService(context: Context) {
        context.stopService(Intent(context, DomainProtectionVpnService::class.java))
    }

    private fun publishAndReturn(
        context: Context,
        shouldRun: Boolean,
        status: DomainProtectionStatus
    ): DomainProtectionReconciliation {
        publishStatus(context, status)
        return DomainProtectionReconciliation(shouldRun, status)
    }

    const val ACTION_STATUS_CHANGED =
        "com.nacon01.kunekune.action.DOMAIN_PROTECTION_STATUS_CHANGED"
    const val EXTRA_STATUS = "com.nacon01.kunekune.extra.DOMAIN_PROTECTION_STATUS"
}

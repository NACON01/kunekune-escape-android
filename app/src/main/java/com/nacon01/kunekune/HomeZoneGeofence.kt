package com.nacon01.kunekune

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

data class HomeZoneOperationResult(
    val success: Boolean,
    val message: String,
    val observation: LocationObservation? = null
)

enum class HomeZoneGeofenceRegistrationStatus {
    UNKNOWN,
    REGISTERED,
    NOT_REGISTERED,
    FAILED
}

data class CurrentHomeZoneLocationResult(
    val sample: HomeZoneLocationSample?,
    val errorMessage: String? = null
)

object HomeZoneGeofenceTransitionMapper {
    fun observationFor(requestId: String, transition: Int): LocationObservation? {
        if (requestId != HomeZoneGeofenceManager.HOME_REQUEST_ID) return null
        return when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> LocationObservation.INSIDE
            Geofence.GEOFENCE_TRANSITION_EXIT -> LocationObservation.OUTSIDE
            else -> null
        }
    }

    fun observationFor(transition: Int): LocationObservation? =
        observationFor(HomeZoneGeofenceManager.HOME_REQUEST_ID, transition)
}

class HomeZoneGeofenceManager(
    private val context: Context,
    private val preferences: HomeZonePreferences = HomeZonePreferences(context),
    private val coordinator: HomeZoneRuntimeCoordinator = HomeZoneRuntimeCoordinator(context)
) {
    private val appContext = context.applicationContext
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(appContext)
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)

    @Volatile
    var registrationStatus: HomeZoneGeofenceRegistrationStatus =
        HomeZoneGeofenceRegistrationStatus.UNKNOWN
        private set

    fun hasRequiredLocationPermissions(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                appContext.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED)

    fun hasForegroundPrecisePermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun registerOrUpdate(onComplete: (HomeZoneOperationResult) -> Unit) {
        val config = preferences.get()
        if (!hasRequiredLocationPermissions()) {
            registrationStatus = HomeZoneGeofenceRegistrationStatus.FAILED
            completeAsync(onComplete, HomeZoneOperationResult(false, "正確な位置情報と常に許可の権限が必要です。"))
            return
        }
        if (config == null) {
            registrationStatus = HomeZoneGeofenceRegistrationStatus.NOT_REGISTERED
            completeAsync(onComplete, HomeZoneOperationResult(false, "自宅位置を保存してください。"))
            return
        }

        val geofence = Geofence.Builder()
            .setRequestId(HOME_REQUEST_ID)
            .setCircularRegion(config.latitude, config.longitude, config.radiusMeters.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()
        try {
            geofencingClient.removeGeofences(pendingIntent).addOnCompleteListener {
                try {
                    geofencingClient.addGeofences(request, pendingIntent)
                        .addOnSuccessListener {
                            registrationStatus = HomeZoneGeofenceRegistrationStatus.REGISTERED
                            classifyCurrentLocation(config) { observation ->
                                onComplete(
                                    HomeZoneOperationResult(
                                        true,
                                        if (observation == LocationObservation.UNKNOWN) {
                                            "自宅ジオフェンスを登録しました。現在地を判定できないため不明です。"
                                        } else {
                                            "自宅ジオフェンスを登録しました。"
                                        },
                                        observation
                                    )
                                )
                            }
                        }
                        .addOnFailureListener { exception ->
                            registrationStatus = HomeZoneGeofenceRegistrationStatus.FAILED
                            onComplete(HomeZoneOperationResult(false, "自宅ジオフェンスを登録できませんでした: ${exception.message ?: "不明なエラー"}"))
                        }
                } catch (exception: Exception) {
                    registrationStatus = HomeZoneGeofenceRegistrationStatus.FAILED
                    onComplete(HomeZoneOperationResult(false, "自宅ジオフェンスを登録できませんでした: ${exception.message ?: "不明なエラー"}"))
                }
            }
        } catch (exception: SecurityException) {
            registrationStatus = HomeZoneGeofenceRegistrationStatus.FAILED
            completeAsync(onComplete, HomeZoneOperationResult(false, "位置情報権限が不足しています。"))
        } catch (exception: Exception) {
            registrationStatus = HomeZoneGeofenceRegistrationStatus.FAILED
            completeAsync(onComplete, HomeZoneOperationResult(false, "自宅ジオフェンスを登録できませんでした: ${exception.message ?: "不明なエラー"}"))
        }
    }

    fun remove(onComplete: (HomeZoneOperationResult) -> Unit = {}) {
        try {
            geofencingClient.removeGeofences(pendingIntent)
                .addOnSuccessListener {
                    registrationStatus = HomeZoneGeofenceRegistrationStatus.NOT_REGISTERED
                    onComplete(HomeZoneOperationResult(true, "自宅ジオフェンスを解除しました。"))
                }
                .addOnFailureListener { exception ->
                    registrationStatus = HomeZoneGeofenceRegistrationStatus.FAILED
                    onComplete(HomeZoneOperationResult(false, "自宅ジオフェンスを解除できませんでした: ${exception.message ?: "不明なエラー"}"))
                }
        } catch (exception: Exception) {
            registrationStatus = HomeZoneGeofenceRegistrationStatus.FAILED
            completeAsync(onComplete, HomeZoneOperationResult(false, "自宅ジオフェンスを解除できませんでした: ${exception.message ?: "不明なエラー"}"))
        }
    }

    fun requestCurrentLocation(onComplete: (CurrentHomeZoneLocationResult) -> Unit) {
        if (!hasForegroundPrecisePermission()) {
            completeAsync(onComplete, CurrentHomeZoneLocationResult(null, "正確な位置情報の権限が必要です。"))
            return
        }
        try {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token
            )
                .addOnSuccessListener { location ->
                    onComplete(CurrentHomeZoneLocationResult(location?.toSample(), if (location == null) "現在地を取得できませんでした。" else null))
                }
                .addOnFailureListener { exception ->
                    onComplete(CurrentHomeZoneLocationResult(null, "現在地を取得できませんでした: ${exception.message ?: "不明なエラー"}"))
                }
        } catch (exception: Exception) {
            completeAsync(onComplete, CurrentHomeZoneLocationResult(null, "現在地を取得できませんでした: ${exception.message ?: "不明なエラー"}"))
        }
    }

    private fun classifyCurrentLocation(config: HomeZoneConfig, onComplete: (LocationObservation) -> Unit) {
        requestCurrentLocation { result ->
            val observation = result.sample?.let { HomeZoneLocationClassifier.classify(config, it) }
                ?: LocationObservation.UNKNOWN
            coordinator.observe(observation)
            AppProtectionController.reconcile(appContext)
            onComplete(observation)
        }
    }

    private val pendingIntent: PendingIntent
        get() {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(
                appContext,
                PENDING_INTENT_REQUEST,
                Intent(appContext, HomeZoneGeofenceReceiver::class.java),
                flags
            )
        }

    private fun Location.toSample() = HomeZoneLocationSample(latitude, longitude, accuracy)

    private fun <T> completeAsync(callback: (T) -> Unit, value: T) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { callback(value) }
    }

    companion object {
        const val HOME_REQUEST_ID = "home-zone"
        const val ACTION_STATE_CHANGED = "com.nacon01.kunekune.action.HOME_ZONE_STATE_CHANGED"
        const val EXTRA_STATE = "com.nacon01.kunekune.extra.HOME_ZONE_STATE"
        const val EXTRA_VISIT_GENERATION = "com.nacon01.kunekune.extra.HOME_ZONE_VISIT_GENERATION"
        const val EXTRA_LAST_KNOWN_INSIDE = "com.nacon01.kunekune.extra.HOME_ZONE_LAST_KNOWN_INSIDE"
        const val EXTRA_UNKNOWN_WARNING = "com.nacon01.kunekune.extra.HOME_ZONE_UNKNOWN_WARNING"
        const val PENDING_INTENT_REQUEST = 4101
    }
}

class HomeZoneGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val coordinator = HomeZoneRuntimeCoordinator(appContext)
        val observation = try {
            val event = intent?.let(GeofencingEvent::fromIntent)
            if (event == null || event.hasError()) {
                LocationObservation.UNKNOWN
            } else {
                val matching = event.triggeringGeofences.orEmpty()
                    .mapNotNull { geofence ->
                        HomeZoneGeofenceTransitionMapper.observationFor(
                            geofence.requestId,
                            event.geofenceTransition
                        )
                    }
                if (matching.contains(LocationObservation.OUTSIDE)) LocationObservation.OUTSIDE
                else matching.firstOrNull()
                    ?: LocationObservation.UNKNOWN
            }
        } catch (_: Exception) {
            LocationObservation.UNKNOWN
        }
        val transition = coordinator.observe(observation)
        if (observation == LocationObservation.OUTSIDE || transition.stopAll) {
            appContext.stopService(Intent(appContext, BackgroundTrackingService::class.java))
            appContext.stopService(Intent(appContext, AppProtectionService::class.java))
            AppProtectionController.publishStatus(appContext, AppProtectionStatus.OUTSIDE_OFF)
        } else {
            AppProtectionController.reconcile(appContext)
        }
        publishStateChanged(appContext, coordinator.snapshot())
    }

    private fun publishStateChanged(context: Context, snapshot: HomeZoneSnapshot) {
        context.sendBroadcast(Intent(HomeZoneGeofenceManager.ACTION_STATE_CHANGED).apply {
            setPackage(context.packageName)
            putExtra(HomeZoneGeofenceManager.EXTRA_STATE, snapshot.state.name)
            putExtra(HomeZoneGeofenceManager.EXTRA_VISIT_GENERATION, snapshot.visitGeneration)
            putExtra(HomeZoneGeofenceManager.EXTRA_LAST_KNOWN_INSIDE, snapshot.lastKnownInside)
            putExtra(HomeZoneGeofenceManager.EXTRA_UNKNOWN_WARNING, snapshot.unknownWarning)
        })
    }
}

class HomeZoneBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        AppProtectionController.reconcile(appContext)
        val pendingResult = goAsync()
        try {
            HomeZoneGeofenceManager(appContext).registerOrUpdate {
                AppProtectionController.reconcile(appContext)
                pendingResult.finish()
            }
        } catch (_: Exception) {
            AppProtectionController.reconcile(appContext)
            pendingResult.finish()
        }
    }
}

package com.nacon01.kunekune

import android.content.Context
import org.json.JSONObject

data class HomeZoneConfig(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Latitude must be finite and in range"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude must be finite and in range"
        }
        require(radiusMeters.isFinite() && radiusMeters > 0.0) {
            "Radius must be finite and positive"
        }
    }

    val hasSmallRadiusWarning: Boolean
        get() = radiusMeters < MIN_WARNING_RADIUS_METERS

    fun hasRadiusWarning(): Boolean = hasSmallRadiusWarning

    companion object {
        const val MIN_WARNING_RADIUS_METERS = 100.0
    }
}

/** Shared-preferences backed home-zone configuration with a testable string-store seam. */
class HomeZonePreferences(private val store: StringPreferenceStore) {
    constructor(context: Context) : this(
        SharedPreferencesStringStore(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        )
    )

    fun get(): HomeZoneConfig? {
        val raw = store.getString(KEY_CONFIG) ?: return null
        return try {
            val json = JSONObject(raw)
            require(json.keys().asSequence().toSet() == setOf("latitude", "longitude", "radiusMeters"))
            HomeZoneConfig(
                latitude = json.getDouble("latitude"),
                longitude = json.getDouble("longitude"),
                radiusMeters = json.getDouble("radiusMeters")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun save(config: HomeZoneConfig) {
        store.putString(
            KEY_CONFIG,
            JSONObject().apply {
                put("latitude", config.latitude)
                put("longitude", config.longitude)
                put("radiusMeters", config.radiusMeters)
            }.toString()
        )
    }

    fun clear() {
        store.remove(KEY_CONFIG)
    }

    companion object {
        private const val PREFERENCES_NAME = "home_zone"
        private const val KEY_CONFIG = "config"
    }
}

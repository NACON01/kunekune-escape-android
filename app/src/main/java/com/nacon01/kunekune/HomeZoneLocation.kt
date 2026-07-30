package com.nacon01.kunekune

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class HomeZoneLocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float
)

/** Conservative classification: an accuracy circle must be wholly on one side of the boundary. */
object HomeZoneLocationClassifier {
    fun distanceMeters(config: HomeZoneConfig, sample: HomeZoneLocationSample): Double =
        haversineMeters(config.latitude, config.longitude, sample.latitude, sample.longitude)

    fun classify(
        config: HomeZoneConfig,
        sample: HomeZoneLocationSample
    ): LocationObservation {
        if (!sample.latitude.isFinite() || !sample.longitude.isFinite() ||
            sample.latitude !in -90.0..90.0 || sample.longitude !in -180.0..180.0 ||
            !sample.accuracyMeters.isFinite() || sample.accuracyMeters < 0f
        ) return LocationObservation.UNKNOWN

        val distance = distanceMeters(config, sample)
        val accuracy = sample.accuracyMeters.toDouble()
        return when {
            distance + accuracy <= config.radiusMeters -> LocationObservation.INSIDE
            distance - accuracy > config.radiusMeters -> LocationObservation.OUTSIDE
            else -> LocationObservation.UNKNOWN
        }
    }

    private fun haversineMeters(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double
    ): Double {
        val earthRadiusMeters = 6_371_000.0
        val latitudeDelta = Math.toRadians(latitude2 - latitude1)
        val longitudeDelta = Math.toRadians(longitude2 - longitude1)
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(Math.toRadians(latitude1)) * cos(Math.toRadians(latitude2)) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return earthRadiusMeters * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}


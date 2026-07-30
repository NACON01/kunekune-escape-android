package com.nacon01.kunekune

import java.util.Locale
import java.util.UUID

data class RouteCatalogEntry(
    val id: String,
    val name: String,
    val markerProfileId: String = DEFAULT_MARKER_PROFILE_ID,
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
    val recordedAtEpochMillis: Long,
    val pointCount: Int,
    val totalDistanceMeters: Float
) {
    init {
        require(canonicalUuid(id) == id) { "Route id must be a canonical UUID" }
        require(name.trim().isNotEmpty()) { "Route name must not be blank" }
        require(markerProfileId.trim().isNotEmpty()) { "Marker profile id must not be blank" }
        require(createdAtEpochMillis >= 0L) { "Route creation timestamp must not be negative" }
        require(updatedAtEpochMillis >= 0L) { "Route update timestamp must not be negative" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Route updated timestamp must not precede creation"
        }
        require(pointCount >= 2) { "Route must contain at least two points" }
        require(totalDistanceMeters.isFinite() && totalDistanceMeters >= 0f) {
            "Route distance must be finite and non-negative"
        }
    }
}

/** Metadata index for all routes stored by [RouteRepository]. */
data class RouteCatalog(
    val version: Int = VERSION,
    val entries: List<RouteCatalogEntry> = emptyList()
) {
    init {
        require(version == VERSION) { "Route catalog version must be $VERSION" }
        val ids = mutableSetOf<String>()
        val names = mutableSetOf<String>()
        entries.forEach { entry ->
            require(ids.add(entry.id)) { "Duplicate route id: ${entry.id}" }
            require(names.add(entry.name.trim().lowercase(Locale.ROOT))) {
                "Duplicate route name: ${entry.name}"
            }
        }
    }

    val routes: List<RouteCatalogEntry>
        get() = entries

    companion object {
        const val VERSION = 2
    }
}

/** A named, validated destination route exposed by the repository API. */
data class DestinationRoute(
    val id: String,
    val name: String,
    val markerProfileId: String = DEFAULT_MARKER_PROFILE_ID,
    val route: RecordedRoute,
    val createdAtEpochMillis: Long = route.recordedAtEpochMillis,
    val updatedAtEpochMillis: Long = createdAtEpochMillis
) {
    init {
        require(canonicalUuid(id) == id) { "Route id must be a canonical UUID" }
        require(name.trim().isNotEmpty()) { "Route name must not be blank" }
        require(markerProfileId.trim().isNotEmpty()) { "Marker profile id must not be blank" }
        require(createdAtEpochMillis >= 0L) { "Route creation timestamp must not be negative" }
        require(updatedAtEpochMillis >= 0L) { "Route update timestamp must not be negative" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Route updated timestamp must not precede creation"
        }
    }

    constructor(id: String, name: String, route: RecordedRoute) : this(
        id = id,
        name = name,
        markerProfileId = DEFAULT_MARKER_PROFILE_ID,
        route = route
    )

    val recordedAtEpochMillis: Long
        get() = route.recordedAtEpochMillis

    val points: List<RoutePoint>
        get() = route.points

    val totalDistanceMeters: Float
        get() = route.totalDistanceMeters
}

internal fun canonicalUuid(value: String): String {
    val normalized = value.trim().lowercase(Locale.ROOT)
    require(normalized.length == UUID_STRING_LENGTH) { "Route id must be a UUID" }
    val parsed = UUID.fromString(normalized)
    require(parsed.toString() == normalized) { "Route id must be a canonical UUID" }
    return normalized
}

internal fun normalizedRouteName(value: String): String = value.trim().also {
    require(it.isNotEmpty()) { "Route name must not be blank" }
}

internal fun routeNameKey(value: String): String = value.trim().lowercase(Locale.ROOT)

private const val UUID_STRING_LENGTH = 36
const val DEFAULT_MARKER_PROFILE_ID = "default-v1"

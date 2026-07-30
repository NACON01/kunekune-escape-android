package com.nacon01.kunekune

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/** JSON codec for the legacy RecordedRoute payload and the v2 route catalog. */
object RouteJsonCodec {
    const val RECORDED_ROUTE_VERSION = 1
    const val CATALOG_VERSION = 2

    fun encode(route: RecordedRoute): String = route.toJson().toString()

    fun decode(json: String): RecordedRoute = JSONObject(json).toRecordedRoute()

    fun encodeRoute(route: RecordedRoute): String = encode(route)

    fun decodeRoute(json: String): RecordedRoute = decode(json)

    fun encodeCatalog(catalog: RouteCatalog): String = catalog.toJson().toString()

    fun decodeCatalog(json: String): RouteCatalog = JSONObject(json).toRouteCatalog()

    private fun RecordedRoute.toJson(): JSONObject = JSONObject().apply {
        put("version", RECORDED_ROUTE_VERSION)
        put("metadata", JSONObject().apply {
            put("recordedAtEpochMillis", recordedAtEpochMillis)
            put("pointCount", points.size)
            put("totalDistanceMeters", totalDistanceMeters.toDouble())
        })
        put("points", JSONArray().apply {
            points.forEach { point ->
                put(JSONObject().apply {
                    put("x", point.x.toDouble())
                    put("y", point.y.toDouble())
                    put("z", point.z.toDouble())
                    put("elapsedMillis", point.elapsedMillis)
                })
            }
        })
    }

    private fun JSONObject.toRecordedRoute(): RecordedRoute {
        require(keys().asSequence().toSet() == setOf("version", "metadata", "points")) {
            "Route payload has unexpected fields"
        }
        val version = requiredInt("version")
        require(version == RECORDED_ROUTE_VERSION) { "Unsupported route version: $version" }

        val metadata = getJSONObject("metadata")
        val jsonPoints = getJSONArray("points")
        require(metadata.keys().asSequence().toSet() == setOf(
            "recordedAtEpochMillis", "pointCount", "totalDistanceMeters"
        )) { "Route metadata has unexpected fields" }
        val points = buildList(jsonPoints.length()) {
            for (index in 0 until jsonPoints.length()) {
                val point = jsonPoints.getJSONObject(index)
                require(point.keys().asSequence().toSet() == setOf("x", "y", "z", "elapsedMillis")) {
                    "Route point has unexpected fields"
                }
                val x = point.requiredDouble("x")
                val y = point.requiredDouble("y")
                val z = point.requiredDouble("z")
                require(x.isFinite() && y.isFinite() && z.isFinite()) {
                    "Route point coordinates must be finite"
                }
                val elapsedMillis = point.requiredLong("elapsedMillis")
                require(elapsedMillis >= 0L) { "Route point elapsed time must not be negative" }
                add(
                    RoutePoint(
                        x = x.toFloat().also { require(it.isFinite()) {
                            "Route point coordinates must be finite"
                        } },
                        y = y.toFloat().also { require(it.isFinite()) {
                            "Route point coordinates must be finite"
                        } },
                        z = z.toFloat().also { require(it.isFinite()) {
                            "Route point coordinates must be finite"
                        } },
                        elapsedMillis = elapsedMillis
                    )
                )
            }
        }
        require(points.size >= 2) { "Route payload must contain at least two points" }
        val pointCount = metadata.requiredInt("pointCount")
        require(pointCount == points.size) {
            "Route point count does not match metadata"
        }
        val totalDistance = metadata.requiredDouble("totalDistanceMeters")
        require(totalDistance.isFinite() && totalDistance >= 0.0) {
            "Route distance must be finite and non-negative"
        }
        require(totalDistance.toFloat().isFinite()) {
            "Route distance must be representable as a float"
        }
        val route = RecordedRoute(
            recordedAtEpochMillis = metadata.requiredLong("recordedAtEpochMillis"),
            points = points,
            totalDistanceMeters = totalDistance.toFloat()
        )
        require(distanceOf(route).isCloseTo(totalDistance)) {
            "Route distance does not match route points"
        }
        return route
    }

    private fun distanceOf(route: RecordedRoute): Double {
        var total = 0.0
        for (index in 0 until route.points.lastIndex) {
            val start = route.points[index]
            val end = route.points[index + 1]
            total += hypot(
                hypot(
                    (end.x - start.x).toDouble(),
                    (end.y - start.y).toDouble()
                ),
                (end.z - start.z).toDouble()
            )
        }
        return total
    }

    private fun Double.isCloseTo(other: Double): Boolean {
        val tolerance = maxOf(0.0001, abs(other) * 0.0001)
        return abs(this - other) <= tolerance
    }

    private fun RouteCatalog.toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("routes", JSONArray().apply {
            routes.forEach { entry ->
                put(JSONObject().apply {
                    put("id", entry.id)
                    put("name", entry.name)
                    put("markerProfileId", entry.markerProfileId)
                    put("createdAtEpochMillis", entry.createdAtEpochMillis)
                    put("updatedAtEpochMillis", entry.updatedAtEpochMillis)
                    put("recordedAtEpochMillis", entry.recordedAtEpochMillis)
                    put("pointCount", entry.pointCount)
                    put("totalDistanceMeters", entry.totalDistanceMeters.toDouble())
                })
            }
        })
    }

    private fun JSONObject.toRouteCatalog(): RouteCatalog {
        require(keys().asSequence().toSet() == setOf("version", "routes")) {
            "Catalog has unexpected fields"
        }
        val version = requiredInt("version")
        require(version == RouteCatalog.VERSION) { "Unsupported catalog version: $version" }
        val jsonRoutes = getJSONArray("routes")
        val routes = buildList(jsonRoutes.length()) {
            for (index in 0 until jsonRoutes.length()) {
                val entry = jsonRoutes.getJSONObject(index)
                require(entry.keys().asSequence().toSet() == setOf(
                    "id", "name", "markerProfileId", "createdAtEpochMillis",
                    "updatedAtEpochMillis", "recordedAtEpochMillis", "pointCount",
                    "totalDistanceMeters"
                )) { "Catalog route entry has unexpected fields" }
                add(
                    RouteCatalogEntry(
                        id = entry.requiredString("id"),
                        name = entry.requiredString("name"),
                        markerProfileId = entry.requiredString("markerProfileId"),
                        createdAtEpochMillis = entry.requiredLong("createdAtEpochMillis"),
                        updatedAtEpochMillis = entry.requiredLong("updatedAtEpochMillis"),
                        recordedAtEpochMillis = entry.requiredLong("recordedAtEpochMillis"),
                        pointCount = entry.requiredInt("pointCount"),
                        totalDistanceMeters = entry.requiredDouble("totalDistanceMeters").toFloat().also {
                            require(it.isFinite()) {
                                "Route distance must be finite and representable as a float"
                            }
                        }
                    )
                )
            }
        }
        return RouteCatalog(version = version, entries = routes)
    }

    private fun JSONObject.requiredString(key: String): String {
        val value = get(key)
        require(value is String) { "JSON field must be a string: $key" }
        return value
    }

    private fun JSONObject.requiredDouble(key: String): Double {
        val value = get(key)
        require(value is Number) { "JSON field must be numeric: $key" }
        return value.toDouble()
    }

    private fun JSONObject.requiredLong(key: String): Long {
        val value = get(key)
        require(value is Number) { "JSON field must be an integer: $key" }
        val converted = value.toLong()
        require(value.toDouble().isFinite() && value.toDouble() == converted.toDouble()) {
            "JSON field must be an integer: $key"
        }
        return converted
    }

    private fun JSONObject.requiredInt(key: String): Int {
        val value = requiredLong(key)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) {
            "JSON integer is out of range: $key"
        }
        return value.toInt()
    }
}

package com.nacon01.kunekune

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

class RouteRepository(
    private val root: File,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    constructor(context: Context) : this(
        context.applicationContext.getExternalFilesDir(null)?.resolve(ROUTES_DIRECTORY_NAME)
            ?: throw IOException("External files directory is unavailable")
    )

    private val catalogFile: File
        get() = root.resolve(CATALOG_FILE_NAME)

    private val legacyFile: File
        get() = root.parentFile?.resolve(LEGACY_FILE_NAME)
            ?: throw IOException("Route directory must have a parent directory")

    private val legacyBackupFile: File
        get() = legacyFile.resolveSibling("${legacyFile.name}.bak")

    @Synchronized
    fun list(): List<DestinationRoute> = ensureLoaded().routes.map(::readRoute)

    @Synchronized
    fun listMetadata(): List<RouteCatalogEntry> = ensureLoaded().routes

    @Synchronized
    fun get(id: String): DestinationRoute? {
        val canonicalId = canonicalUuid(id)
        val entry = ensureLoaded().routes.firstOrNull { it.id == canonicalId } ?: return null
        return readRoute(entry)
    }

    @Synchronized
    fun create(name: String, route: RecordedRoute): DestinationRoute = create(
        name = name,
        route = route,
        markerProfileId = DEFAULT_MARKER_PROFILE_ID,
        id = UUID.randomUUID().toString()
    )

    fun create(route: RecordedRoute, name: String): DestinationRoute = create(name, route)

    @Synchronized
    fun create(name: String, route: RecordedRoute, id: String): DestinationRoute = create(
        name = name,
        route = route,
        markerProfileId = DEFAULT_MARKER_PROFILE_ID,
        id = id
    )

    @Synchronized
    fun create(
        name: String,
        route: RecordedRoute,
        markerProfileId: String = DEFAULT_MARKER_PROFILE_ID,
        id: String = UUID.randomUUID().toString(),
        createdAtEpochMillis: Long = clock()
    ): DestinationRoute {
        val catalog = ensureLoaded()
        val normalizedName = normalizedRouteName(name)
        val canonicalId = canonicalUuid(id)
        val normalizedMarkerProfileId = markerProfileId.trim().also {
            require(it.isNotEmpty()) { "Marker profile id must not be blank" }
        }
        validateRoute(route)
        require(catalog.routes.none { it.id == canonicalId }) {
            "Route id already exists: $canonicalId"
        }
        require(catalog.routes.none { routeNameKey(it.name) == routeNameKey(normalizedName) }) {
            "Route name already exists: $normalizedName"
        }

        val destination = DestinationRoute(
            id = canonicalId,
            name = normalizedName,
            markerProfileId = normalizedMarkerProfileId,
            route = route,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = createdAtEpochMillis
        )
        writePayload(destination)
        try {
            writeCatalog(catalog.copy(entries = catalog.routes + destination.toCatalogEntry()))
        } catch (exception: Exception) {
            deletePayloadIfPresent(canonicalId)
            throw exception
        }
        return destination
    }

    @Synchronized
    fun updateName(id: String, name: String): DestinationRoute {
        val canonicalId = canonicalUuid(id)
        val catalog = ensureLoaded()
        val current = catalog.routes.firstOrNull { it.id == canonicalId }
            ?: throw NoSuchElementException("Unknown route id: $canonicalId")
        val normalizedName = normalizedRouteName(name)
        require(catalog.routes.none {
            it.id != canonicalId && routeNameKey(it.name) == routeNameKey(normalizedName)
        }) { "Route name already exists: $normalizedName" }

        val updatedAt = maxOf(clock(), current.updatedAtEpochMillis)
        val updatedCatalog = catalog.copy(entries = catalog.routes.map { entry ->
            if (entry.id == canonicalId) {
                entry.copy(name = normalizedName, updatedAtEpochMillis = updatedAt)
            } else {
                entry
            }
        })
        writeCatalog(updatedCatalog)
        return readRoute(updatedCatalog.routes.first { it.id == canonicalId })
    }

    @Synchronized
    fun rename(id: String, name: String): DestinationRoute = updateName(id, name)

    @Synchronized
    fun replaceRoute(id: String, route: RecordedRoute): DestinationRoute {
        val canonicalId = canonicalUuid(id)
        val catalog = ensureLoaded()
        val current = catalog.routes.firstOrNull { it.id == canonicalId }
            ?: throw NoSuchElementException("Unknown route id: $canonicalId")
        validateRoute(route)

        val destination = DestinationRoute(
            id = canonicalId,
            name = current.name,
            markerProfileId = current.markerProfileId,
            route = route,
            createdAtEpochMillis = current.createdAtEpochMillis,
            updatedAtEpochMillis = maxOf(clock(), current.updatedAtEpochMillis)
        )
        val payload = payloadFile(canonicalId)
        val previousPayload = payload.readBytes()
        writePayload(destination)
        val updatedCatalog = catalog.copy(entries = catalog.routes.map { entry ->
            if (entry.id == canonicalId) destination.toCatalogEntry() else entry
        })
        try {
            writeCatalog(updatedCatalog)
        } catch (exception: Exception) {
            writeAtomically(payload, previousPayload)
            throw exception
        }
        return destination
    }

    @Synchronized
    fun replaceRecordedRoute(id: String, route: RecordedRoute): DestinationRoute =
        replaceRoute(id, route)

    @Synchronized
    fun delete(id: String): Boolean {
        val canonicalId = canonicalUuid(id)
        val catalog = ensureLoaded()
        if (catalog.routes.none { it.id == canonicalId }) return false
        writeCatalog(catalog.copy(entries = catalog.routes.filterNot { it.id == canonicalId }))
        deletePayloadIfPresent(canonicalId)
        return true
    }

    private fun ensureLoaded(): RouteCatalog {
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Unable to create route directory: $root")
        }
        return if (catalogFile.exists()) {
            readCatalog()
        } else {
            migrateLegacyOrCreateCatalog()
        }
    }

    private fun readCatalog(): RouteCatalog = try {
        RouteJsonCodec.decodeCatalog(catalogFile.readText(StandardCharsets.UTF_8))
    } catch (exception: Exception) {
        throw IOException("Unable to read route catalog", exception)
    }

    private fun migrateLegacyOrCreateCatalog(): RouteCatalog {
        val source = when {
            legacyFile.exists() -> legacyFile
            legacyBackupFile.exists() -> legacyBackupFile
            else -> return RouteCatalog().also(::writeCatalog)
        }
        val legacyRoute = try {
            RouteJsonCodec.decode(source.readText(StandardCharsets.UTF_8))
        } catch (exception: Exception) {
            throw IOException("Unable to read legacy route", exception)
        }
        validateRoute(legacyRoute)

        val now = clock()
        val migrated = DestinationRoute(
            id = UUID.randomUUID().toString(),
            name = LEGACY_ROUTE_NAME,
            markerProfileId = DEFAULT_MARKER_PROFILE_ID,
            route = legacyRoute,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )
        writePayload(migrated)
        if (source == legacyFile) {
            try {
                moveLegacyToBackup()
            } catch (exception: Exception) {
                deletePayloadIfPresent(migrated.id)
                throw exception
            }
        }
        try {
            val catalog = RouteCatalog(entries = listOf(migrated.toCatalogEntry()))
            writeCatalog(catalog)
            return catalog
        } catch (exception: Exception) {
            if (source == legacyFile && !legacyFile.exists() && legacyBackupFile.exists()) {
                try {
                    Files.move(legacyBackupFile.toPath(), legacyFile.toPath())
                } catch (restoreException: Exception) {
                    exception.addSuppressed(restoreException)
                }
            }
            deletePayloadIfPresent(migrated.id)
            throw exception
        }
    }

    private fun moveLegacyToBackup() {
        legacyBackupFile.parentFile?.mkdirs()
        try {
            Files.move(legacyFile.toPath(), legacyBackupFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(legacyFile.toPath(), legacyBackupFile.toPath())
        }
    }

    private fun readRoute(entry: RouteCatalogEntry): DestinationRoute = DestinationRoute(
        id = entry.id,
        name = entry.name,
        markerProfileId = entry.markerProfileId,
        route = readRoutePayload(entry),
        createdAtEpochMillis = entry.createdAtEpochMillis,
        updatedAtEpochMillis = entry.updatedAtEpochMillis
    )

    private fun readRoutePayload(entry: RouteCatalogEntry): RecordedRoute {
        val payloadFile = payloadFile(entry.id)
        if (!payloadFile.exists()) throw IOException("Route payload is missing: ${entry.id}")
        return try {
            val route = RouteJsonCodec.decode(payloadFile.readText(StandardCharsets.UTF_8))
            validateRoute(route)
            require(entry.pointCount == route.points.size) {
                "Catalog metadata does not match route payload: ${entry.id}"
            }
            require(entry.recordedAtEpochMillis == route.recordedAtEpochMillis) {
                "Catalog metadata does not match route payload: ${entry.id}"
            }
            require(entry.totalDistanceMeters == route.totalDistanceMeters) {
                "Catalog metadata does not match route payload: ${entry.id}"
            }
            route
        } catch (exception: IOException) {
            throw exception
        } catch (exception: Exception) {
            throw IOException("Unable to read route payload: ${entry.id}", exception)
        }
    }

    private fun validateRoute(route: RecordedRoute) {
        val points = route.points.map { point -> GuidanceVector3(point.x, point.y, point.z) }
        require(StoredRouteValidator.isValid(points)) {
            "Route does not pass StoredRouteValidator"
        }
        require(route.recordedAtEpochMillis >= 0L) {
            "Route recording timestamp must not be negative"
        }
        require(route.totalDistanceMeters.isFinite() && route.totalDistanceMeters >= 0f) {
            "Route distance must be finite and non-negative"
        }
        require(route.points.zipWithNext().all { (start, end) ->
            start.elapsedMillis >= 0L && end.elapsedMillis >= start.elapsedMillis
        }) { "Route point elapsed times must be non-negative and non-decreasing" }
        var distance = 0.0
        for ((start, end) in route.points.zipWithNext()) {
            distance += hypot(
                hypot(
                    (end.x - start.x).toDouble(),
                    (end.y - start.y).toDouble()
                ),
                (end.z - start.z).toDouble()
            )
        }
        val expected = route.totalDistanceMeters.toDouble()
        val tolerance = maxOf(0.0001, abs(expected) * 0.0001)
        require(abs(distance - expected) <= tolerance) {
            "Route distance does not match route points"
        }
    }

    private fun DestinationRoute.toCatalogEntry() = RouteCatalogEntry(
        id = id,
        name = name,
        markerProfileId = markerProfileId,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        recordedAtEpochMillis = route.recordedAtEpochMillis,
        pointCount = route.points.size,
        totalDistanceMeters = route.totalDistanceMeters
    )

    private fun writePayload(destination: DestinationRoute) {
        writeAtomically(payloadFile(destination.id), RouteJsonCodec.encode(destination.route))
    }

    private fun writeCatalog(catalog: RouteCatalog) {
        writeAtomically(catalogFile, RouteJsonCodec.encodeCatalog(catalog))
    }

    private fun payloadFile(id: String): File = root.resolve("$id.json")

    private fun deletePayloadIfPresent(id: String) {
        try {
            Files.deleteIfExists(payloadFile(id).toPath())
        } catch (exception: Exception) {
            throw IOException("Unable to delete route payload: $id", exception)
        }
    }

    private fun writeAtomically(target: File, contents: String) {
        writeAtomically(target, contents.toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeAtomically(target: File, contents: ByteArray) {
        target.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Unable to create route directory: $parent")
            }
        }
        val temporary = target.resolveSibling(".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(contents)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (exception: IOException) {
            throw exception
        } catch (exception: Exception) {
            throw IOException("Unable to save route file: $target", exception)
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val ROUTES_DIRECTORY_NAME = "routes"
        const val CATALOG_FILE_NAME = "catalog.json"
        const val LEGACY_FILE_NAME = "route.json"
        const val LEGACY_ROUTE_NAME = "Migrated route"
    }
}

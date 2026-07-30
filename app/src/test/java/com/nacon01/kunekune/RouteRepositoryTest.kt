package com.nacon01.kunekune

import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RouteRepositoryTest {
    private val roots = mutableListOf<File>()

    @After
    fun cleanup() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun crudUsesCatalogMetadataAndPerIdPayload() {
        val externalFilesDir = tempDirectory()
        val repository = RouteRepository(externalFilesDir.resolve("routes"))
        val route = route(1_000L, 0.3f)

        val created = repository.create("  Home  ", route)

        assertEquals("Home", created.name)
        assertEquals(DEFAULT_MARKER_PROFILE_ID, created.markerProfileId)
        assertEquals(created.createdAtEpochMillis, created.updatedAtEpochMillis)
        assertEquals(2, repository.listMetadata().single().pointCount)
        assertEquals(listOf(created.id), repository.listMetadata().map { it.id })
        assertEquals(created, repository.get(created.id))
        assertTrue(externalFilesDir.resolve("routes/catalog.json").isFile)
        assertTrue(externalFilesDir.resolve("routes/${created.id}.json").isFile)

        val renamed = repository.updateName(created.id.uppercase(), " Office ")
        assertEquals("Office", renamed.name)
        val replacement = route(2_000L, 0.4f)
        assertEquals(replacement, repository.replaceRoute(created.id, replacement).route)
        assertTrue(repository.delete(created.id))
        assertFalse(repository.delete(created.id))
        assertNull(repository.get(created.id))
    }

    @Test
    fun namesAreUniqueIgnoringCaseAndWhitespace() {
        val repository = RouteRepository(tempDirectory().resolve("routes"))
        repository.create("Home", route(1L, 0.3f))

        try {
            repository.create("  hOmE  ", route(2L, 0.3f))
            throw AssertionError("Expected duplicate name rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun validLegacyRouteIsMovedOnceAndMigrated() {
        val externalFilesDir = tempDirectory()
        val legacyFile = externalFilesDir.resolve("route.json")
        val legacyRoute = route(3L, 0.3f)
        legacyFile.writeText(RouteJsonCodec.encode(legacyRoute))
        val repository = RouteRepository(externalFilesDir.resolve("routes"))

        val first = repository.list()
        val second = RouteRepository(externalFilesDir.resolve("routes")).list()

        assertEquals(1, first.size)
        assertEquals(legacyRoute, first.single().route)
        assertEquals(first.single().id, second.single().id)
        assertFalse(legacyFile.exists())
        val backup = externalFilesDir.resolve("route.json.bak")
        assertTrue(backup.isFile)
        assertEquals(RouteJsonCodec.encode(legacyRoute), backup.readText())
        assertNotNull(repository.get(first.single().id))
    }

    @Test
    fun migrationIsIdempotentAcrossRepeatedLoads() {
        val externalFilesDir = tempDirectory()
        externalFilesDir.resolve("route.json").writeText(RouteJsonCodec.encode(route(5L, 0.3f)))
        val root = externalFilesDir.resolve("routes")

        val first = RouteRepository(root).list().single()
        val second = RouteRepository(root).list().single()
        val third = RouteRepository(root).list().single()

        assertEquals(first, second)
        assertEquals(second, third)
        assertEquals(1, RouteRepository(root).listMetadata().size)
    }

    @Test
    fun invalidLegacyRouteIsNotMoved() {
        val externalFilesDir = tempDirectory()
        val legacyFile = externalFilesDir.resolve("route.json")
        legacyFile.writeText(RouteJsonCodec.encode(route(4L, 0.1f)))
        val repository = RouteRepository(externalFilesDir.resolve("routes"))

        try {
            repository.list()
            throw AssertionError("Expected invalid legacy route rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertTrue(legacyFile.exists())
        assertFalse(externalFilesDir.resolve("route.json.bak").exists())
    }

    @Test
    fun corruptLegacyRouteIsNotMoved() {
        val externalFilesDir = tempDirectory()
        val legacyFile = externalFilesDir.resolve("route.json")
        legacyFile.writeText("not valid json")

        try {
            RouteRepository(externalFilesDir.resolve("routes")).list()
            fail("Expected corrupt legacy route rejection")
        } catch (_: java.io.IOException) {
            // expected
        }
        assertTrue(legacyFile.exists())
        assertFalse(externalFilesDir.resolve("route.json.bak").exists())
    }

    @Test
    fun invalidRecordedRouteIsRejectedByStoredRouteValidator() {
        val externalFilesDir = tempDirectory()
        val repository = RouteRepository(externalFilesDir.resolve("routes"))

        try {
            repository.create("Too short", route(6L, 0.1f))
            fail("Expected invalid route rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertTrue(externalFilesDir.resolve("route.json").let { !it.exists() })
        assertTrue(externalFilesDir.resolve("routes/catalog.json").isFile)
    }

    @Test
    fun catalogAndPayloadMetadataMustAgree() {
        val externalFilesDir = tempDirectory()
        val root = externalFilesDir.resolve("routes")
        val repository = RouteRepository(root)
        val created = repository.create("Home", route(7L, 0.3f))

        val catalog = root.resolve("catalog.json")
        catalog.writeText(catalog.readText().replace("\"pointCount\":2", "\"pointCount\":3"))
        try {
            repository.get(created.id)
            fail("Expected stale catalog rejection")
        } catch (_: java.io.IOException) {
            // expected
        }
    }

    private fun tempDirectory(): File = File.createTempFile("route-repository", "").also {
        assertTrue(it.delete())
        assertTrue(it.mkdirs())
        roots += it
    }

    private fun route(recordedAt: Long, distance: Float): RecordedRoute = RecordedRoute(
        recordedAtEpochMillis = recordedAt,
        points = listOf(
            RoutePoint(0f, 0f, 0f, 0L),
            RoutePoint(distance, 0f, 0f, 100L)
        ),
        totalDistanceMeters = distance
    )
}

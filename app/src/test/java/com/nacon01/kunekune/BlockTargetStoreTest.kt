package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockTargetStoreTest {
    @Test
    fun crudSelectionAndInitialTargetRepairAreDeterministic() {
        val preferences = TestStringPreferences()
        val store = BlockTargetStore(preferences)
        val app = BlockTarget.app("com.example.app", "App")
        val domain = BlockTarget.domain("example.com", true)
        val other = BlockTarget.domain("other.example.com", false)

        store.add(domain)
        store.add(app)
        store.add(other)
        assertEquals(domain.id, store.initialTargetId())
        assertEquals(setOf(domain.id), store.selectedTargetIds())

        store.setSelectedTargetIds(setOf(app.id, other.id))
        assertEquals(setOf(app.id, other.id, domain.id), store.selectedTargetIds())
        store.setInitialTargetId(other.id)
        assertEquals(other.id, store.initialTargetId())
        assertTrue(other.id in store.selectedTargetIds())

        assertTrue(store.remove(domain.id))
        assertEquals(other.id, store.initialTargetId())
        assertFalse(store.remove(domain.id))
        assertEquals(listOf(app.id, other.id), store.all().map { it.id })

        store.remove(other.id)
        assertEquals(app.id, store.initialTargetId())
        assertEquals(setOf(app.id), store.selectedTargetIds())
        assertEquals(app, store.get(app.id))
    }

    @Test
    fun corruptPreferencesFallBackToEmptyWithoutThrowing() {
        val preferences = TestStringPreferences()
        preferences.values["state"] = "not json"

        val store = BlockTargetStore(preferences)

        assertTrue(store.all().isEmpty())
        assertTrue(store.selectedTargetIds().isEmpty())
        assertNull(store.initialTargetId())
    }

    @Test
    fun selectionCannotReferToUnknownTargetsAndPersistedOrderIsStable() {
        val preferences = TestStringPreferences()
        val store = BlockTargetStore(preferences)
        val z = BlockTarget.app("com.z", "Z")
        val a = BlockTarget.app("com.a", "A")
        store.add(z)
        store.add(a)
        store.setSelectedTargetIds(setOf("unknown", a.id))

        assertEquals(setOf(a.id, z.id), store.selectedTargetIds())
        val json = preferences.values.getValue("state")
        assertTrue(json.indexOf(a.id) < json.indexOf(z.id))
    }
}

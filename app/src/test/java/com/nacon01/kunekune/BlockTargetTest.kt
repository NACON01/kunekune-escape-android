package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockTargetTest {
    @Test
    fun appNormalizationAndIdAreDeterministic() {
        val first = BlockTarget.app("  com.example.app  ", "  Example  ")
        val second = BlockTarget.app("com.example.app", "Example")

        assertEquals("com.example.app", first.packageName)
        assertEquals("Example", first.label)
        assertEquals("app:com.example.app", first.id)
        assertEquals(first.id, second.id)
    }

    @Test
    fun domainNormalizesUrlHostAndMatchesOnlyTheHost() {
        val target = BlockTarget.domain("HTTPS://WWW.Example.COM./path", true)

        assertEquals("www.example.com", target.host)
        assertTrue(target.matches("https://www.example.com/other/path"))
        assertTrue(target.matches("https://child.www.example.com/path"))
        assertFalse(target.matches("https://badwww.example.com/path"))
        assertFalse(target.matches("www.example.com.evil.test/path"))
        assertEquals(target.id, BlockTarget.domain("www.example.com", true, target.launchUrl).id)
    }

    @Test
    fun domainRejectsUnsafeOrInvalidInputs() {
        listOf(
            "com",
            "127.0.0.1",
            "https://user:password@example.com",
            "ftp://example.com",
            "https://example.com:not-a-port",
            "https://example.com:99999",
            "https:///missing-host"
        ).forEach { input ->
            try {
                BlockTarget.domain(input, false)
                throw AssertionError("Expected rejection for $input")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun includeSubdomainsChangesStableIdAndExactHostStillMatches() {
        val exact = BlockTarget.domain("example.com", false)
        val descendants = BlockTarget.domain("example.com", true)

        assertNotEquals(exact.id, descendants.id)
        assertTrue(exact.matches("https://example.com/a"))
        assertFalse(exact.matches("https://a.example.com"))
        assertTrue(descendants.matches("https://a.example.com"))
        assertFalse(descendants.matches("https://badexample.com"))
    }
}

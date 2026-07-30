package com.nacon01.kunekune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DomainProtectionPolicyTest {
    private val exact = BlockTarget.domain("example.com", includeSubdomains = false)
    private val subdomains = BlockTarget.domain("example.com", includeSubdomains = true)

    @Test fun blocksExactAndIncludedSubdomainButNotSibling() {
        val selected = setOf(exact.id, subdomains.id)
        assertTrue(DomainProtectionPolicy.decide(inside(), listOf(exact, subdomains), selected, "EXAMPLE.COM.") is DomainProtectionDecision.Block)
        assertTrue(DomainProtectionPolicy.decide(inside(), listOf(exact, subdomains), selected, "a.example.com") is DomainProtectionDecision.Block)
        assertEquals(DomainProtectionDecision.Allowed, DomainProtectionPolicy.decide(inside(), listOf(exact, subdomains), selected, "aexample.com"))
    }

    @Test fun selectedScopeIgnoresUnselectedAndMixedTargets() {
        assertEquals(
            DomainProtectionDecision.NoSelectedDomainTarget,
            DomainProtectionPolicy.decide(inside(), listOf(exact), emptySet(), "example.com")
        )
        assertTrue(
            DomainProtectionPolicy.decide(inside(), listOf(BlockTarget.app("com.example.app", "App"), exact), setOf(exact.id), "example.com")
                is DomainProtectionDecision.Block
        )
    }

    @Test fun grantMustMatchStateGenerationAndTarget() {
        val grant = SessionGrant(7, UUID.randomUUID().toString(), setOf(exact.id), exact.id)
        val active = inside(state = HomeZoneState.GUIDANCE_ACTIVE, grant = grant)
        assertEquals(DomainProtectionDecision.Allowed, DomainProtectionPolicy.decide(active, listOf(exact), setOf(exact.id), "example.com"))
        assertTrue(DomainProtectionPolicy.decide(active.copy(visitGeneration = 8), listOf(exact), setOf(exact.id), "example.com") is DomainProtectionDecision.Block)
        assertTrue(DomainProtectionPolicy.decide(inside(state = HomeZoneState.INSIDE_LOCKED, grant = grant), listOf(exact), setOf(exact.id), "example.com") is DomainProtectionDecision.Block)
    }

    @Test fun overlappingTargetsRemainBlockedUntilAllMatchingTargetsGranted() {
        val broad = BlockTarget.domain("example.com", true)
        val narrow = BlockTarget.domain("a.example.com", true)
        val ids = setOf(broad.id, narrow.id)
        val grant = SessionGrant(7, UUID.randomUUID().toString(), setOf(broad.id), broad.id)
        val decision = DomainProtectionPolicy.decide(inside(HomeZoneState.GUIDANCE_ACTIVE, grant), listOf(broad, narrow), ids, "A.EXAMPLE.COM.")
        assertEquals(setOf(narrow.id), (decision as DomainProtectionDecision.Block).targetIds)

        val allGranted = SessionGrant(7, UUID.randomUUID().toString(), ids, broad.id)
        assertEquals(
            DomainProtectionDecision.Allowed,
            DomainProtectionPolicy.decide(
                inside(HomeZoneState.STARTING_GUIDANCE, allGranted),
                listOf(broad, narrow),
                ids,
                "a.example.com"
            )
        )
    }

    @Test fun outsideUnknownAndInvalidInputAreSafe() {
        assertEquals(DomainProtectionDecision.OutsideOrOff, DomainProtectionPolicy.decide(inside(lastKnownInside = false), listOf(exact), setOf(exact.id), "example.com"))
        assertEquals(DomainProtectionDecision.OutsideOrOff, DomainProtectionPolicy.decide(inside(lastKnownInside = null), listOf(exact), setOf(exact.id), "example.com"))
        assertEquals(DomainProtectionDecision.Allowed, DomainProtectionPolicy.decide(inside(), listOf(exact), setOf(exact.id), "not a host"))
    }

    private fun inside(
        state: HomeZoneState = HomeZoneState.INSIDE_LOCKED,
        grant: SessionGrant? = null,
        lastKnownInside: Boolean? = true
    ) = HomeZoneSnapshot(state, 7, grant, lastKnownInside, false, false)
}

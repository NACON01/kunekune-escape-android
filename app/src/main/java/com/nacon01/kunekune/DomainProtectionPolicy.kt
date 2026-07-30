package com.nacon01.kunekune

/** Decisions made by the selected-domain DNS policy. */
sealed interface DomainProtectionDecision {
    data object OutsideOrOff : DomainProtectionDecision
    data object NoSelectedDomainTarget : DomainProtectionDecision
    data object Allowed : DomainProtectionDecision
    data class Block(
        val hostname: String,
        val targetIds: Set<String>
    ) : DomainProtectionDecision
}

/** Pure policy for DNS hostnames; it has no Android or network dependencies. */
object DomainProtectionPolicy {
    fun decide(
        snapshot: HomeZoneSnapshot,
        configuredTargets: Iterable<BlockTarget>,
        selectedTargetIds: Set<String>,
        queriedHost: String
    ): DomainProtectionDecision {
        if (snapshot.lastKnownInside != true) return DomainProtectionDecision.OutsideOrOff

        val selectedDomains = configuredTargets.filterIsInstance<BlockTarget.Domain>()
            .filter { it.id in selectedTargetIds }
        if (selectedDomains.isEmpty()) return DomainProtectionDecision.NoSelectedDomainTarget

        val matching = selectedDomains.filter { it.matches(queriedHost) }
        if (matching.isEmpty()) return DomainProtectionDecision.Allowed

        val grant = snapshot.sessionGrant?.takeIf {
            snapshot.state == HomeZoneState.STARTING_GUIDANCE ||
                snapshot.state == HomeZoneState.GUIDANCE_ACTIVE
        }?.takeIf { it.visitGeneration == snapshot.visitGeneration }
        val grantedIds = grant?.grantedTargetIds.orEmpty()
        val blocked = matching.filter { it.id !in grantedIds }
        return if (blocked.isEmpty()) {
            DomainProtectionDecision.Allowed
        } else {
            DomainProtectionDecision.Block(
                hostname = queriedHost,
                targetIds = blocked.mapTo(linkedSetOf()) { it.id }
            )
        }
    }
}

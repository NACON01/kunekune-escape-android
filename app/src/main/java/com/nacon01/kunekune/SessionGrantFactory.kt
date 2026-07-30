package com.nacon01.kunekune

/** Pure validation for the subset of configured targets granted to one visit. */
object SessionGrantFactory {
    fun create(
        visitGeneration: Long,
        routeId: String,
        configuredSelectedTargetIds: Set<String>,
        grantedTargetIds: Set<String>,
        initialTargetId: String
    ): SessionGrant {
        require(grantedTargetIds.isNotEmpty()) { "At least one target must be granted" }
        require(grantedTargetIds.all { it in configuredSelectedTargetIds }) {
            "Granted targets must be selected in configuration"
        }
        require(initialTargetId in grantedTargetIds) {
            "Initial target must be granted"
        }
        return SessionGrant(visitGeneration, routeId, grantedTargetIds, initialTargetId)
    }
}

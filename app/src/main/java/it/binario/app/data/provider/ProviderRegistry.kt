package it.binario.app.data.provider

import it.binario.app.data.core.SourceId

data class ProviderPolicy(
    val descriptor: ProviderDescriptor,
    val enabled: Boolean,
    val priorityByCapability: Map<ProviderCapability, Int> = emptyMap()
) {
    init {
        require(priorityByCapability.values.all { it >= 0 }) { "Le priorità non possono essere negative" }
    }

    fun priorityFor(capability: ProviderCapability): Int = priorityByCapability[capability] ?: Int.MAX_VALUE
}

class ProviderRegistry(policies: Collection<ProviderPolicy>) {
    private val policyById = policies.associateBy { it.descriptor.id }

    init {
        require(policyById.size == policies.size) { "Ogni provider deve avere un SourceId univoco" }
    }

    fun policy(sourceId: SourceId): ProviderPolicy? = policyById[sourceId]

    fun activeFor(capability: ProviderCapability): List<ProviderPolicy> = policyById.values
        .asSequence()
        .filter { it.enabled }
        .filter { it.descriptor.accessMode !in setOf(AccessMode.DISABLED, AccessMode.AGREEMENT_REQUIRED) }
        .filter { capability in it.descriptor.capabilities }
        .sortedWith(compareBy<ProviderPolicy> { it.priorityFor(capability) }.thenBy { it.descriptor.id.value })
        .toList()

    fun isEnabled(sourceId: SourceId): Boolean = policyById[sourceId]
        ?.let { it.enabled && it.descriptor.accessMode !in setOf(AccessMode.DISABLED, AccessMode.AGREEMENT_REQUIRED) }
        ?: false

    fun all(): List<ProviderPolicy> = policyById.values.sortedBy { it.descriptor.id.value }
}

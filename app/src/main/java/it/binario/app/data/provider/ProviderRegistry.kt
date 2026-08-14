package it.binario.app.data.provider

import it.binario.app.data.core.SourceId
import java.time.Duration

data class RetryPolicy(
    val maxAttempts: Int = 2,
    val initialBackoff: Duration = Duration.ofMillis(500),
    val maxBackoff: Duration = Duration.ofSeconds(5)
) {
    init {
        require(maxAttempts in 0..3) { "I tentativi devono essere compresi tra 0 e 3" }
        require(!initialBackoff.isNegative && !maxBackoff.isNegative) { "Il backoff non può essere negativo" }
    }
}

data class CircuitBreakerPolicy(
    val failuresBeforeOpen: Int = 5,
    val openDuration: Duration = Duration.ofMinutes(2)
) {
    init {
        require(failuresBeforeOpen > 0) { "La soglia del circuito deve essere positiva" }
        require(!openDuration.isNegative && !openDuration.isZero) { "La durata del circuito deve essere positiva" }
    }
}

data class ProviderPolicy(
    val descriptor: ProviderDescriptor,
    val enabled: Boolean,
    val priorityByCapability: Map<ProviderCapability, Int> = emptyMap(),
    val retry: RetryPolicy = RetryPolicy(),
    val circuitBreaker: CircuitBreakerPolicy = CircuitBreakerPolicy()
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
        .filter { it.descriptor.accessMode != AccessMode.DISABLED }
        .filter { capability in it.descriptor.capabilities }
        .sortedWith(compareBy<ProviderPolicy> { it.priorityFor(capability) }.thenBy { it.descriptor.id.value })
        .toList()

    fun isEnabled(sourceId: SourceId): Boolean = policyById[sourceId]
        ?.let { it.enabled && it.descriptor.accessMode != AccessMode.DISABLED }
        ?: false

    fun all(): List<ProviderPolicy> = policyById.values.sortedBy { it.descriptor.id.value }
}

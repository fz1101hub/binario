package it.binario.app.data.repository

import it.binario.app.data.*
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RailProvider
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Exact public-number matching; category/line labels never turn this into substring search. */
data class TrainNumberQuery(val number: String, val date: LocalDate) {
    val normalized: String = normalize(number)
    val numeric: String? = numeric(normalized)
    val valid: Boolean get() = normalized.length in 1..32 && normalized.all { it.isLetterOrDigit() } && normalized.any(Char::isDigit)

    fun matches(published: String): Boolean {
        val candidate = published.substringAfterLast(" - ").trim()
        return normalized == normalize(published) || normalized == normalize(candidate) ||
            (numeric != null && numeric == numeric(normalize(candidate)))
    }

    companion object {
        private fun normalize(value: String) = value.trim().uppercase(Locale.ROOT).replace(Regex("[\\s-]+"), "")
        private fun numeric(value: String): String? = Regex("(?:(?:FRECCIAROSSA|FRECCIARGENTO|FRECCIABIANCA|REGIONALE|ITALO|REG|RJX|ICE|ICN|TGV|TER|FR|FA|FB|AV|RV|RE|IC|EC|EN|RJ|R))?([0-9]+)")
            .matchEntire(value)?.groupValues?.get(1)?.trimStart('0')?.ifEmpty { "0" }
    }
}

data class TrainNumberMatch(val service: TrainService, val snapshot: RouteSnapshot) {
    val id: String get() = service.id

    companion object {
        fun create(service: TrainService, source: String, confidence: DataConfidence, fetchedAt: Long, expiresAt: Long): TrainNumberMatch {
            val route = SavedRoute("number:${service.id}", "${service.origin} → ${service.destination}",
                Station("number-origin:${service.id}", service.origin, service.origin),
                Station("number-destination:${service.id}", service.destination, service.destination))
            return TrainNumberMatch(service, RouteSnapshot(route, Direction.OUTBOUND, listOf(service), source,
                retrievedAtEpochMillis = fetchedAt, expiresAtEpochMillis = expiresAt,
                sourceObservedAtEpochMillis = service.operationalObservedAtEpochMillis ?: fetchedAt,
                freshness = if (service.operationalStatusVerified) Freshness.FRESH else Freshness.AGING, confidence = confidence))
        }
    }
}

interface TrainNumberSearchProvider : RailProvider {
    suspend fun searchByNumber(query: TrainNumberQuery): ProviderResult<List<TrainNumberMatch>>
}

data class TrainNumberSearchUpdate(val matches: List<TrainNumberMatch>, val complete: Boolean, val unavailableSources: Set<String>)

class TrainNumberSearchService(
    private val providers: List<TrainNumberSearchProvider>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val providerTimeoutMillis: Long = 45_000L
) {
    fun search(query: TrainNumberQuery): Flow<TrainNumberSearchUpdate> = flow {
        if (!query.valid || providers.isEmpty()) {
            emit(TrainNumberSearchUpdate(emptyList(), true, providers.map { it.descriptor.displayName }.toSet()))
            return@flow
        }
        coroutineScope {
            val completed = Channel<Pair<Int, ProviderResult<List<TrainNumberMatch>>>>(providers.size)
            providers.forEachIndexed { index, provider ->
                launch {
                    val result = try {
                        withTimeout(providerTimeoutMillis) { provider.searchByNumber(query) }
                    } catch (_: TimeoutCancellationException) {
                        failure()
                    } catch (error: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        failure()
                    } catch (_: Exception) { failure() }
                    completed.send(index to result)
                }
            }
            val byProvider = mutableMapOf<Int, List<TrainNumberMatch>>()
            val errors = linkedSetOf<String>()
            repeat(providers.size) { count ->
                val (index, result) = completed.receive()
                when (result) {
                    is ProviderResult.Success -> byProvider[index] = result.value
                    is ProviderResult.Partial -> { byProvider[index] = result.value; errors += providers[index].descriptor.displayName }
                    else -> errors += providers[index].descriptor.displayName
                }
                val matches = byProvider.toSortedMap().values.flatten().filter { query.matches(it.service.number) }
                    .groupBy { it.id }.values.map { group -> group.maxWith(compareBy<TrainNumberMatch> { it.service.operationalStatusVerified }.thenBy { it.service.operationalObservedAtEpochMillis ?: 0L }) }.sortedWith(compareBy<TrainNumberMatch> { it.service.scheduledDepartureDateTime }.thenBy { it.service.origin }.thenBy { it.snapshot.providerName })
                emit(TrainNumberSearchUpdate(matches, count == providers.lastIndex, errors.toSet()))
            }
        }
    }.flowOn(dispatcher)

    private fun failure() = ProviderResult.Failure(it.binario.app.data.provider.ProviderError(
        it.binario.app.data.provider.ProviderErrorKind.UPSTREAM_UNAVAILABLE, "Ricerca per numero non disponibile da questa fonte."))
}

package it.binario.app.data.notices

import it.binario.app.data.RailOperator
import it.binario.app.data.RailTime
import it.binario.app.data.TrainService
import it.binario.app.data.core.TrainNotice
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import it.binario.app.data.provider.TrainNoticeProvider
import it.binario.app.data.provider.TrainNoticeRequest
import it.binario.app.data.repository.TrainNumberQuery
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** What the train detail knows about the service whose notices are requested. */
data class TrainNoticeContext(
    val publicNumber: String,
    val serviceDate: LocalDate,
    val originCode: String?,
    val operator: RailOperator,
    val stationNames: List<String>
) {
    val key: String get() = listOf(publicNumber, serviceDate, originCode.orEmpty(), operator.name).joinToString("|")

    val isTrenitalia: Boolean get() = operator in TRENITALIA

    companion object {
        private val TRENITALIA = setOf(RailOperator.TRENITALIA_REGIONALE, RailOperator.TRENITALIA_LONG_DISTANCE, RailOperator.TRENITALIA_AV)
        private val VIAGGIATRENO_RUN = Regex("^viaggiatreno:(S[0-9]{5}):([0-9]{1,6}):([0-9]{10,16})(?::.*)?$")

        /**
         * The ViaggiaTreno run identity (`viaggiatreno:<origin>:<number>:<service day ms>`) gives the exact origin and
         * service day; other services use their scheduled departure date. Returns null without a numeric train number.
         */
        fun from(service: TrainService, today: LocalDate = LocalDate.now(RailTime.defaultZone)): TrainNoticeContext? {
            val run = VIAGGIATRENO_RUN.matchEntire(service.id)
            val serviceDate = run?.groupValues?.get(3)?.toLongOrNull()
                ?.let { Instant.ofEpochMilli(it).atZone(RailTime.defaultZone).toLocalDate() }
                ?: service.scheduledDepartureDateTime?.toLocalDate()
                ?: today
            val number = run?.groupValues?.get(2)?.trimStart('0')
                ?: TrainNumberQuery(service.number.substringAfterLast(" - "), serviceDate).numeric
                ?: return null
            if (number.isEmpty() || number == "0" || number.length > 6) return null
            return TrainNoticeContext(
                publicNumber = number,
                serviceDate = serviceDate,
                originCode = run?.groupValues?.get(1),
                operator = service.operator,
                stationNames = (listOf(service.origin) + service.stops.map { it.station } + listOf(service.destination))
                    .map(String::trim).filter(String::isNotBlank).distinct()
            )
        }
    }
}

/** Notices shown in a train detail; [unavailable] tells the UI that a queried source answered with an error. */
data class TrainDetailNotices(
    val trainNotices: List<TrainNotice> = emptyList(),
    val unavailable: Boolean = false,
    val stale: Boolean = false
)

/**
 * Collects the notices of the enabled public sources for the UI. It never throws: a failed source is reported
 * through [TrainDetailNotices.unavailable] and its notices are simply absent.
 */
class RailNoticesService(
    private val trainNoticeProviders: List<TrainNoticeProvider>,
    private val sourceTimeoutMillis: Long = 20_000
) {
    val hasTrainSources: Boolean get() = trainNoticeProviders.isNotEmpty()

    suspend fun forTrain(context: TrainNoticeContext): TrainDetailNotices = coroutineScope {
        // Per-train notices are a Trenitalia service; other operators' numbers would only match by coincidence.
        val trainNotices = if (context.isTrenitalia || context.originCode != null) {
            trainNoticeProviders.map { provider ->
                async { read { provider.getTrainNotices(TrainNoticeRequest(context.publicNumber, context.serviceDate, context.originCode)) } }
            }
        } else emptyList()
        val reads = trainNotices.awaitAll()
        TrainDetailNotices(
            trainNotices = reads.flatMap { it.value.orEmpty() }.distinctBy { it.id }
                .sortedByDescending { it.publishedAt ?: Instant.EPOCH },
            unavailable = reads.any { it.failed },
            stale = reads.any { it.stale }
        )
    }

    private suspend fun <T> read(call: suspend () -> ProviderResult<T>): Read<T> = try {
        when (val result = withTimeoutOrNull(sourceTimeoutMillis) { call() }) {
            is ProviderResult.Success -> Read(result.value)
            is ProviderResult.Partial -> Read(result.value, stale = ProviderWarning.STALE_SOURCE in result.warnings)
            is ProviderResult.NotModified -> Read(null)
            is ProviderResult.Failure, null -> Read(null, failed = true)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        Read(null, failed = true)
    }

    private data class Read<T>(val value: T?, val failed: Boolean = false, val stale: Boolean = false)
}

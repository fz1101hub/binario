package it.binario.app.data.notices

import it.binario.app.data.RailOperator
import it.binario.app.data.RailTime
import it.binario.app.data.TrainService
import it.binario.app.data.core.ServiceAlert
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.TrainNotice
import it.binario.app.data.live.TrenordLine
import it.binario.app.data.live.TrenordLineNotices
import it.binario.app.data.live.TrenordLineNoticesAdapter
import it.binario.app.data.provider.AlertProvider
import it.binario.app.data.provider.AlertRequest
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
    val stationNames: List<String>,
    /** Boarding and alighting stations shown in the detail. */
    val endpoints: List<String> = emptyList(),
    /** Set by the caller from catalogue coordinates: one endpoint lies in the Trenord service area. */
    val nearTrenordNetwork: Boolean = false
) {
    val trenordOperated: Boolean get() = operator == RailOperator.TRENORD

    val key: String get() = listOf(publicNumber, serviceDate, originCode.orEmpty(), operator.name, endpoints.joinToString("/"), nearTrenordNetwork).joinToString("|")

    /** Trenitalia notices are matched by train number only for Trenitalia services or ViaggiaTreno runs. */
    val matchesTrenitaliaNumbers: Boolean get() = operator in TRENITALIA || originCode != null

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
                    .map(String::trim).filter(String::isNotBlank).distinct(),
                endpoints = listOf(service.origin, service.destination).map(String::trim).filter(String::isNotBlank).distinct()
            )
        }
    }
}

enum class NoticePublisher { RFI, TRENITALIA, TRENORD }

/** A publisher's line: code and published name (Trenord `D034`, `LAVENO-VARESE-SARONNO-MILANO`). */
data class NoticeLine(val code: String, val name: String)

/** A network notice ready for display: plain text only, with the publisher's own link when it has one. */
data class RailNotice(
    val id: String,
    val publisher: NoticePublisher,
    val title: String,
    val body: String?,
    val publishedAt: Instant?,
    val link: String?,
    val lines: List<NoticeLine> = emptyList()
)

/** Trenord notices of one set of lines; [allLines] marks notices published on (almost) every line. */
data class NoticeGroup(val lines: List<NoticeLine>, val allLines: Boolean, val notices: List<RailNotice>)

/** Notices shown in a train detail; [unavailable] tells the UI that a queried source answered with an error. */
data class TrainDetailNotices(
    val trainNotices: List<TrainNotice> = emptyList(),
    val networkNotices: List<RailNotice> = emptyList(),
    val trenordNotices: List<RailNotice> = emptyList(),
    val unavailable: Boolean = false,
    val stale: Boolean = false
) {
    val isEmpty: Boolean get() = trainNotices.isEmpty() && networkNotices.isEmpty() && trenordNotices.isEmpty()
}

/** The general notices area: RFI and Trenitalia notices deduplicated by title, Trenord notices grouped by line. */
data class GeneralNotices(
    val network: List<RailNotice> = emptyList(),
    val trenordGroups: List<NoticeGroup> = emptyList(),
    val unavailable: Set<NoticePublisher> = emptySet(),
    val stale: Boolean = false
)

/** Trenord notices for one station board. */
data class StationNotices(val notices: List<RailNotice> = emptyList(), val unavailable: Boolean = false, val stale: Boolean = false)

/**
 * Collects the notices of the enabled public sources for the UI. It never throws: a failed source is reported
 * as unavailable and its notices are simply absent; a stale answer is flagged.
 */
class RailNoticesService(
    private val trainNoticeProviders: List<TrainNoticeProvider>,
    private val networkAlertProviders: List<AlertProvider> = emptyList(),
    private val trenord: TrenordLineNoticesAdapter? = null,
    private val sourceTimeoutMillis: Long = 20_000
) {
    val hasTrainSources: Boolean
        get() = trainNoticeProviders.isNotEmpty() || trenord != null ||
            networkAlertProviders.any { publisherOf(it.descriptor.id) == NoticePublisher.TRENITALIA }

    val hasNetworkSources: Boolean get() = networkAlertProviders.isNotEmpty() || trenord != null

    val hasTrenordSource: Boolean get() = trenord != null

    suspend fun forTrain(context: TrainNoticeContext): TrainDetailNotices = coroutineScope {
        val trenitalia = context.matchesTrenitaliaNumbers
        // Per-train notices are a Trenitalia service; other operators' numbers would only match by coincidence.
        val trainReads = if (trenitalia) trainNoticeProviders.map { provider ->
            async { read { provider.getTrainNotices(TrainNoticeRequest(context.publicNumber, context.serviceDate, context.originCode)) } }
        } else emptyList()
        val newsReads = if (trenitalia) networkAlertProviders.filter { publisherOf(it.descriptor.id) == NoticePublisher.TRENITALIA }
            .map { provider -> async { provider.descriptor.id to read { provider.getAlerts(AlertRequest()) } } }
        else emptyList()
        // Trenord is asked only for its own trains or for a route inside its service area.
        val trenordRead = trenord?.takeIf { context.trenordOperated || context.nearTrenordNetwork }
            ?.let { source -> async { read { source.getLineNotices() } } }
        val trains = trainReads.awaitAll()
        val news = newsReads.awaitAll()
        val lineNotices = trenordRead?.await()
        TrainDetailNotices(
            trainNotices = trains.flatMap { it.value.orEmpty() }.distinctBy { it.id }
                .sortedByDescending { it.publishedAt ?: Instant.EPOCH },
            networkNotices = news.flatMap { (id, read) ->
                read.value.orEmpty().filter { context.publicNumber in it.affectedTrainNumbers }.mapNotNull { it.toNotice(id) }
            }.let(::newestFirstWithoutDuplicates),
            trenordNotices = lineNotices?.value?.let { value ->
                val related = TrenordLineMatcher.linesForTrain(value.lines, context).mapTo(hashSetOf()) { it.code }
                value.alerts.filter { alert -> alert.affectedLines.any(related::contains) }.mapNotNull { it.toNotice(value.lines) }
            }.orEmpty(),
            unavailable = trains.any { it.failed } || news.any { it.second.failed } || lineNotices?.failed == true,
            stale = trains.any { it.stale } || news.any { it.second.stale } || lineNotices?.stale == true
        )
    }

    suspend fun forStation(stationName: String): StationNotices {
        val source = trenord ?: return StationNotices()
        val read = read { source.getLineNotices() }
        val value = read.value ?: return StationNotices(unavailable = read.failed)
        return StationNotices(
            notices = TrenordLineMatcher.alertsForStation(value.lines, value.alerts, stationName).mapNotNull { it.toNotice(value.lines) },
            unavailable = read.failed,
            stale = read.stale
        )
    }

    private fun groupByLines(value: TrenordLineNotices): List<NoticeGroup> {
        val byCode = value.lines.associateBy { it.code }
        val groups = value.alerts.groupBy { alert -> alert.affectedLines.sorted() }
        return groups.mapNotNull { (codes, alerts) ->
            val notices = alerts.mapNotNull { it.toNotice(value.lines) }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            NoticeGroup(
                lines = codes.map { code -> NoticeLine(code, byCode[code]?.description.orEmpty()) },
                allLines = alerts.first().let { TrenordLineMatcher.isNetworkWide(it, value.lines) },
                notices = notices
            )
        }.sortedWith(compareByDescending<NoticeGroup> { it.allLines }.thenByDescending { it.lines.size }.thenBy { it.lines.firstOrNull()?.code.orEmpty() })
    }

    private fun ServiceAlert.toNotice(lines: List<TrenordLine>): RailNotice? {
        val byCode = lines.associateBy { it.code }
        return toNotice(TrenordLineNoticesAdapter.SOURCE_ID)?.copy(
            lines = affectedLines.sorted().map { code -> NoticeLine(code, byCode[code]?.description.orEmpty()) }
        )
    }

    suspend fun general(): GeneralNotices = coroutineScope {
        val trenordRead = trenord?.let { source -> async { read { source.getLineNotices() } } }
        val reads = networkAlertProviders.map { provider -> async { provider.descriptor.id to read { provider.getAlerts(AlertRequest()) } } }.awaitAll()
        val lineNotices = trenordRead?.await()
        GeneralNotices(
            network = newestFirstWithoutDuplicates(reads.flatMap { (id, read) -> read.value.orEmpty().mapNotNull { it.toNotice(id) } }),
            trenordGroups = lineNotices?.value?.let(::groupByLines).orEmpty(),
            unavailable = (reads.filter { it.second.failed }.mapNotNull { publisherOf(it.first) } +
                listOfNotNull(NoticePublisher.TRENORD.takeIf { lineNotices?.failed == true })).toSet(),
            stale = reads.any { it.second.stale } || lineNotices?.stale == true
        )
    }

    /** The first notice of each title wins in publisher order (RFI before Trenitalia); then newest first. */
    private fun newestFirstWithoutDuplicates(notices: List<RailNotice>): List<RailNotice> = notices
        .sortedBy { it.publisher.ordinal }
        .distinctBy { NoticeText.dedupKey(it.title) }
        .sortedWith(compareByDescending<RailNotice> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.publisher.ordinal })

    private fun ServiceAlert.toNotice(sourceId: SourceId): RailNotice? {
        val publisher = publisherOf(sourceId) ?: return null
        val cleanTitle = NoticeText.normalize(title.value, 300) ?: return null
        return RailNotice(
            id = id.value,
            publisher = publisher,
            title = cleanTitle,
            // RFI RSS descriptions can carry markup; Trenitalia's are already plain text.
            body = description?.value?.let { NoticeText.fromHtml(it, 6_000) }?.takeIf { NoticeText.dedupKey(it) != NoticeText.dedupKey(cleanTitle) },
            publishedAt = validFrom,
            link = link
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

    companion object {
        fun publisherOf(sourceId: SourceId): NoticePublisher? = when (sourceId.value) {
            "rfi-infomobility-rss" -> NoticePublisher.RFI
            "trenitalia-infomobility-news" -> NoticePublisher.TRENITALIA
            "trenord-line-notices" -> NoticePublisher.TRENORD
            else -> null
        }
    }
}

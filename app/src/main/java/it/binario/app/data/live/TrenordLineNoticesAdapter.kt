package it.binario.app.data.live

import it.binario.app.data.core.AlertId
import it.binario.app.data.core.AlertSeverity
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.LocalizedText
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.ServiceAlert
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.notices.NoticeText
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.AlertProvider
import it.binario.app.data.provider.AlertRequest
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RatePolicy
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A Trenord line ("direttrice") as published: code (`D034`) and the stations of its name, in order. */
data class TrenordLine(val code: String, val description: String) {
    /** Normalised station names of the description (`LAVENO-VARESE-SARONNO-MILANO` → LAVENO, VARESE, …). */
    val stationTokens: List<String> = description.split('-', '/', '–')
        .map { TrenordLineNoticesAdapter.normalizeStationName(it) }
        .filter { it.length >= 2 }
        .distinct()
}

data class TrenordLineNotices(val lines: List<TrenordLine>, val alerts: List<ServiceAlert>)

/**
 * Trenord line notices (`mia/direttrici`): every line with its news. Best effort, HTTPS, 15 minute TTL.
 *
 * The same news is repeated under many lines (a strike notice appears on all of them): it becomes one alert
 * whose [ServiceAlert.affectedLines] lists every line. Promotional items are dropped by [isOperational].
 */
class TrenordLineNoticesAdapter internal constructor(
    private val fetcher: PublicSourceFetcher,
    private val clock: Clock,
    retryDelayMillis: Long
) : AlertProvider {
    constructor(clock: Clock = Clock.systemUTC()) : this(
        HttpPublicSourceFetcher(PublicSourceRequestPolicy(maxBytes = MAX_BYTES)), clock, 750
    )

    override val descriptor = ProviderDescriptor(
        id = SOURCE_ID,
        displayName = "Trenord — avvisi per direttrice",
        capabilities = setOf(ProviderCapability.SERVICE_ALERTS),
        coverage = CoverageDescriptor(countryCodes = setOf("IT", "CH"), description = "Avvisi Trenord per linea (direttrice)"),
        accessMode = AccessMode.PUBLIC_BEST_EFFORT,
        ratePolicy = RatePolicy(Duration.ofMinutes(10), defaultTimeToLive = TTL),
        schemaVersion = 1
    )

    private val cache = ResilientSourceCache<ParsedLines>(TTL, Duration.ofHours(2), clock, maxEntries = 1, retryDelayMillis = retryDelayMillis)

    override suspend fun getAlerts(request: AlertRequest): ProviderResult<List<ServiceAlert>> = when (val result = getLineNotices()) {
        is ProviderResult.Success -> ProviderResult.Success(result.value.alerts, result.meta)
        is ProviderResult.Partial -> ProviderResult.Partial(result.value.alerts, result.warnings, result.meta)
        is ProviderResult.NotModified -> result
        is ProviderResult.Failure -> result
    }

    suspend fun getLineNotices(): ProviderResult<TrenordLineNotices> = withContext(Dispatchers.Default) {
        cache.get(URL) { parse(fetcher.get(URL)) }
            .toProviderResult(SOURCE_ID, UNAVAILABLE, UNREADABLE) { parsed, fetchedAt -> parsed.toNotices(fetchedAt) }
    }

    internal fun parse(body: String): ParsedLines {
        val trimmed = body.trim()
        if (!trimmed.startsWith("[")) throw PublicSourceFormatException("Elenco direttrici atteso")
        val array = JSONArray(trimmed)
        val lines = mutableListOf<TrenordLine>()
        val news = LinkedHashMap<String, ParsedNews>()
        for (index in 0 until minOf(array.length(), MAX_LINES)) {
            val item = array.optJSONObject(index) ?: continue
            val code = item.text("nome")?.take(16)?.takeIf { it.matches(LINE_CODE) } ?: continue
            val line = TrenordLine(code, item.text("descrizione")?.take(200).orEmpty())
            lines += line
            val items = item.optJSONArray("news") ?: continue
            for (newsIndex in 0 until minOf(items.length(), MAX_NEWS_PER_LINE)) {
                val entry = items.optJSONObject(newsIndex) ?: continue
                val text = NoticeText.fromHtml(entry.text("description"), MAX_TEXT) ?: continue
                if (!isOperational(text)) continue
                val date = entry.text("date")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val key = "${NoticeText.dedupKey(text)}|${date?.epochSecond ?: 0}"
                val existing = news[key]
                news[key] = existing?.copy(lines = existing.lines + code) ?: ParsedNews(text, date, entry.text("severity_description"), linkedSetOf(code))
            }
        }
        return ParsedLines(lines.distinctBy { it.code }, news.values.toList())
    }

    private fun ParsedLines.toNotices(fetchedAt: Instant): TrenordLineNotices = TrenordLineNotices(
        lines = lines,
        alerts = news.map { item ->
            val hash = sha256("${item.text}|${item.date?.epochSecond ?: 0}").take(24)
            ServiceAlert(
                id = AlertId("${SOURCE_ID.value}:$hash"),
                title = LocalizedText(summary(item.text)),
                description = LocalizedText(item.text),
                severity = severityOf(item.text),
                validFrom = item.date,
                affectedOperators = setOf(OperatorId("trenord")),
                source = SourceRef(
                    sourceId = SOURCE_ID, nativeId = hash, observedAt = item.date, fetchedAt = fetchedAt,
                    expiresAt = fetchedAt.plus(TTL), confidence = Confidence.OPERATOR_PRIMARY, traceId = "${SOURCE_ID.value}:$hash"
                ),
                affectedLines = item.lines
            )
        }.sortedByDescending { it.validFrom ?: Instant.EPOCH }
    )

    /** First sentence (or line) of the notice, bounded, used as its title. */
    private fun summary(text: String): String {
        val firstLine = text.lineSequence().first().trim()
        val sentenceEnd = Regex("[.:!?](\\s|$)").find(firstLine)?.range?.first
        val sentence = sentenceEnd?.let { firstLine.substring(0, it + 1) } ?: firstLine
        return if (sentence.length <= MAX_TITLE) sentence else sentence.take(MAX_TITLE - 1).trimEnd() + "…"
    }

    private fun severityOf(text: String): AlertSeverity = when {
        Regex("soppress|sospes|interrott|interruz|cancellat", RegexOption.IGNORE_CASE).containsMatchIn(text) -> AlertSeverity.SEVERE
        Regex("ritard|rallent|modific|variazion|scioper|sostitu", RegexOption.IGNORE_CASE).containsMatchIn(text) -> AlertSeverity.WARNING
        else -> AlertSeverity.INFO
    }

    private fun JSONObject.text(key: String): String? =
        opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    internal data class ParsedNews(val text: String, val date: Instant?, val severity: String?, val lines: Set<String>)
    internal data class ParsedLines(val lines: List<TrenordLine>, val news: List<ParsedNews>)

    companion object {
        val SOURCE_ID = SourceId("trenord-line-notices")
        internal const val URL = "https://www.trenord.it/mgmt/store-management-api/mia/direttrici/"
        private val TTL: Duration = Duration.ofMinutes(15)
        private const val MAX_BYTES = 1024 * 1024
        private const val MAX_LINES = 300
        private const val MAX_NEWS_PER_LINE = 100
        private const val MAX_TEXT = 4_000
        private const val MAX_TITLE = 160
        private val LINE_CODE = Regex("[A-Za-z0-9_-]{1,16}")
        private const val UNAVAILABLE = "Gli avvisi Trenord non sono raggiungibili."
        private const val UNREADABLE = "Gli avvisi Trenord non sono leggibili."

        /**
         * Promotional filter. A notice is kept only when its text, ignoring case and accents, contains one of these
         * operational stems: `scioper` (strike), `soppress`, `ritard`, `lavori`, `variazion`, `sostitui`/`sostitutiv`,
         * `interruz`/`interrott`, `circolazion`, `sospes`, `cancellat`, `limitazion`, `guast`, `deviat`, `modific`,
         * `autobus`, or the phrases "servizio (di) bus", "corse (di/con) bus" and "bus tra/fra". A bare "bus" is
         * deliberately not a keyword: Trenord's real "Ricaricami" ticket promotion says "treni, metro, tram e bus",
         * while operational notices say "autobus", "bus sostitutivi", "sostituiti con bus" or "servizio bus tra …".
         */
        internal fun isOperational(text: String): Boolean = OPERATIONAL.containsMatchIn(stripAccents(text))

        private val OPERATIONAL = Regex(
            "scioper|soppress|ritard|lavori|variazion|sostitui|sostitutiv|interruz|interrott|circolazion|sospes|cancellat|" +
                "limitazion|guast|deviat|modific|autobus|servizi[oa]? (di )?bus|corse (di |con )?bus|\\bbus (tra|fra)\\b",
            RegexOption.IGNORE_CASE
        )

        private fun stripAccents(value: String): String =
            java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

        internal fun normalizeStationName(value: String): String = stripAccents(value)
            .uppercase(Locale.ROOT)
            .replace(Regex("\\bS\\.\\s*"), "SAN ")
            .replace(Regex("[^A-Z0-9]+"), " ")
            .trim()
    }
}

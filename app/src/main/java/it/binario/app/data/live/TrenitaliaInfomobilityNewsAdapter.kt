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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trenitalia infomobility news as published by ViaggiaTreno (`news/infomobility`): national and regional notices
 * with the train numbers they concern. Best effort in the ViaggiaTreno family; the HTTPS endpoint redirects to the
 * same resource over HTTP on `www.viaggiatreno.it`, the single cleartext domain allowed, and nothing else is
 * followed. The HTML descriptions arrive entity-escaped twice: entities are decoded, then jsoup reduces the
 * markup to plain text.
 */
class TrenitaliaInfomobilityNewsAdapter internal constructor(
    private val fetcher: PublicSourceFetcher,
    private val clock: Clock,
    retryDelayMillis: Long
) : AlertProvider {
    constructor(clock: Clock = Clock.systemUTC()) : this(
        HttpPublicSourceFetcher(PublicSourceRequestPolicy(maxBytes = MAX_BYTES, allowViaggiaTrenoCleartextRedirect = true)), clock, 750
    )

    override val descriptor = ProviderDescriptor(
        id = SOURCE_ID,
        displayName = "Trenitalia — Infomobilità (ViaggiaTreno)",
        capabilities = setOf(ProviderCapability.SERVICE_ALERTS),
        coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Notizie di circolazione Trenitalia con numeri treno interessati"),
        accessMode = AccessMode.PUBLIC_BEST_EFFORT,
        ratePolicy = RatePolicy(Duration.ofMinutes(5), defaultTimeToLive = TTL),
        schemaVersion = 1
    )

    private val cache = ResilientSourceCache<List<ParsedNews>>(TTL, Duration.ofHours(1), clock, maxEntries = 1, retryDelayMillis = retryDelayMillis)

    override suspend fun getAlerts(request: AlertRequest): ProviderResult<List<ServiceAlert>> = withContext(Dispatchers.Default) {
        cache.get(URL) { parse(fetcher.get(URL)) }
            .toProviderResult(SOURCE_ID, UNAVAILABLE, UNREADABLE) { news, fetchedAt -> news.map { it.toAlert(fetchedAt) } }
    }

    internal fun parse(body: String): List<ParsedNews> {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed == "null") return emptyList()
        if (!trimmed.startsWith("[")) throw PublicSourceFormatException("Elenco notizie atteso")
        val array = JSONArray(trimmed)
        return (0 until minOf(array.length(), MAX_ITEMS)).mapNotNull { index -> array.optJSONObject(index)?.let(::parseItem) }
    }

    private fun parseItem(item: JSONObject): ParsedNews? {
        val title = NoticeText.decodeEntities(item.text("title"), MAX_TITLE) ?: return null
        val description = NoticeText.fromDoubleEscapedHtml(item.text("description"), MAX_DESCRIPTION)
        val published = item.millis("pubDate")?.let(Instant::ofEpochMilli)
        val trainTags = item.optJSONArray("trainTags")?.let { tags ->
            (0 until minOf(tags.length(), 200)).mapNotNull { tags.opt(it)?.toString()?.trim()?.trimStart('0')?.takeIf { tag -> tag.matches(TRAIN_TAG) } }
        }.orEmpty().toSet()
        return ParsedNews(
            title = title,
            description = description,
            publishedAt = published,
            link = item.text("link")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.take(500),
            trainNumbers = trainTags
        )
    }

    private fun ParsedNews.toAlert(fetchedAt: Instant): ServiceAlert {
        val hash = sha256("$title|${publishedAt?.toEpochMilli() ?: 0}").take(24)
        return ServiceAlert(
            id = AlertId("${SOURCE_ID.value}:$hash"),
            title = LocalizedText(title),
            description = description?.let(::LocalizedText),
            severity = severityOf("$title ${description.orEmpty()}"),
            validFrom = publishedAt,
            affectedOperators = setOf(OperatorId("trenitalia")),
            source = SourceRef(
                sourceId = SOURCE_ID, nativeId = hash, observedAt = publishedAt, fetchedAt = fetchedAt,
                expiresAt = fetchedAt.plus(TTL), confidence = Confidence.OPERATOR_PRIMARY, traceId = "${SOURCE_ID.value}:$hash"
            ),
            affectedTrainNumbers = trainNumbers,
            link = link
        )
    }

    private fun severityOf(text: String): AlertSeverity = when {
        Regex("soppress|sospes|interrott|cancellat", RegexOption.IGNORE_CASE).containsMatchIn(text) -> AlertSeverity.SEVERE
        Regex("ritard|rallent|modific|variazion|scioper", RegexOption.IGNORE_CASE).containsMatchIn(text) -> AlertSeverity.WARNING
        else -> AlertSeverity.INFO
    }

    private fun JSONObject.text(key: String): String? =
        opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JSONObject.millis(key: String): Long? = opt(key)?.takeIf { it != JSONObject.NULL }?.let {
        when (it) {
            is Number -> it.toLong()
            else -> it.toString().trim().toLongOrNull()
        }
    }?.takeIf { it > 0 }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    internal data class ParsedNews(
        val title: String,
        val description: String?,
        val publishedAt: Instant?,
        val link: String?,
        val trainNumbers: Set<String>
    )

    companion object {
        val SOURCE_ID = SourceId("trenitalia-infomobility-news")
        internal const val URL = "https://www.viaggiatreno.it/infomobilitamobile/resteasy/news/infomobility"
        private val TTL: Duration = Duration.ofMinutes(10)
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_ITEMS = 300
        private const val MAX_TITLE = 300
        private const val MAX_DESCRIPTION = 6_000
        private val TRAIN_TAG = Regex("[1-9][0-9]{0,5}")
        private const val UNAVAILABLE = "L'infomobilità Trenitalia non è raggiungibile."
        private const val UNREADABLE = "L'infomobilità Trenitalia non è leggibile."
    }
}

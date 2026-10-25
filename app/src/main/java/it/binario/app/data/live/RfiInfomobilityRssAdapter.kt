package it.binario.app.data.live

import it.binario.app.data.core.AlertId
import it.binario.app.data.core.AlertSeverity
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.LocalizedText
import it.binario.app.data.core.ServiceAlert
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.AlertProvider
import it.binario.app.data.provider.AlertRequest
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderError
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import it.binario.app.data.provider.RatePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

fun interface RfiRssFetcher {
    suspend fun fetch(url: String): String
}

internal class HttpRfiRssFetcher(private val maxBytes: Int = 2 * 1024 * 1024) : RfiRssFetcher {
    override suspend fun fetch(url: String): String = withTimeoutOrNull(25_000) {
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as? HttpURLConnection
                ?: throw IOException("Il feed RFI non usa una connessione HTTP")
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
            connection.setRequestProperty("User-Agent", "Binario/0.15 (personal rail information client)")
            try {
                if (connection.responseCode !in 200..299) throw IOException("RFI RSS ha risposto HTTP ${connection.responseCode}")
                // The feed is now read from the notices area: a body is bounded instead of read whole into memory.
                connection.inputStream.use { readBoundedUtf8(it, connection.contentLengthLong.takeIf { n -> n >= 0 }, maxBytes) }
            } finally {
                connection.disconnect()
            }
        }
    } ?: throw java.net.SocketTimeoutException("RFI RSS supera il tempo massimo della richiesta")
}

/** Feed ufficiale RFI per aggiornamenti nazionali di infomobilità. */
class RfiInfomobilityRssAdapter(
    private val fetcher: RfiRssFetcher = HttpRfiRssFetcher(),
    private val clock: Clock = Clock.systemUTC(),
    private val cacheTtl: Duration = Duration.ofMinutes(10)
) : AlertProvider {
    override val descriptor = ProviderDescriptor(
        id = SOURCE_ID,
        displayName = "RFI — Infomobilità RSS",
        capabilities = setOf(ProviderCapability.SERVICE_ALERTS),
        coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Avvisi RFI nazionali e regionali"),
        accessMode = AccessMode.OPEN_DIRECT,
        ratePolicy = RatePolicy(Duration.ofMinutes(5), defaultTimeToLive = cacheTtl, supportsConditionalRequests = true),
        schemaVersion = 1
    )

    @Volatile
    private var cached: CachedAlerts? = null
    private val breaker = SourceCircuitBreaker(clock)
    private val refresh = kotlinx.coroutines.sync.Mutex()

    override suspend fun getAlerts(request: AlertRequest): ProviderResult<List<ServiceAlert>> {
        cachedResult(clock.instant())?.let { return it }
        // Concurrent callers share one request: the second waits and reads the cache the first filled.
        return refresh.withLock {
            val now = clock.instant()
            cachedResult(now) ?: load(now)
        }
    }

    private fun cachedResult(now: Instant): ProviderResult<List<ServiceAlert>>? = cached?.takeIf { now.isBefore(it.expiresAt) }?.let {
        ProviderResult.Success(it.alerts, ProviderResponseMeta(SOURCE_ID, now, observedAt = it.observedAt, expiresAt = it.expiresAt, fromCache = true))
    }

    private suspend fun load(now: Instant): ProviderResult<List<ServiceAlert>> {
        if (!breaker.allowRequest()) return staleOr(ProviderError(ProviderErrorKind.UPSTREAM_UNAVAILABLE, "Feed RFI Infomobilità sospeso dopo errori ripetuti."))
        return try {
            val xml = fetcher.fetch(NATIONAL_UPDATES_URL)
            val alerts = parseUpdates(xml, now)
            val expiresAt = now.plus(cacheTtl)
            cached = CachedAlerts(alerts, now, expiresAt)
            breaker.recordSuccess()
            ProviderResult.Success(alerts, ProviderResponseMeta(SOURCE_ID, now, observedAt = now, expiresAt = expiresAt, responseFingerprint = sha256(xml)))
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: IOException) {
            breaker.recordFailure()
            staleOr(ProviderError(ProviderErrorKind.NETWORK, "Feed RFI Infomobilità non raggiungibile.", error::class.java.name))
        } catch (error: Exception) {
            breaker.recordFailure()
            staleOr(ProviderError(ProviderErrorKind.PARSE_ERROR, "Feed RFI Infomobilità non leggibile.", error::class.java.name))
        }
    }

    /** The last feed, if at most an hour past its TTL, marked stale; otherwise the typed failure. */
    private fun staleOr(error: ProviderError): ProviderResult<List<ServiceAlert>> {
        val last = cached?.takeIf { !clock.instant().isAfter(it.expiresAt.plus(Duration.ofHours(1))) }
            ?: return ProviderResult.Failure(error)
        return ProviderResult.Partial(last.alerts, setOf(ProviderWarning.STALE_SOURCE),
            ProviderResponseMeta(SOURCE_ID, last.observedAt, observedAt = last.observedAt, fromCache = true))
    }

    internal fun parseUpdates(xml: String, fetchedAt: Instant): List<ServiceAlert> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        return (0 until document.getElementsByTagName("item").length).mapNotNull { index ->
            val item = document.getElementsByTagName("item").item(index) as? Element ?: return@mapNotNull null
            val title = item.textOf("title").trim()
            val description = item.textOf("description").trim().takeIf { it.isNotBlank() }
            val link = item.textOf("link").trim()
            if (title.isBlank() || link.isBlank()) return@mapNotNull null
            val published = item.textOf("pubDate").trim().takeIf { it.isNotEmpty() }
                ?.let { runCatching { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
            ServiceAlert(
                id = AlertId("rfi-rss:${sha256(link)}"),
                title = LocalizedText(title),
                description = description?.let(::LocalizedText),
                severity = severityOf("$title $description"),
                validFrom = published,
                link = link.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                source = SourceRef(
                    sourceId = SOURCE_ID,
                    nativeId = link,
                    observedAt = fetchedAt,
                    fetchedAt = fetchedAt,
                    expiresAt = fetchedAt.plus(cacheTtl),
                    confidence = Confidence.INFRASTRUCTURE_PRIMARY,
                    traceId = "rfi-rss:${sha256(link).take(16)}"
                )
            )
        }
    }

    private fun severityOf(text: String): AlertSeverity = when {
        Regex("soppress|sospes|interrott|nessuna circolazione", RegexOption.IGNORE_CASE).containsMatchIn(text) -> AlertSeverity.SEVERE
        Regex("ritard|rallent|modific", RegexOption.IGNORE_CASE).containsMatchIn(text) -> AlertSeverity.WARNING
        else -> AlertSeverity.INFO
    }

    private fun Element.textOf(name: String): String = getElementsByTagName(name).item(0)?.textContent.orEmpty()
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class CachedAlerts(val alerts: List<ServiceAlert>, val observedAt: Instant, val expiresAt: Instant)

    companion object {
        val SOURCE_ID = SourceId("rfi-infomobility-rss")
        const val NATIONAL_UPDATES_URL = "https://www.rfi.it/content/rfi/it/news-e-media/infomobilita.rss.updates.xml"
    }
}

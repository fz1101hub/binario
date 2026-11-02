package it.binario.app.data.live

import it.binario.app.data.RailTime
import it.binario.app.data.core.SourceId
import it.binario.app.data.notices.NoticeText
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RailProvider
import it.binario.app.data.provider.RatePolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.w3c.dom.Element

/** One item of an RSS 2.0 channel, as published (text fields trimmed, markup untouched). */
data class RssItem(
    val title: String,
    val link: String?,
    val description: String?,
    val pubDate: Instant?,
    val guid: String?
)

/**
 * A rail-sector strike of the MIT calendar. [startDate] and [endDate] are read from the item's own text (title, then
 * description); null when the text gives no date, in which case the item is never treated as "tomorrow".
 */
data class StrikeNotice(
    val id: String,
    val title: String,
    val description: String?,
    val link: String?,
    val publishedAt: Instant?,
    val startDate: LocalDate?,
    val endDate: LocalDate?
)

/** A standard RSS 2.0 reader: `channel/item` with title, link, description, pubDate and guid. No DTD, no entities. */
object Rss2Parser {
    private const val MAX_ITEMS = 300
    private const val MAX_TEXT = 4_000

    fun parse(bytes: ByteArray): List<RssItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        val builder = factory.newDocumentBuilder().apply { setErrorHandler(SilentXmlErrors) }
        val document = builder.parse(ByteArrayInputStream(bytes))
        val root = document.documentElement ?: throw PublicSourceFormatException("Feed RSS vuoto")
        if (!root.tagName.equals("rss", ignoreCase = true) && !root.tagName.equals("rdf:RDF", ignoreCase = true)) {
            throw PublicSourceFormatException("Feed RSS atteso")
        }
        val items = document.getElementsByTagName("item")
        return (0 until minOf(items.length, MAX_ITEMS)).mapNotNull { index ->
            val item = items.item(index) as? Element ?: return@mapNotNull null
            val title = item.child("title") ?: return@mapNotNull null
            RssItem(
                title = title,
                link = item.child("link")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                description = item.child("description"),
                pubDate = item.child("pubDate")?.let(::parseDate),
                guid = item.child("guid")
            )
        }
    }

    /** Malformed XML is reported by the exception alone, without the parser's default print to the error stream. */
    private object SilentXmlErrors : org.xml.sax.ErrorHandler {
        override fun warning(exception: org.xml.sax.SAXParseException) = Unit
        override fun error(exception: org.xml.sax.SAXParseException) = throw exception
        override fun fatalError(exception: org.xml.sax.SAXParseException) = throw exception
    }

    /** RFC 1123 ("Mon, 02 Nov 2026 10:00:00 +0100"), also with a one-digit day or a zone name. */
    fun parseDate(value: String): Instant? = runCatching { ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()

    private fun Element.child(name: String): String? {
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index) as? Element ?: continue
            if (node.tagName.equals(name, ignoreCase = true)) return node.textContent?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }
        }
        return null
    }
}

/**
 * Rules of the strike calendar: which items are about rail (by sector words, case and accents ignored), the dates
 * written in the item, and what is "in programma".
 */
object StrikeCalendar {
    private val accents = Regex("\\p{M}+")
    private val railWords = Regex(
        "\\b(ferroviari[oae]?|ferrovi[ae]|treni|treno|fs|trenitalia|italo|trenord|rfi)\\b"
    )
    private val months = listOf(
        "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"
    )
    private val monthPattern = months.joinToString("|")
    private val numeric = Regex("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})\\b")
    private val iso = Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")
    private val spanned = Regex("\\b(\\d{1,2})\\s*(?:-|al|e)\\s*(\\d{1,2})\\s+($monthPattern)(?:\\s+(\\d{4}))?\\b")
    private val named = Regex("\\b(\\d{1,2})(?:°|º)?\\s+($monthPattern)(?:\\s+(\\d{4}))?\\b")
    private val startLabel = Regex("(inizio|dal|dalle|a partire)\\W*$")
    private val endLabel = Regex("(fine|al|alle|termine)\\W*$")
    const val UPCOMING_SHOWN = 6
    val UNDATED_RECENT: Duration = Duration.ofDays(14)

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD).replace(accents, "").lowercase(Locale.ROOT)

    fun isRail(item: RssItem): Boolean = railWords.containsMatchIn(normalize(item.title + " " + item.description.orEmpty()))

    private data class Found(val position: Int, val start: LocalDate, val end: LocalDate?)

    /** The first start and end date written in [text]; a date without year takes the one of [reference]. */
    fun dates(text: String, reference: LocalDate): Pair<LocalDate?, LocalDate?> {
        val value = normalize(text)
        val found = mutableListOf<Found>()
        numeric.findAll(value).forEach { match ->
            date(match.groupValues[3].toInt(), match.groupValues[2].toInt(), match.groupValues[1].toInt())?.let { found += Found(match.range.first, it, null) }
        }
        iso.findAll(value).forEach { match ->
            date(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())?.let { found += Found(match.range.first, it, null) }
        }
        val spans = spanned.findAll(value).toList()
        spans.forEach { match ->
            val month = months.indexOf(match.groupValues[3]) + 1
            val year = match.groupValues[4].toIntOrNull() ?: inferYear(month, reference)
            val first = date(year, month, match.groupValues[1].toInt())
            val second = date(year, month, match.groupValues[2].toInt())
            if (first != null) found += Found(match.range.first, first, second?.takeIf { !it.isBefore(first) })
        }
        named.findAll(value).filter { match -> spans.none { match.range.first in it.range } }.forEach { match ->
            val month = months.indexOf(match.groupValues[2]) + 1
            val year = match.groupValues[3].toIntOrNull() ?: inferYear(month, reference)
            date(year, month, match.groupValues[1].toInt())?.let { found += Found(match.range.first, it, null) }
        }
        if (found.isEmpty()) return null to null
        val ordered = found.sortedBy { it.position }
        val prefix = { item: Found -> value.substring(maxOf(0, item.position - 24), item.position) }
        val start = ordered.firstOrNull { startLabel.containsMatchIn(prefix(it)) } ?: ordered.first()
        val end = start.end
            ?: ordered.firstOrNull { it !== start && endLabel.containsMatchIn(prefix(it)) && !it.start.isBefore(start.start) }?.start
            ?: ordered.firstOrNull { it.position > start.position && !it.start.isBefore(start.start) }?.start
            ?: start.start
        return start.start to end
    }

    private fun inferYear(month: Int, reference: LocalDate): Int =
        if (month < reference.monthValue - 3) reference.year + 1 else reference.year

    private fun date(year: Int, month: Int, day: Int): LocalDate? = runCatching { LocalDate.of(year, month, day) }.getOrNull()

    /** Rail items with their dates; markup in titles and descriptions is reduced to plain text. */
    fun strikes(items: List<RssItem>, fetchedOn: LocalDate): List<StrikeNotice> = items.filter(::isRail).map { item ->
        val title = NoticeText.fromHtml(item.title, 300) ?: item.title
        val description = NoticeText.fromHtml(item.description, 2_000)
        val reference = item.pubDate?.atZone(RailTime.defaultZone)?.toLocalDate() ?: fetchedOn
        val (start, end) = dates(title, reference).takeIf { it.first != null } ?: dates(description.orEmpty(), reference)
        StrikeNotice(
            id = item.guid ?: item.link ?: sha256("$title|${item.pubDate}"),
            title = title,
            description = description,
            link = item.link?.takeIf { it.startsWith("https://") },
            publishedAt = item.pubDate,
            startDate = start,
            endDate = end
        )
    }.distinctBy { it.id }

    /**
     * "Scioperi in programma": strikes not over yet, by start date, then recent items without a readable date (the
     * feed's format is not verified, so they are shown rather than lost), at most [UPCOMING_SHOWN].
     */
    fun upcoming(strikes: List<StrikeNotice>, today: LocalDate, now: Instant): List<StrikeNotice> {
        val dated = strikes.filter { strike -> (strike.endDate ?: strike.startDate)?.isBefore(today) == false }
            .sortedWith(compareBy<StrikeNotice> { it.startDate }.thenBy { it.title })
        val undated = strikes.filter { it.startDate == null && it.publishedAt?.isAfter(now.minus(UNDATED_RECENT)) == true }
            .sortedByDescending { it.publishedAt }
        return (dated + undated).take(UPCOMING_SHOWN)
    }

    /** Strikes that start on the day after [today]: the subject of the 18:00 reminder. */
    fun startingTomorrow(strikes: List<StrikeNotice>, today: LocalDate): List<StrikeNotice> =
        strikes.filter { it.startDate == today.plusDays(1) }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}

fun interface MitStrikeFetcher {
    suspend fun fetch(url: String): ByteArray
}

/** HTTPS GET with timeouts and a bounded body; redirects only to the same HTTPS host. Bytes, so the XML declares the charset. */
internal class HttpMitStrikeFetcher(private val maxBytes: Int = 1_048_576) : MitStrikeFetcher {
    override suspend fun fetch(url: String): ByteArray = withTimeoutOrNull(20_000) {
        withContext(Dispatchers.IO) {
            var current = runCatching { URI(url).toURL() }.getOrElse { throw IOException("URL della fonte non valido") }
            val host = current.host
            repeat(MAX_HOPS) {
                if (current.protocol != "https" || !current.host.equals(host, ignoreCase = true) || current.userInfo != null) {
                    throw IOException("La fonte deve usare HTTPS sullo stesso host")
                }
                val connection = current.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8_000
                connection.readTimeout = 12_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
                connection.setRequestProperty("User-Agent", BINARIO_PUBLIC_SOURCE_USER_AGENT)
                val response = withCancellableHttpConnection(connection) {
                    val status = connection.responseCode
                    when (status) {
                        in 200..299 -> Response(status, null, connection.inputStream.use {
                            readBounded(it, connection.contentLengthLong.takeIf { n -> n >= 0 }, maxBytes)
                        })
                        in REDIRECTS -> Response(status, connection.getHeaderField("Location"), null)
                        else -> Response(status, null, null)
                    }
                }
                when (response.status) {
                    in 200..299 -> return@withContext response.body ?: ByteArray(0)
                    in REDIRECTS -> {
                        val location = response.location ?: throw IOException("Reindirizzamento senza destinazione")
                        current = runCatching { current.toURI().resolve(location.trim()).toURL() }.getOrElse { throw IOException("Reindirizzamento non leggibile") }
                    }
                    else -> throw PublicSourceHttpException(response.status, "La fonte ha risposto HTTP ${response.status}")
                }
            }
            throw IOException("Troppi reindirizzamenti")
        }
    } ?: throw SocketTimeoutException("Il calendario scioperi supera il tempo massimo della richiesta")

    private fun readBounded(input: InputStream, declared: Long?, max: Int): ByteArray {
        if (declared != null && declared > max) throw IOException("Risposta oltre il limite di $max byte")
        val output = ByteArrayOutputStream(minOf(declared?.toInt() ?: 8_192, max).coerceAtLeast(16))
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > max) throw IOException("Risposta oltre il limite di $max byte")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private class Response(val status: Int, val location: String?, val body: ByteArray?)

    private companion object {
        const val MAX_HOPS = 3
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}

/**
 * Strike calendar of the Ministry of infrastructure and transport (RSS 2.0, public institutional feed), rail
 * sector only. Cached for six hours; a failed refresh serves the last list for a day, then fails without throwing,
 * and the notices area keeps only the link to the calendar.
 */
class MitStrikeRssAdapter internal constructor(
    private val fetcher: MitStrikeFetcher,
    private val clock: Clock,
    retryDelayMillis: Long
) : RailProvider {
    constructor(clock: Clock = Clock.systemUTC()) : this(HttpMitStrikeFetcher(), clock, 750)

    override val descriptor = ProviderDescriptor(
        id = SOURCE_ID,
        displayName = "MIT — calendario scioperi (RSS)",
        capabilities = setOf(ProviderCapability.SERVICE_ALERTS),
        coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Scioperi del settore ferroviario proclamati e comunicati al Ministero"),
        accessMode = AccessMode.OPEN_DIRECT,
        ratePolicy = RatePolicy(Duration.ofMinutes(30), defaultTimeToLive = TTL),
        schemaVersion = 1
    )

    private val cache = ResilientSourceCache<List<StrikeNotice>>(TTL, MAX_STALE, clock, maxEntries = 1, retryDelayMillis = retryDelayMillis)

    suspend fun strikes(): ProviderResult<List<StrikeNotice>> = withContext(Dispatchers.Default) {
        cache.get(URL) {
            val items = Rss2Parser.parse(fetcher.fetch(URL))
            StrikeCalendar.strikes(items, clock.instant().atZone(RailTime.defaultZone).toLocalDate())
        }.toProviderResult(SOURCE_ID, UNAVAILABLE, UNREADABLE) { strikes, _ -> strikes }
    }

    companion object {
        val SOURCE_ID = SourceId("mit-strikes-rss")
        const val URL = "https://scioperi.mit.gov.it/mit2/public/scioperi/rss"
        val TTL: Duration = Duration.ofHours(6)
        private val MAX_STALE: Duration = Duration.ofHours(24)
        private const val UNAVAILABLE = "Il calendario scioperi del MIT non è raggiungibile."
        private const val UNREADABLE = "Il calendario scioperi del MIT non è leggibile."
    }
}

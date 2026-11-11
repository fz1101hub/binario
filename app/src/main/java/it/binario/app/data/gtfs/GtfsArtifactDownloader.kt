package it.binario.app.data.gtfs

import it.binario.app.data.live.SourceCircuitBreaker
import it.binario.app.data.live.retryAfterOf
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.sources.RailSourceDefinition
import it.binario.app.data.sources.SourceFormat
import it.binario.app.data.sources.SourceReadiness
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.URLConnection
import java.time.Clock
import java.time.Duration
import java.util.Locale

class GtfsDownloadException(message: String) : IllegalStateException(message)

/** Boundary for GTFS artifact acquisition; production HTTP and tests share the same policy contract. */
fun interface GtfsArtifactSource {
    suspend fun open(source: RailSourceDefinition, allowBestEffortFallback: Boolean): GtfsDownloadSession
}

/**
 * Acquisisce un singolo data pack GTFS esplicitamente richiesto dal chiamante.
 * Non esegue refresh automatici, non segue fonti HTTP in chiaro e non gestisce
 * token: queste policy restano responsabilità del gateway o della pipeline CI.
 *
 * A redirect is followed only on the host that was asked (HTTPS, default port): the archive never comes from, and the
 * user's address never reaches, a host the privacy documents do not name. A refusal (403, 429, or 503 with
 * Retry-After) pauses that host for the Retry-After it sent or [refusalPause], as the other public-source clients do;
 * while paused no request is sent (audit2 security-privacy-2).
 *
 * Le fonti BEST_EFFORT_FALLBACK richiedono opt-in esplicito: possono colmare
 * una tratta scoperta ma non diventano accidentalmente equivalenti ai feed
 * ufficiali READY_FOR_GTFS_PACK.
 */
class GtfsArtifactDownloader(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 45_000,
    private val maxContentLengthBytes: Long = 256L * 1024L * 1024L,
    private val maxRedirects: Int = 5,
    private val connectionFactory: (URL) -> URLConnection = { url -> url.openConnection() },
    private val clock: Clock = Clock.systemUTC(),
    private val refusalPause: Duration = Duration.ofMinutes(15)
) : GtfsArtifactSource {
    private val refusals = HashMap<String, SourceCircuitBreaker>()

    init {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
        require(maxContentLengthBytes > 0)
        require(maxRedirects >= 0)
    }

    /** Backward-compatible strict download: best-effort sources require explicit opt-in. */
    fun open(source: RailSourceDefinition): GtfsDownloadSession = openBlocking(source, false)

    override suspend fun open(
        source: RailSourceDefinition,
        allowBestEffortFallback: Boolean
    ): GtfsDownloadSession = openBlocking(source, allowBestEffortFallback)

    private fun openBlocking(
        source: RailSourceDefinition,
        allowBestEffortFallback: Boolean
    ): GtfsDownloadSession {
        validate(source, allowBestEffortFallback)
        val requested = source.downloadOrProbeUri!!.toURL()
        val host = requested.host.orEmpty().lowercase(Locale.ROOT)
        val refusal = synchronized(refusals) { refusals.getOrPut(host) { SourceCircuitBreaker(clock, pauseLabel = source.displayName) } }
        if (!refusal.allowRequest()) {
            throw GtfsDownloadException("Download GTFS in pausa dopo il rifiuto della fonte")
        }
        var url = requested
        val visited = mutableSetOf<String>()
        var redirects = 0
        while (true) {
            if (url.protocol != "https") {
                throw GtfsDownloadException("Binario accetta soltanto data pack GTFS su HTTPS")
            }
            if (!visited.add(url.toExternalForm())) {
                throw GtfsDownloadException("Il download GTFS contiene un ciclo di redirect")
            }
            val connection = (connectionFactory(url) as? HttpURLConnection)
            ?: throw GtfsDownloadException("La fonte non usa HTTP(S)")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/zip, application/octet-stream;q=0.9")
            connection.setRequestProperty("User-Agent", "Binario/0.20 data-pack importer")
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode in REDIRECT_STATUS_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    throw GtfsDownloadException("Redirect GTFS privo di destinazione")
                }
                if (redirects >= maxRedirects) {
                    throw GtfsDownloadException("Il download GTFS supera il limite di redirect")
                }
                val target = try {
                    URL(url, location)
                } catch (_: MalformedURLException) {
                    throw GtfsDownloadException("Redirect GTFS non valido")
                } catch (_: IllegalArgumentException) {
                    throw GtfsDownloadException("Redirect GTFS non valido")
                }
                if (target.protocol != "https") {
                    throw GtfsDownloadException("Binario accetta soltanto data pack GTFS su HTTPS")
                }
                if (!target.host.equals(requested.host, ignoreCase = true) || target.port !in setOf(-1, 443) || target.userInfo != null) {
                    throw GtfsDownloadException("Redirect GTFS verso un altro host non consentito")
                }
                url = target
                redirects += 1
                continue
            }
            if (responseCode !in 200..299) {
                val retryAfter = retryAfterOf(connection.getHeaderField("Retry-After"), clock)
                connection.disconnect()
                when {
                    responseCode == 403 || responseCode == 429 -> refusal.recordFailure(retryAfter ?: refusalPause)
                    responseCode == 503 && retryAfter != null -> refusal.recordFailure(retryAfter)
                }
                throw GtfsDownloadException("Download GTFS non disponibile: HTTP $responseCode")
            }
            refusal.recordSuccess()
            val length = connection.contentLengthLong
            if (length > maxContentLengthBytes) {
                connection.disconnect()
                throw GtfsDownloadException("Il data pack supera il limite configurato")
            }
            val contentType = connection.contentType.orEmpty().lowercase()
            if (contentType.isNotBlank() && "zip" !in contentType && "octet-stream" !in contentType) {
                connection.disconnect()
                throw GtfsDownloadException("Il server non ha restituito un archivio GTFS ZIP")
            }
            return GtfsDownloadSession(BoundedGtfsInputStream(connection.inputStream, maxContentLengthBytes), connection)
        }
    }

    private fun validate(source: RailSourceDefinition, allowBestEffortFallback: Boolean) {
        if (source.accessMode !in setOf(AccessMode.OPEN_DIRECT, AccessMode.PUBLIC_BEST_EFFORT)) {
            throw GtfsDownloadException("La fonte ${source.id.value} non è abilitata al download diretto")
        }
        val readinessAllowed = source.readiness == SourceReadiness.READY_FOR_GTFS_PACK ||
            (allowBestEffortFallback && source.readiness == SourceReadiness.BEST_EFFORT_FALLBACK)
        if (!readinessAllowed) {
            throw GtfsDownloadException("La fonte ${source.id.value} richiede pipeline offline, opt-in fallback o ulteriore verifica")
        }
        if (SourceFormat.GTFS_ZIP !in source.formats) {
            throw GtfsDownloadException("La fonte ${source.id.value} non espone un data pack GTFS")
        }
        val uri = source.downloadOrProbeUri ?: throw GtfsDownloadException("La fonte non ha un URL di download stabile")
        if (uri.scheme != "https") {
            throw GtfsDownloadException("Binario accetta soltanto data pack GTFS su HTTPS")
        }
    }

    private companion object {
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}

class GtfsDownloadSession internal constructor(
    val input: InputStream,
    private val connection: HttpURLConnection? = null
) : Closeable {
    override fun close() {
        input.close()
        connection?.disconnect()
    }
}

/**
 * Applica il limite di dimensione ai byte effettivamente letti: un Content-Length
 * assente (-1) o mendace non deve permettere di superare maxContentLengthBytes.
 */
internal class BoundedGtfsInputStream(
    private val upstream: InputStream,
    private val maxBytes: Long
) : InputStream() {
    private var consumed = 0L

    override fun read(): Int {
        val result = upstream.read()
        if (result >= 0) register(1)
        return result
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val result = upstream.read(buffer, offset, length)
        if (result > 0) register(result.toLong())
        return result
    }

    override fun available(): Int = upstream.available()

    override fun close() = upstream.close()

    private fun register(bytes: Long) {
        consumed += bytes
        if (consumed > maxBytes) throw GtfsDownloadException("Il data pack supera il limite configurato durante il download")
    }
}

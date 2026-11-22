package it.binario.app.data.gtfs

import it.binario.app.data.provider.AccessMode
import it.binario.app.data.sources.RailSourceDefinition
import it.binario.app.data.sources.SourceFormat
import it.binario.app.data.sources.SourceReadiness
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.time.Clock
import java.time.Duration

/**
 * A download that did not complete; [refused] when the host refused it (403, 429, 503 with Retry-After) or is paused,
 * [oversized] when the announced or received bytes exceed the limit.
 */
class GtfsDownloadException(
    message: String,
    val refused: Boolean = false,
    val oversized: Boolean = false
) : IllegalStateException(message)

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
 * while paused no request is sent (audit2 security-privacy-2). The request loop is [HttpsArchiveFetch].
 *
 * Le fonti BEST_EFFORT_FALLBACK richiedono opt-in esplicito: possono colmare
 * una tratta scoperta ma non diventano accidentalmente equivalenti ai feed
 * ufficiali READY_FOR_GTFS_PACK.
 */
class GtfsArtifactDownloader(
    connectTimeoutMillis: Int = 15_000,
    readTimeoutMillis: Int = 45_000,
    private val maxContentLengthBytes: Long = 256L * 1024L * 1024L,
    maxRedirects: Int = 5,
    connectionFactory: (URL) -> URLConnection = { url -> url.openConnection() },
    clock: Clock = Clock.systemUTC(),
    refusalPause: Duration = Duration.ofMinutes(15)
) : GtfsArtifactSource {
    init {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
        require(maxContentLengthBytes > 0)
        require(maxRedirects >= 0)
    }

    private val fetch = HttpsArchiveFetch(connectTimeoutMillis, readTimeoutMillis, maxRedirects, connectionFactory, clock, refusalPause)

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
        // Same host only, HTTPS on the default port, no user info.
        return fetch.open(requested, source.displayName, maxContentLengthBytes) { asked, target ->
            target.host.equals(asked.host, ignoreCase = true) && target.port in setOf(-1, 443) && target.userInfo == null
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

}

class GtfsDownloadSession internal constructor(
    val input: InputStream,
    private val connection: HttpURLConnection? = null,
    /** The Content-Length the server announced, -1 when it sent none. */
    val contentLength: Long = -1L
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
        if (consumed > maxBytes) throw GtfsDownloadException("Il data pack supera il limite configurato durante il download", oversized = true)
    }
}

package it.binario.app.data.gtfs

import it.binario.app.data.live.BINARIO_PUBLIC_SOURCE_USER_AGENT
import it.binario.app.data.live.SourceCircuitBreaker
import it.binario.app.data.live.retryAfterOf
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.URLConnection
import java.time.Clock
import java.time.Duration
import java.util.Locale

/**
 * The HTTPS loop of the archive downloads ([GtfsArtifactDownloader], [ForeignPackDownloader]): one GET with the honest
 * User-Agent, redirects followed by hand (never by the platform) and only where [open]'s policy allows, HTTPS only, a
 * size cap on the Content-Length and on the bytes actually read. A refusal (403, 429, or 503 with Retry-After) pauses
 * the host that was asked for the Retry-After it sent or [refusalPause]; while paused no request is sent. There are no
 * retries: every call is one request chain the user asked for.
 */
internal class HttpsArchiveFetch(
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    private val maxRedirects: Int,
    private val connectionFactory: (URL) -> URLConnection,
    private val clock: Clock,
    private val refusalPause: Duration
) {
    private val refusals = HashMap<String, SourceCircuitBreaker>()

    init {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
        require(maxRedirects >= 0)
    }

    /**
     * Opens [requested] and returns the body of the final answer, bounded to [maxContentLengthBytes]. A redirect is
     * followed only when [redirectAllowed] accepts its target (HTTPS is required before it is asked). [pauseLabel]
     * names the host in the data status line while it is paused; null keeps the pause out of it.
     */
    fun open(
        requested: URL,
        pauseLabel: String?,
        maxContentLengthBytes: Long,
        redirectAllowed: (requested: URL, target: URL) -> Boolean
    ): GtfsDownloadSession {
        require(maxContentLengthBytes > 0)
        val host = requested.host.orEmpty().lowercase(Locale.ROOT)
        val refusal = synchronized(refusals) { refusals.getOrPut(host) { SourceCircuitBreaker(clock, pauseLabel = pauseLabel) } }
        if (!refusal.allowRequest()) {
            throw GtfsDownloadException("Download GTFS in pausa dopo il rifiuto della fonte", refused = true)
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
            connection.setRequestProperty("User-Agent", BINARIO_PUBLIC_SOURCE_USER_AGENT)
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
                if (!redirectAllowed(requested, target)) {
                    throw GtfsDownloadException("Redirect GTFS verso un altro host non consentito")
                }
                url = target
                redirects += 1
                continue
            }
            if (responseCode !in 200..299) {
                val retryAfter = retryAfterOf(connection.getHeaderField("Retry-After"), clock)
                connection.disconnect()
                val refused = when {
                    responseCode == 403 || responseCode == 429 -> {
                        refusal.recordFailure(retryAfter ?: refusalPause)
                        true
                    }
                    responseCode == 503 && retryAfter != null -> {
                        refusal.recordFailure(retryAfter)
                        true
                    }
                    else -> false
                }
                throw GtfsDownloadException("Download GTFS non disponibile: HTTP $responseCode", refused = refused)
            }
            refusal.recordSuccess()
            val length = connection.contentLengthLong
            if (length > maxContentLengthBytes) {
                connection.disconnect()
                throw GtfsDownloadException("Il data pack supera il limite configurato", oversized = true)
            }
            val contentType = connection.contentType.orEmpty().lowercase(Locale.ROOT)
            if (contentType.isNotBlank() && "zip" !in contentType && "octet-stream" !in contentType) {
                connection.disconnect()
                throw GtfsDownloadException("Il server non ha restituito un archivio GTFS ZIP")
            }
            return GtfsDownloadSession(BoundedGtfsInputStream(connection.inputStream, maxContentLengthBytes), connection, length)
        }
    }

    companion object {
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

        /** HTTPS on the default port, without user info, to one of [hosts] (exact names, lower case). */
        fun toHostsOnly(target: URL, hosts: Set<String>): Boolean =
            target.protocol == "https" && target.port in setOf(-1, 443) && target.userInfo == null &&
                target.host.orEmpty().lowercase(Locale.ROOT) in hosts
    }
}

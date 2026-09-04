package it.binario.app.data.gtfs

import it.binario.app.data.provider.AccessMode
import it.binario.app.data.sources.RailSourceDefinition
import it.binario.app.data.sources.SourceFormat
import it.binario.app.data.sources.SourceReadiness
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection

class GtfsDownloadException(message: String) : IllegalStateException(message)

/** Boundary for GTFS artifact acquisition; production HTTP and tests share the same policy contract. */
fun interface GtfsArtifactSource {
    fun open(source: RailSourceDefinition, allowBestEffortFallback: Boolean): GtfsDownloadSession
}

/**
 * Acquisisce un singolo data pack GTFS esplicitamente richiesto dal chiamante.
 * Non esegue refresh automatici, non segue fonti HTTP in chiaro e non gestisce
 * token: queste policy restano responsabilità del gateway o della pipeline CI.
 *
 * Le fonti BEST_EFFORT_FALLBACK richiedono opt-in esplicito: possono colmare
 * una tratta scoperta ma non diventano accidentalmente equivalenti ai feed
 * ufficiali READY_FOR_GTFS_PACK.
 */
class GtfsArtifactDownloader(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 45_000,
    private val maxContentLengthBytes: Long = 256L * 1024L * 1024L
) : GtfsArtifactSource {
    init {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
        require(maxContentLengthBytes > 0)
    }

    override fun open(
        source: RailSourceDefinition,
        allowBestEffortFallback: Boolean = false
    ): GtfsDownloadSession {
        validate(source, allowBestEffortFallback)
        val url = source.downloadOrProbeUri!!.toURL()
        val connection = (url.openConnection() as? HttpURLConnection)
            ?: throw GtfsDownloadException("La fonte non usa HTTP(S)")
        connection.instanceFollowRedirects = true
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/zip, application/octet-stream;q=0.9")
        connection.setRequestProperty("User-Agent", "Binario/0.20 data-pack importer")
        connection.connect()

        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            throw GtfsDownloadException("Download GTFS non disponibile: HTTP ${connection.responseCode}")
        }
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
        return GtfsDownloadSession(connection.inputStream, connection)
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
    private val connection: HttpURLConnection? = null
) : Closeable {
    override fun close() {
        input.close()
        connection?.disconnect()
    }
}

package it.binario.app.data.gtfs

import it.binario.app.data.provider.AccessMode
import it.binario.app.data.sources.RailSourceDefinition
import it.binario.app.data.sources.SourceFormat
import it.binario.app.data.sources.SourceReadiness
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

class GtfsDownloadException(message: String) : IllegalStateException(message)

/**
 * Acquisisce un singolo data pack GTFS esplicitamente richiesto dal chiamante.
 * Non esegue refresh automatici, non segue fonti HTTP in chiaro e non gestisce
 * token: queste policy restano responsabilità del gateway o della pipeline CI.
 */
class GtfsArtifactDownloader(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 45_000,
    private val maxContentLengthBytes: Long = 256L * 1024L * 1024L
) {
    init {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
        require(maxContentLengthBytes > 0)
    }

    fun open(source: RailSourceDefinition): GtfsDownloadSession {
        validate(source)
        val url = source.downloadOrProbeUri!!.toURL()
        val connection = (url.openConnection() as? HttpURLConnection)
            ?: throw GtfsDownloadException("La fonte non usa HTTP(S)")
        connection.instanceFollowRedirects = true
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/zip, application/octet-stream;q=0.9")
        connection.setRequestProperty("User-Agent", "Binario/0.15 data-pack importer")
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

    private fun validate(source: RailSourceDefinition) {
        if (source.accessMode != AccessMode.OPEN_DIRECT) {
            throw GtfsDownloadException("La fonte ${source.id.value} non è abilitata al download diretto")
        }
        if (source.readiness != SourceReadiness.READY_FOR_GTFS_PACK) {
            throw GtfsDownloadException("La fonte ${source.id.value} richiede pipeline offline o ulteriore verifica")
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
    private val connection: HttpURLConnection
) : Closeable {
    override fun close() {
        input.close()
        connection.disconnect()
    }
}

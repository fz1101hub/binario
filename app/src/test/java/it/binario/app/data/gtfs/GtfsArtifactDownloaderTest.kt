package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import it.binario.app.data.sources.InternationalSourceCatalog
import it.binario.app.data.sources.RailSourceCatalog
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Test

class GtfsArtifactDownloaderTest {
    private val downloader = GtfsArtifactDownloader()

    @Test(expected = GtfsDownloadException::class)
    fun `rifiuta una fonte gtfs disponibile soltanto via gateway http`() {
        downloader.open(RailSourceCatalog.byId(SourceId("amt-genova-casella-gtfs"))!!)
    }

    @Test(expected = GtfsDownloadException::class)
    fun `rifiuta una fonte realtime best effort`() {
        downloader.open(RailSourceCatalog.byId(SourceId("viaggiatreno"))!!)
    }

    @Test(expected = GtfsDownloadException::class)
    fun `rifiuta una fonte gtfs senza url artefatto stabilizzato`() {
        val sourceWithoutArtifact = RailSourceCatalog
            .byId(SourceId("eav-gtfs"))!!
            .copy(downloadOrProbeUri = null)

        downloader.open(sourceWithoutArtifact)
    }

    @Test(expected = GtfsDownloadException::class)
    fun `fallback community richiede opt in esplicito`() {
        val europeanSleeper = InternationalSourceCatalog.byId(SourceId("european-sleeper-gtfs"))!!
        downloader.open(europeanSleeper)
    }

    @Test(expected = GtfsDownloadException::class)
    fun `rifiuta redirect da https a http`() {
        val bounded = GtfsArtifactDownloader(
            maxContentLengthBytes = LIMIT,
            connectionFactory = { url ->
                fakeRedirectConnection(url, "http://example.invalid/feed.zip")
            }
        )

        bounded.open(eavGtfs)
    }

    @Test
    fun `segue redirect https mantenendo la policy tls`() {
        var requests = 0
        val bounded = GtfsArtifactDownloader(
            maxContentLengthBytes = LIMIT,
            connectionFactory = { url ->
                requests += 1
                if (requests == 1) fakeRedirectConnection(url, "https://cdn.example.invalid/feed.zip")
                else fakeConnection(url, ByteArray(LIMIT.toInt()), LIMIT)
            }
        )

        val bytes = bounded.open(eavGtfs).use { session -> session.input.readBytes() }

        assertEquals(2, requests)
        assertEquals(LIMIT.toInt(), bytes.size)
    }

    @Test(expected = GtfsDownloadException::class)
    fun `interrompe una catena di redirect oltre il limite configurato`() {
        var requests = 0
        val bounded = GtfsArtifactDownloader(
            maxContentLengthBytes = LIMIT,
            maxRedirects = 1,
            connectionFactory = { url ->
                requests += 1
                fakeRedirectConnection(url, "https://cdn.example.invalid/feed-$requests.zip")
            }
        )

        bounded.open(eavGtfs)
    }

    @Test(expected = GtfsDownloadException::class)
    fun `interrompe il download quando i byte letti superano il limite senza content length`() {
        val bounded = downloaderWithFakeConnection(body = ByteArray(LIMIT.toInt() + 1), contentLength = -1L)

        bounded.open(eavGtfs).use { session -> session.input.readBytes() }
    }

    @Test(expected = GtfsDownloadException::class)
    fun `interrompe il download quando content length mente sulla dimensione reale`() {
        val bounded = downloaderWithFakeConnection(body = ByteArray(LIMIT.toInt() + 1), contentLength = 1L)

        bounded.open(eavGtfs).use { session -> session.input.readBytes() }
    }

    @Test
    fun `accetta un download che rientra esattamente nel limite`() {
        val bounded = downloaderWithFakeConnection(body = ByteArray(LIMIT.toInt()), contentLength = -1L)

        val bytes = bounded.open(eavGtfs).use { session -> session.input.readBytes() }

        assertEquals(LIMIT.toInt(), bytes.size)
    }

    private val eavGtfs = RailSourceCatalog.byId(SourceId("eav-gtfs"))!!

    private fun downloaderWithFakeConnection(body: ByteArray, contentLength: Long) = GtfsArtifactDownloader(
        maxContentLengthBytes = LIMIT,
        connectionFactory = { url -> fakeConnection(url, body, contentLength) }
    )

    private fun fakeRedirectConnection(url: URL, location: String): HttpURLConnection =
        object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false
            override fun getResponseCode(): Int = HTTP_MOVED_TEMP
            override fun getHeaderField(name: String?): String? = if (name.equals("Location", ignoreCase = true)) location else null
        }

    private fun fakeConnection(url: URL, body: ByteArray, contentLength: Long): HttpURLConnection =
        object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false
            override fun getResponseCode(): Int = 200
            override fun getContentLengthLong(): Long = contentLength
            override fun getContentType(): String = "application/zip"
            override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        }

    private companion object {
        const val LIMIT = 16L
    }
}

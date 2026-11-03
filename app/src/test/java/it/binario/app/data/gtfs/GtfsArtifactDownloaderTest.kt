package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import it.binario.app.data.sources.InternationalSourceCatalog
import it.binario.app.data.sources.RailSourceCatalog
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `rifiuta un redirect da https a http senza aprire la destinazione`() {
        val opened = mutableListOf<URL>()
        val redirecting = GtfsArtifactDownloader(connectionFactory = { url ->
            opened += url
            redirectConnection(url, "http://mirror.example.test/feed.zip")
        })

        val error = runCatching { redirecting.open(eavGtfs) }.exceptionOrNull()

        assertTrue(error is GtfsDownloadException)
        assertEquals(1, opened.size)
    }

    @Test(expected = GtfsDownloadException::class)
    fun `rifiuta catene di redirect oltre il limite`() {
        val locations = ArrayDeque(
            listOf(
                "https://one.example.test/feed.zip",
                "https://two.example.test/feed.zip"
            )
        )
        val redirecting = GtfsArtifactDownloader(
            maxRedirects = 1,
            connectionFactory = { url -> redirectConnection(url, locations.removeFirst()) }
        )

        redirecting.open(eavGtfs)
    }

    @Test
    fun `rifiuta un loop di redirect prima di riaprire lo stesso url`() {
        val opened = mutableListOf<URL>()
        val redirecting = GtfsArtifactDownloader(connectionFactory = { url ->
            opened += url
            val location = if (opened.size == 1) "https://mirror.example.test/feed.zip" else eavGtfs.downloadOrProbeUri.toString()
            redirectConnection(url, location)
        })

        val error = runCatching { redirecting.open(eavGtfs) }.exceptionOrNull()

        assertTrue(error is GtfsDownloadException)
        assertEquals(2, opened.size)
    }

    @Test
    fun `rifiuta una location malformata come errore di download`() {
        val opened = mutableListOf<URL>()
        val redirecting = GtfsArtifactDownloader(connectionFactory = { url ->
            opened += url
            redirectConnection(url, "https://[host-non-valido/feed.zip")
        })

        val error = runCatching { redirecting.open(eavGtfs) }.exceptionOrNull()

        assertTrue(error?.javaClass?.name, error is GtfsDownloadException)
        assertEquals(1, opened.size)
    }

    @Test
    fun `segue un redirect https bounded e restituisce solo il body finale`() {
        val opened = mutableListOf<URL>()
        val redirecting = GtfsArtifactDownloader(connectionFactory = { url ->
            opened += url
            if (opened.size == 1) redirectConnection(url, "/final.zip")
            else fakeConnection(url, byteArrayOf(1, 2, 3), 3)
        })

        val bytes = redirecting.open(eavGtfs).use { it.input.readBytes() }

        assertEquals(listOf(1, 2, 3), bytes.map(Byte::toInt))
        assertEquals("https", opened.last().protocol)
        assertEquals("/final.zip", opened.last().path)
    }

    private val eavGtfs = RailSourceCatalog.byId(SourceId("eav-gtfs"))!!

    private fun downloaderWithFakeConnection(body: ByteArray, contentLength: Long) = GtfsArtifactDownloader(
        maxContentLengthBytes = LIMIT,
        connectionFactory = { url -> fakeConnection(url, body, contentLength) }
    )

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

    private fun redirectConnection(url: URL, location: String): HttpURLConnection =
        object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false
            override fun getResponseCode(): Int = 302
            override fun getHeaderField(name: String?): String? = if (name.equals("Location", ignoreCase = true)) location else null
        }

    private companion object {
        const val LIMIT = 16L
    }
}

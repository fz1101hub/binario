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
                "/one/feed.zip",
                "/two/feed.zip"
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
            val location = if (opened.size == 1) "/mirror/feed.zip" else eavGtfs.downloadOrProbeUri.toString()
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

    /** Audit2 security-privacy-2: the archive comes from the host that was asked, never from where it points. */
    @Test
    fun `rifiuta un redirect verso un altro host senza aprirlo`() {
        for (location in listOf("https://other.example.test/feed.zip", "https://other.example.test:443/feed.zip",
            "//other.example.test/feed.zip", "https://user@${eavGtfs.downloadOrProbeUri!!.host}/feed.zip",
            "https://${eavGtfs.downloadOrProbeUri!!.host}:8443/feed.zip")) {
            val opened = mutableListOf<URL>()
            val redirecting = GtfsArtifactDownloader(connectionFactory = { url ->
                opened += url
                redirectConnection(url, location)
            })

            val error = runCatching { redirecting.open(eavGtfs) }.exceptionOrNull()

            assertTrue(location, error is GtfsDownloadException)
            assertEquals(location, listOf(eavGtfs.downloadOrProbeUri!!.toURL()), opened)
        }
    }

    /** Audit2 security-privacy-2: a refusal pauses the host for the Retry-After it sent, or a quarter of an hour. */
    @Test
    fun `un rifiuto mette in pausa il download e rispetta Retry-After`() {
        val clock = object : java.time.Clock() {
            var now = java.time.Instant.parse("2026-11-11T09:00:00Z")
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        var status = 429
        var retryAfter: String? = "3600"
        var opened = 0
        val downloader = GtfsArtifactDownloader(clock = clock, connectionFactory = { url ->
            opened++
            if (status == 200) fakeConnection(url, byteArrayOf(1), 1) else object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy(): Boolean = false
                override fun getResponseCode(): Int = status
                override fun getHeaderField(name: String?): String? = if (name.equals("Retry-After", ignoreCase = true)) retryAfter else null
            }
        })
        fun refused() = runCatching { downloader.open(eavGtfs).close() }.exceptionOrNull() is GtfsDownloadException

        assertTrue(refused())
        clock.now = clock.now.plusSeconds(3_599)
        assertTrue(refused())
        assertEquals(1, opened)
        // After the Retry-After the host is asked again; a 403 without one pauses it for fifteen minutes.
        clock.now = clock.now.plusSeconds(2)
        status = 403
        retryAfter = null
        assertTrue(refused())
        clock.now = clock.now.plusSeconds(14 * 60)
        assertTrue(refused())
        assertEquals(2, opened)
        // A plain server error is not a refusal: the caller's own cooldown decides when to ask again.
        clock.now = clock.now.plusSeconds(61)
        status = 500
        assertTrue(refused())
        assertTrue(refused())
        assertEquals(4, opened)
        status = 200
        downloader.open(eavGtfs).close()
        assertEquals(5, opened)
        it.binario.app.data.live.SourcePauses.clearForTest()
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

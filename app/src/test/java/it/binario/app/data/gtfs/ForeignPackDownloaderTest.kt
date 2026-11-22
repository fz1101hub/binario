package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.ForeignPackFixtures.archive
import it.binario.app.data.gtfs.ForeignPackFixtures.oebb
import it.binario.app.data.gtfs.ForeignPackFixtures.packJson
import it.binario.app.data.gtfs.ForeignPackFixtures.pins
import it.binario.app.data.live.BINARIO_PUBLIC_SOURCE_USER_AGENT
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignPackDownloaderTest {
    private val root: File = Files.createTempDirectory("foreign-download").toFile()
    private val bytes = archive("oebb-gtfs")
    private val sha = ForeignPackFixtures.sha256(bytes)
    private val pins = pins(packJson("oebb-gtfs", "AT", bytes, listOf("NC000544", "NC001632"), listOf("NC000544")))
    private val store = ForeignPackStore(File(root, "gtfs-full"), pins)
    private val pinnedUrl = "https://github.com/fz1101hub/binario/releases/download/gtfs-full-oebb-gtfs-${sha.take(12)}/oebb-gtfs.zip"
    private val assetUrl = "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r&sig=x"

    private class Answer(val status: Int, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap(), val length: Long = body.size.toLong())

    private val clock = object : Clock() {
        var now: Instant = Instant.parse("2026-11-30T09:00:00Z")
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
    private val opened = mutableListOf<String>()
    private val userAgents = mutableListOf<String?>()
    private var answers: (String) -> Answer = { url -> if (url == pinnedUrl) redirect(assetUrl) else Answer(200, bytes) }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun redirect(location: String) = Answer(302, headers = mapOf("Location" to location))

    private fun connection(url: URL): HttpURLConnection {
        opened += url.toExternalForm()
        val answer = answers(url.toExternalForm())
        return object : HttpURLConnection(url) {
            private val requestHeaders = HashMap<String, String>()
            override fun connect() {
                userAgents += requestHeaders["User-Agent"]
            }
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false
            override fun setRequestProperty(key: String, value: String) {
                requestHeaders[key] = value
            }
            override fun getResponseCode(): Int = answer.status
            override fun getHeaderField(name: String?): String? = answer.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            override fun getContentLengthLong(): Long = answer.length
            override fun getContentType(): String = "application/octet-stream"
            override fun getInputStream(): InputStream = ByteArrayInputStream(answer.body)
        }
    }

    private fun downloader() = ForeignPackDownloader(store, connectionFactory = ::connection, clock = clock, ioDispatcher = Dispatchers.Unconfined)

    @Test
    fun `a pinned download follows GitHub's redirect and installs the exact bytes`() = runBlocking {
        val progress = mutableListOf<Long>()

        val result = downloader().download(oebb) { written, total -> progress += written; assertEquals(bytes.size.toLong(), total) }

        assertEquals(ForeignPackDownloadResult.Installed, result)
        assertEquals(listOf(pinnedUrl, assetUrl), opened)
        assertEquals(listOf(BINARIO_PUBLIC_SOURCE_USER_AGENT, BINARIO_PUBLIC_SOURCE_USER_AGENT), userAgents)
        assertTrue(BINARIO_PUBLIC_SOURCE_USER_AGENT.startsWith("Binario/"))
        assertEquals(bytes.size.toLong(), progress.last())
        assertTrue(bytes.contentEquals(store.installed(oebb)!!.readBytes()))
        assertFalse(store.partFile(oebb).exists())
        assertEquals(1L, store.generation.value)
    }

    @Test
    fun `the legacy asset host is accepted too`() = runBlocking {
        answers = { url -> if (url == pinnedUrl) redirect("https://objects.githubusercontent.com/release/x.zip") else Answer(200, bytes) }

        assertEquals(ForeignPackDownloadResult.Installed, downloader().download(oebb))
    }

    @Test
    fun `a redirect anywhere else is never opened`() = runBlocking {
        for (location in listOf(
            "https://example.com/oebb-gtfs.zip",
            "https://release-assets.githubusercontent.com.example.com/x.zip",
            "http://release-assets.githubusercontent.com/x.zip",
            "https://release-assets.githubusercontent.com:8443/x.zip",
            "https://user@release-assets.githubusercontent.com/x.zip",
            "https://github.com/fz1101hub/binario/archive/main.zip",
            "//example.com/x.zip"
        )) {
            opened.clear()
            answers = { url -> if (url == pinnedUrl) redirect(location) else Answer(200, bytes) }

            assertEquals(location, ForeignPackDownloadResult.NetworkError, downloader().download(oebb))
            assertEquals(location, listOf(pinnedUrl), opened)
            assertNull(store.installed(oebb))
        }
    }

    @Test
    fun `a body that is not the pinned one is not kept`() = runBlocking {
        val different = bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val cases = mapOf(
            "announced larger" to Answer(200, bytes + byteArrayOf(0), length = bytes.size + 1L),
            "longer without length" to Answer(200, bytes + byteArrayOf(0), length = -1L),
            "longer than announced" to Answer(200, bytes + byteArrayOf(0), length = bytes.size.toLong()),
            "shorter" to Answer(200, bytes.copyOf(bytes.size - 1)),
            "other bytes" to Answer(200, different)
        )
        for ((label, answer) in cases) {
            opened.clear()
            answers = { url -> if (url == pinnedUrl) redirect(assetUrl) else answer }

            assertEquals(label, ForeignPackDownloadResult.Invalid, downloader().download(oebb))
            assertEquals(label, 2, opened.size)
            assertNull(label, store.installed(oebb))
            assertFalse(label, store.partFile(oebb).exists())
            assertFalse(label, store.zipFile(oebb).exists())
        }
        assertEquals(0L, store.generation.value)
    }

    @Test
    fun `an archive of another pack with the pinned digest is refused`() = runBlocking {
        val other = archive("sncf-gtfs")
        val otherSha = ForeignPackFixtures.sha256(other)
        val otherPins = pins(packJson("oebb-gtfs", "AT", other, listOf("A"), listOf("A")))
        val otherStore = ForeignPackStore(File(root, "other"), otherPins)
        val url = "https://github.com/fz1101hub/binario/releases/download/gtfs-full-oebb-gtfs-${otherSha.take(12)}/oebb-gtfs.zip"
        answers = { asked -> if (asked == url) redirect(assetUrl) else Answer(200, other) }

        val result = ForeignPackDownloader(otherStore, connectionFactory = ::connection, clock = clock, ioDispatcher = Dispatchers.Unconfined).download(oebb)

        assertEquals(ForeignPackDownloadResult.Invalid, result)
        assertNull(otherStore.installed(oebb))
    }

    @Test
    fun `a refusal stops at once and pauses github without retrying`() = runBlocking {
        answers = { Answer(429, headers = mapOf("Retry-After" to "600")) }
        val downloader = downloader()

        assertEquals(ForeignPackDownloadResult.Refused, downloader.download(oebb))
        assertEquals(1, opened.size)
        // Paused: no request at all.
        assertEquals(ForeignPackDownloadResult.Refused, downloader.download(oebb))
        assertEquals(1, opened.size)
        clock.now = clock.now.plusSeconds(601)
        answers = { url -> if (url == pinnedUrl) Answer(403) else Answer(200, bytes) }
        assertEquals(ForeignPackDownloadResult.Refused, downloader.download(oebb))
        assertEquals(2, opened.size)
        // A 403 without Retry-After pauses a quarter of an hour.
        clock.now = clock.now.plusSeconds(14 * 60)
        assertEquals(ForeignPackDownloadResult.Refused, downloader.download(oebb))
        assertEquals(2, opened.size)
        clock.now = clock.now.plusSeconds(61)
        answers = { url -> if (url == pinnedUrl) redirect(assetUrl) else Answer(200, bytes) }
        assertEquals(ForeignPackDownloadResult.Installed, downloader.download(oebb))
        assertEquals(4, opened.size)
    }

    @Test
    fun `a refusal by the asset host also pauses the download`() = runBlocking {
        answers = { url -> if (url == pinnedUrl) redirect(assetUrl) else Answer(403) }
        val downloader = downloader()

        assertEquals(ForeignPackDownloadResult.Refused, downloader.download(oebb))
        assertEquals(ForeignPackDownloadResult.Refused, downloader.download(oebb))
        assertEquals(2, opened.size)
    }

    @Test
    fun `a server error is one failed request, never retried by itself`() = runBlocking {
        answers = { Answer(500) }
        val downloader = downloader()

        assertEquals(ForeignPackDownloadResult.NetworkError, downloader.download(oebb))
        assertEquals(1, opened.size)
        assertEquals(ForeignPackDownloadResult.NetworkError, downloader.download(oebb))
        assertEquals(2, opened.size)
    }

    @Test
    fun `an expired or unpinned pack is never requested`() = runBlocking {
        clock.now = Instant.parse("2026-12-13T09:00:00Z")
        assertEquals(ForeignPackDownloadResult.NotOffered, downloader().download(oebb))
        clock.now = Instant.parse("2026-11-30T09:00:00Z")
        assertEquals(ForeignPackDownloadResult.NotOffered, downloader().download(SourceId("ch-otd-gtfs")))
        val unpinned = ForeignPackStore(File(root, "unpinned"), ForeignPackPins.parse(ForeignPackFixtures.sampleJson()))
        assertEquals(
            ForeignPackDownloadResult.NotOffered,
            ForeignPackDownloader(unpinned, connectionFactory = ::connection, clock = clock, ioDispatcher = Dispatchers.Unconfined)
                .download(SourceId("sncf-gtfs"))
        )
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `one download at a time`() = runBlocking {
        store.downloadMutex.lock()
        try {
            assertEquals(ForeignPackDownloadResult.Busy, downloader().download(oebb))
            assertTrue(opened.isEmpty())
        } finally {
            store.downloadMutex.unlock()
        }
    }

    @Test
    fun `cancelling stops the download and leaves nothing behind`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        answers = { url ->
            if (url == pinnedUrl) redirect(assetUrl) else Answer(200, bytes)
        }
        val job = async(Dispatchers.Default) {
            downloader().download(oebb) { _, _ ->
                started.complete(Unit)
                runBlocking { release.await() }
            }
        }
        started.await()
        job.cancel()
        release.complete(Unit)
        val outcome = runCatching { job.await() }.exceptionOrNull()

        assertTrue(outcome is CancellationException)
        assertNull(store.installed(oebb))
        assertFalse(store.partFile(oebb).exists())
        assertFalse(store.downloadMutex.isLocked)
    }

    @Test
    fun `only a release URL of this repository on github is ever requested`() {
        val pin = pins.entry(oebb)!!.android!!
        assertNotNull(ForeignPackDownloader.pinnedUrl(pin))
        for (url in listOf(
            "http://github.com/fz1101hub/binario/releases/download/t/a.zip",
            "https://github.com:8443/fz1101hub/binario/releases/download/t/a.zip",
            "https://example.com/fz1101hub/binario/releases/download/t/a.zip",
            "https://github.com/fz1101hub/binario/releases/download/t/a.zip?x=1"
        )) {
            assertNull(url, ForeignPackDownloader.pinnedUrl(pin.copy(url = url)))
        }
        assertEquals(LocalDate.of(2026, 12, 12), pins.entry(oebb)!!.lastServiceDate)
    }
}

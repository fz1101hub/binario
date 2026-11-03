package it.binario.app.data.live

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Real response bodies captured by `LiveProviderNetworkTest.candidateSourceSamples`, trimmed to valid JSON. */
internal fun sourceFixture(name: String): String =
    requireNotNull(PublicSourceFixtureAnchor::class.java.getResource("/sources/$name")) { "Fixture assente: $name" }.readText()

private object PublicSourceFixtureAnchor

internal class MutablePublicSourceClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(duration: Duration) { now = now.plus(duration) }
}

/** Records every URL and answers from [respond]; an exception thrown by [respond] reaches the adapter unchanged. */
internal class RecordingPublicSourceFetcher(private val respond: (String) -> String) : PublicSourceFetcher {
    val urls = java.util.concurrent.CopyOnWriteArrayList<String>()
    override suspend fun get(url: String): String {
        urls += url
        return respond(url)
    }
}

internal class FakePublicSourceConnection(
    url: URL,
    private val status: Int,
    private val location: String? = null,
    private val body: ByteArray = ByteArray(0),
    private val declaredLength: Long = body.size.toLong()
) : HttpURLConnection(url) {
    var closed = false
    override fun connect() = Unit
    override fun disconnect() { closed = true }
    override fun usingProxy() = false
    override fun getResponseCode() = status
    override fun getHeaderField(name: String?): String? = if (name == "Location") location else null
    override fun getContentLengthLong(): Long = declaredLength
    override fun getInputStream(): InputStream = ByteArrayInputStream(body)
}

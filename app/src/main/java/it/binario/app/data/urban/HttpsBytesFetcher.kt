package it.binario.app.data.urban

import it.binario.app.data.live.BINARIO_PUBLIC_SOURCE_USER_AGENT
import it.binario.app.data.live.PublicSourceHttpException
import it.binario.app.data.live.withCancellableHttpConnection
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A bounded HTTPS GET of a binary open-data feed (GTFS-Realtime protobuf). HTTPS only; a redirect is followed only to
 * HTTPS on the same host (Roma Servizi per la Mobilità moves its files under `/wp-content/uploads/shared/`), at most
 * [MAX_HOPS] times; no cookies or caches, a byte cap that refuses a larger body instead of truncating it and a total
 * time limit. Nothing about the user is sent: the URL is the publisher's fixed feed address.
 */
class HttpsBytesFetcher(
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 15_000,
    private val totalTimeoutMillis: Long = 20_000,
    private val accept: String = "application/x-protobuf, application/octet-stream",
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : UrbanRealtimeFetcher {
    override suspend fun fetch(url: String): ByteArray = withTimeoutOrNull(totalTimeoutMillis) {
        val requested = try { URI(url).toURL() } catch (_: Exception) { throw IOException("URL del feed non valido") }
        if (requested.protocol != "https" || requested.userInfo != null) throw IOException("Il feed deve usare HTTPS")
        var target = requested
        repeat(MAX_HOPS + 1) {
            val connection = openConnection(target)
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", BINARIO_PUBLIC_SOURCE_USER_AGENT)
            val response = withCancellableHttpConnection(connection) {
                val status = connection.responseCode
                when (status) {
                    in REDIRECTS -> Response(connection.getHeaderField("Location"), null)
                    in 200..299 -> Response(null, connection.inputStream.use {
                        readBoundedBytes(it, connection.contentLengthLong.takeIf { length -> length >= 0 }, maxBytes)
                    })
                    else -> throw PublicSourceHttpException(status, "Il feed ha risposto HTTP $status")
                }
            }
            response.body?.let { return@withTimeoutOrNull it }
            val location = response.location?.trim() ?: throw IOException("Reindirizzamento senza destinazione")
            val next = try { target.toURI().resolve(location).toURL() } catch (_: Exception) { throw IOException("Reindirizzamento non leggibile") }
            if (next.protocol != "https" || next.userInfo != null || !next.host.equals(requested.host, ignoreCase = true) || next.port !in setOf(-1, 443)) {
                throw IOException("Reindirizzamento non consentito verso ${next.protocol}://${next.host}")
            }
            target = next
        }
        throw IOException("Troppi reindirizzamenti")
    } ?: throw SocketTimeoutException("Il feed supera il tempo massimo della richiesta")

    private class Response(val location: String?, val body: ByteArray?)

    private companion object {
        const val MAX_HOPS = 3
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}

/** At most [maxBytes] of [input]; a larger declared or streamed body is refused, never truncated. */
internal fun readBoundedBytes(input: InputStream, declaredLength: Long?, maxBytes: Int): ByteArray {
    if (declaredLength != null && declaredLength > maxBytes) throw IOException("Risposta oltre il limite di $maxBytes byte")
    val output = ByteArrayOutputStream(minOf(declaredLength?.toInt() ?: 64 * 1024, maxBytes).coerceAtLeast(16))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw IOException("Risposta oltre il limite di $maxBytes byte")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

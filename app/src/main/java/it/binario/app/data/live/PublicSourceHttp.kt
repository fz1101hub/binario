package it.binario.app.data.live

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

/** User-Agent shared by the public notice, board and weather clients, in the style of the other adapters. */
internal const val BINARIO_PUBLIC_SOURCE_USER_AGENT = "Binario/0.20 (personal rail information client)"

/** A public source answered with a non-success HTTP status. Never retried by [ResilientSourceCache]. */
internal class PublicSourceHttpException(val status: Int, message: String) : IOException(message)

/** A public source answered, but not with the expected shape. Never retried. */
internal class PublicSourceFormatException(message: String) : Exception(message)

/**
 * Limits of a single public-source GET. Every request is HTTPS. The only cleartext hop allowed is the one
 * ViaggiaTreno itself imposes: an HTTPS request to `www.viaggiatreno.it` that answers with a redirect to the
 * very same path and query on `http://www.viaggiatreno.it` (the single domain permitted in
 * `network_security_config.xml`). Any other redirect target is refused.
 */
internal data class PublicSourceRequestPolicy(
    val accept: String = "application/json",
    val userAgent: String = BINARIO_PUBLIC_SOURCE_USER_AGENT,
    val connectTimeoutMillis: Int = 8_000,
    val readTimeoutMillis: Int = 12_000,
    val totalTimeoutMillis: Long = 15_000,
    val maxBytes: Int = 1_048_576,
    val allowViaggiaTrenoCleartextRedirect: Boolean = false
) {
    init {
        require(maxBytes > 0) { "Il limite di risposta deve essere positivo" }
        require(totalTimeoutMillis > 0) { "Il timeout deve essere positivo" }
    }
}

fun interface PublicSourceFetcher {
    /** Returns the UTF-8 body of a successful GET; throws [IOException] for network/HTTP failures. */
    suspend fun get(url: String): String
}

internal class HttpPublicSourceFetcher(
    private val policy: PublicSourceRequestPolicy = PublicSourceRequestPolicy(),
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : PublicSourceFetcher {
    override suspend fun get(url: String): String = withTimeoutOrNull(policy.totalTimeoutMillis) {
        val requested = try { URL(url) } catch (_: MalformedURLException) { throw IOException("URL della fonte non valido") }
        if (requested.protocol != "https" || requested.userInfo != null) throw IOException("La fonte deve usare HTTPS")
        var current = requested
        repeat(MAX_HOPS) {
            val connection = openConnection(current)
            connection.requestMethod = "GET"
            connection.connectTimeout = policy.connectTimeoutMillis
            connection.readTimeout = policy.readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", policy.accept)
            connection.setRequestProperty("User-Agent", policy.userAgent)
            val response = withCancellableHttpConnection(connection) {
                val status = connection.responseCode
                when (status) {
                    in REDIRECTS -> Response(status, connection.getHeaderField("Location"), null)
                    in 200..299 -> Response(status, null, if (status == 204) "" else connection.inputStream.use {
                        readBoundedUtf8(it, connection.contentLengthLong.takeIf { length -> length >= 0 }, policy.maxBytes)
                    })
                    else -> Response(status, null, null)
                }
            }
            when (response.status) {
                in REDIRECTS -> {
                    val location = response.location ?: throw IOException("Reindirizzamento senza destinazione")
                    val target = try { URL(current, location) } catch (_: MalformedURLException) {
                        throw IOException("Reindirizzamento non leggibile")
                    }
                    if (!allowedRedirect(requested, target)) {
                        throw IOException("Reindirizzamento non consentito verso ${target.protocol}://${target.host}")
                    }
                    current = target
                }
                in 200..299 -> return@withTimeoutOrNull response.body.orEmpty()
                else -> throw PublicSourceHttpException(response.status, "La fonte ha risposto HTTP ${response.status}")
            }
        }
        throw IOException("Troppi reindirizzamenti")
    } ?: throw SocketTimeoutException("La fonte supera il tempo massimo della richiesta")

    private fun allowedRedirect(requested: URL, target: URL): Boolean {
        if (target.userInfo != null || target.ref != null) return false
        if (target.path != requested.path || target.query != requested.query) return false
        val sameHttpsHost = target.protocol == "https" &&
            target.host.equals(requested.host, ignoreCase = true) && target.port in setOf(-1, 443)
        val viaggiaTrenoCleartext = policy.allowViaggiaTrenoCleartextRedirect &&
            requested.host.lowercase(Locale.ROOT) == VIAGGIATRENO_HOST &&
            target.protocol == "http" && target.host.lowercase(Locale.ROOT) == VIAGGIATRENO_HOST &&
            target.port in setOf(-1, 80)
        return sameHttpsHost || viaggiaTrenoCleartext
    }

    private data class Response(val status: Int, val location: String?, val body: String?)

    private companion object {
        const val MAX_HOPS = 3
        const val VIAGGIATRENO_HOST = "www.viaggiatreno.it"
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}

/** Reads at most [maxBytes] from [input]; a larger declared or streamed body is refused, never truncated. */
internal fun readBoundedUtf8(input: InputStream, declaredLength: Long?, maxBytes: Int): String {
    if (declaredLength != null && declaredLength > maxBytes) throw IOException("Risposta oltre il limite di $maxBytes byte")
    val output = ByteArrayOutputStream(minOf(declaredLength?.toInt() ?: 8_192, maxBytes).coerceAtLeast(16))
    val buffer = ByteArray(8_192)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw IOException("Risposta oltre il limite di $maxBytes byte")
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

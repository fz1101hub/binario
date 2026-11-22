package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.ForeignPackStore.Companion.toHex
import java.io.FileOutputStream
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import java.net.URLConnection
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** How a download of a full foreign pack ended. */
sealed interface ForeignPackDownloadResult {
    data object Installed : ForeignPackDownloadResult
    /** The connection failed or the server answered with an error: «Download non riuscito». */
    data object NetworkError : ForeignPackDownloadResult
    /** GitHub refused (403, 429) or is paused after refusing: no request until the pause ends. */
    data object Refused : ForeignPackDownloadResult
    /** The bytes received are not the pinned ones (size, SHA-256, archive): nothing was kept. */
    data object Invalid : ForeignPackDownloadResult
    /** Another download is running. */
    data object Busy : ForeignPackDownloadResult
    /** This version does not offer the pack (no pin, or its timetable has ended). */
    data object NotOffered : ForeignPackDownloadResult
}

/**
 * Downloads the full pack of one foreign country after the user tapped «Scarica», and nothing else: one GET of the
 * pinned URL on `github.com`, redirects followed only to GitHub's release download hosts (HTTPS on port 443, without
 * user info), the honest User-Agent, no cookies. The body is capped at the pinned size (a larger Content-Length is
 * refused, a longer body is stopped), hashed while it is written to the part file, synced, and moved into place only if
 * its size and SHA-256 equal the pin ([ForeignPackStore.install] then checks the archive and writes the sidecar). The
 * first 403 or 429 pauses github.com (no request at all until the pause ends); there are no retries: a new attempt
 * needs a new tap. Cancelling the coroutine stops at the next block and deletes the part file.
 */
class ForeignPackDownloader internal constructor(
    private val store: ForeignPackStore,
    private val fetch: HttpsArchiveFetch,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher
) {
    constructor(
        store: ForeignPackStore,
        connectionFactory: (URL) -> URLConnection = { url -> url.openConnection() },
        clock: Clock = Clock.system(it.binario.app.data.RailTime.defaultZone),
        refusalPause: Duration = Duration.ofMinutes(15),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        store,
        HttpsArchiveFetch(CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS, MAX_REDIRECTS, connectionFactory, clock, refusalPause),
        clock,
        ioDispatcher
    )

    /**
     * Downloads and installs the full pack of [sourceId]. [onProgress] receives the bytes written and the pinned size
     * after every block, on the IO dispatcher.
     */
    suspend fun download(sourceId: SourceId, onProgress: (Long, Long) -> Unit = { _, _ -> }): ForeignPackDownloadResult {
        val entry = store.pins.entry(sourceId)?.takeIf(ForeignPackEntry::downloadable) ?: return ForeignPackDownloadResult.NotOffered
        if (entry.expiredOn(LocalDate.now(clock))) return ForeignPackDownloadResult.NotOffered
        val pin = entry.android ?: return ForeignPackDownloadResult.NotOffered
        val url = pinnedUrl(pin) ?: return ForeignPackDownloadResult.NotOffered
        if (!store.downloadMutex.tryLock()) return ForeignPackDownloadResult.Busy
        try {
            return withContext(ioDispatcher) { fetchAndInstall(sourceId, pin, url, onProgress) }
        } finally {
            store.downloadMutex.unlock()
        }
    }

    private suspend fun fetchAndInstall(
        sourceId: SourceId,
        pin: ForeignPackDownloadPin,
        url: URL,
        onProgress: (Long, Long) -> Unit
    ): ForeignPackDownloadResult {
        val part = store.partFile(sourceId)
        try {
            store.ensureDirectory()
            val session = try {
                fetch.open(url, pauseLabel = null, maxContentLengthBytes = pin.sizeBytes) { _, target ->
                    HttpsArchiveFetch.toHostsOnly(target, REDIRECT_HOSTS)
                }
            } catch (error: GtfsDownloadException) {
                return when {
                    error.refused -> ForeignPackDownloadResult.Refused
                    error.oversized -> ForeignPackDownloadResult.Invalid
                    else -> ForeignPackDownloadResult.NetworkError
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            session.use {
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = try {
                            session.input.read(buffer)
                        } catch (_: GtfsDownloadException) {
                            // More bytes than pinned.
                            return ForeignPackDownloadResult.Invalid
                        }
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        onProgress(written, pin.sizeBytes)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (written != pin.sizeBytes || digest.digest().toHex() != pin.sha256) return ForeignPackDownloadResult.Invalid
            try {
                store.install(sourceId, part)
            } catch (_: IOException) {
                return ForeignPackDownloadResult.Invalid
            }
            return ForeignPackDownloadResult.Installed
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            return ForeignPackDownloadResult.NetworkError
        } finally {
            part.delete()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        /** github.com answers with one redirect to the asset host. */
        private const val MAX_REDIRECTS = 3
        private const val BUFFER_BYTES = 64 * 1024

        /** The only hosts a pinned download may be redirected to. */
        val REDIRECT_HOSTS: Set<String> = setOf(
            ForeignPackPins.GITHUB_RELEASE_DOWNLOAD_HOST,
            ForeignPackPins.GITHUB_LEGACY_RELEASE_DOWNLOAD_HOST
        )

        /** The pin's URL when it is a release download of this repository on github.com over HTTPS; else null. */
        internal fun pinnedUrl(pin: ForeignPackDownloadPin): URL? {
            if (!pin.url.startsWith(ForeignPackPins.GITHUB_RELEASE_URL_PREFIX)) return null
            val url = try {
                URL(pin.url)
            } catch (_: MalformedURLException) {
                return null
            }
            return url.takeIf {
                it.protocol == "https" && it.host.lowercase(Locale.ROOT) == ForeignPackPins.GITHUB_HOST &&
                    it.port in setOf(-1, 443) && it.userInfo == null && it.query == null && it.ref == null
            }
        }
    }
}

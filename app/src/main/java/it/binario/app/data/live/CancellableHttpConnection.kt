package it.binario.app.data.live

import java.net.HttpURLConnection
import java.util.concurrent.FutureTask
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

/** A blocking socket must not keep a cancelled coroutine waiting for its IO child. */
internal suspend fun <T> withCancellableHttpConnection(connection: HttpURLConnection, read: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        val worker = FutureTask<Unit> {
            val result = runCatching(read)
            runCatching { connection.disconnect() }
            continuation.resumeWith(result)
        }
        continuation.invokeOnCancellation {
            worker.cancel(true)
            // Some HttpURLConnection implementations ignore thread interruption during reads.
            // Disconnect off the caller's thread, including when cancellation starts on the UI.
            Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { runCatching { connection.disconnect() } })
        }
        Dispatchers.IO.dispatch(EmptyCoroutineContext, worker)
    }

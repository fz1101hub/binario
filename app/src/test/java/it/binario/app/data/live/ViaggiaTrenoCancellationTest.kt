package it.binario.app.data.live

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ViaggiaTrenoCancellationTest {
    private val path = "andamentoTreno/S08001/12345/1792101600000"

    @Test fun `caller cancellation closes a socket that ignores thread interruption`() = runBlocking {
        val connection = BlockedConnection()
        try {
            val pending = async { HttpViaggiaTrenoRealtimeFetcher(openConnection = { connection }).fetch(path) }
            assertTrue(withContext(Dispatchers.IO) { connection.entered.await(2, TimeUnit.SECONDS) })
            withTimeout(2_000) { pending.cancelAndJoin() }
            assertTrue(withContext(Dispatchers.IO) { connection.closed.await(2, TimeUnit.SECONDS) })
        } finally { connection.disconnect() }
    }

    @Test fun `whole request deadline disconnects instead of waiting for the socket read timeout`() = runBlocking {
        val connection = BlockedConnection()
        try {
            try {
                withTimeout(3_000) { HttpViaggiaTrenoRealtimeFetcher(500, { connection }).fetch(path) }
                fail("request deadline expected")
            } catch (_: SocketTimeoutException) { }
            assertEquals(0L, connection.entered.count)
            assertTrue(withContext(Dispatchers.IO) { connection.closed.await(2, TimeUnit.SECONDS) })
        } finally { connection.disconnect() }
    }

    private class BlockedConnection : HttpURLConnection(URL("https://www.viaggiatreno.it")) {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun connect() = Unit
        override fun disconnect() { closed.countDown() }
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream() = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                while (closed.count != 0L) {
                    try { closed.await() } catch (_: InterruptedException) { /* simulate a non-interruptible socket */ }
                }
                throw IOException("disconnected")
            }
        }
    }
}

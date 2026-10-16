package it.binario.app.data.live

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ViaggiaTrenoHttpsFetcherTest {
    private val path = "partenze/S08328/today"

    @Test fun `segue redirect HTTPS della fonte preservando TLS e chiudendo le connessioni`() = runBlocking {
        val opened = mutableListOf<FakeConnection>()
        val fetcher = HttpViaggiaTrenoRealtimeFetcher { url ->
            FakeConnection(url, if (opened.isEmpty()) 301 else 200,
                "https://viaggiatreno.it/infomobilita/resteasy/viaggiatreno/$path").also(opened::add)
        }
        assertEquals("[]", fetcher.fetch(path))
        assertEquals(listOf("www.viaggiatreno.it", "viaggiatreno.it"), opened.map { it.url.host })
        assertTrue(opened.all { it.closed && !it.instanceFollowRedirects })
    }

    @Test fun `rifiuta downgrade host estranei porte credenziali e percorsi fuori API`() = runBlocking {
        for (location in listOf(
            "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/$path",
            "https://example.org/infomobilita/resteasy/viaggiatreno/$path",
            "https://www.viaggiatreno.it:444/infomobilita/resteasy/viaggiatreno/$path",
            "https://user@www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/$path",
            "https://www.viaggiatreno.it/login"
        )) {
            val urls = mutableListOf<URL>()
            val fetcher = HttpViaggiaTrenoRealtimeFetcher { url -> urls.add(url); FakeConnection(url, 301, location) }
            try { fetcher.fetch(path); fail("Redirect non sicuro accettato") } catch (_: IOException) { }
            assertEquals(if (location.startsWith("http://www.viaggiatreno.it/")) 2 else 1, urls.size)
            assertTrue(urls.all { it.protocol == "https" })
        }
    }

    @Test fun `un downgrade di www prova solo l'altro indirizzo HTTPS noto`() = runBlocking {
        val urls = mutableListOf<URL>()
        val fetcher = HttpViaggiaTrenoRealtimeFetcher { url ->
            urls.add(url)
            FakeConnection(url, if (url.host == "www.viaggiatreno.it") 301 else 200,
                "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/$path")
        }
        assertEquals("[]", fetcher.fetch(path))
        assertEquals(listOf("www.viaggiatreno.it", "viaggiatreno.it"), urls.map { it.host })
        assertTrue(urls.all { it.protocol == "https" })
    }

    @Test fun `redirect HTTP alla home tenta la stessa API sul solo host HTTPS alternativo`() = runBlocking {
        val urls = mutableListOf<URL>()
        val fetcher = HttpViaggiaTrenoRealtimeFetcher { url ->
            urls.add(url)
            FakeConnection(url, if (url.host == "www.viaggiatreno.it") 301 else 200, "http://www.viaggiatreno.it/")
        }
        assertEquals("[]", fetcher.fetch(path))
        assertEquals(listOf("www.viaggiatreno.it", "viaggiatreno.it"), urls.map { it.host })
        assertTrue(urls.all { it.protocol == "https" && it.path.endsWith(path) })
    }

    @Test fun `limita un ciclo di redirect HTTPS e non lo presenta come tabellone vuoto`() = runBlocking {
        var calls = 0
        val fetcher = HttpViaggiaTrenoRealtimeFetcher { url -> calls++; FakeConnection(url, 301, url.toString()) }
        try { fetcher.fetch(path); fail("Ciclo redirect accettato") } catch (_: IOException) { }
        assertEquals(4, calls)
    }

    private class FakeConnection(url: URL, private val status: Int, private val location: String) : HttpURLConnection(url) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?): String? = if (name == "Location") location else null
        override fun getInputStream() = ByteArrayInputStream("[]".toByteArray())
    }
}

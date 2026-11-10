package it.binario.app.data.live

import java.io.IOException
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * Audit android-data-7: the RFI monitor, Italo and RFI RSS clients follow only the redirects the shared public-source
 * policy allows (same HTTPS host, path and query) and never follow one automatically to another host.
 */
class PublicSourceRedirectPolicyTest {
    private val clients: List<Pair<String, suspend ((URL) -> java.net.HttpURLConnection) -> String>> = listOf(
        "https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=2416&arrivals=False" to { open ->
            HttpRfiMonitorFetcher(2_000, open).fetch("https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=2416&arrivals=False")
        },
        "https://italoinviaggio.italotreno.com/api/RicercaTrenoService?TrainNumber=9920" to { open ->
            downloadItaloPublic("https://italoinviaggio.italotreno.com/api/RicercaTrenoService?TrainNumber=9920", 2_000, open)
        },
        RfiInfomobilityRssAdapter.NATIONAL_UPDATES_URL to { open -> HttpRfiRssFetcher(openConnection = open).fetch(RfiInfomobilityRssAdapter.NATIONAL_UPDATES_URL) }
    )

    @Test fun `a redirect to another host is refused and never followed automatically`() = runBlocking {
        for ((requested, fetch) in clients) {
            val opened = mutableListOf<FakePublicSourceConnection>()
            try {
                fetch { url ->
                    FakePublicSourceConnection(url, 302, location = "https://portal.example.test/login").also(opened::add)
                }
                fail("$requested followed a redirect to another host")
            } catch (_: IOException) { }
            assertEquals(requested, 1, opened.size)
            assertFalse(requested, opened.single().instanceFollowRedirects)
        }
    }

    @Test fun `a same host redirect to the same resource is followed`() = runBlocking {
        for ((requested, fetch) in clients) {
            var calls = 0
            val body = fetch { url ->
                calls += 1
                if (calls == 1) FakePublicSourceConnection(url, 302, location = requested)
                else FakePublicSourceConnection(url, 200, body = "ok".toByteArray())
            }
            assertEquals(requested, "ok", body)
            assertEquals(requested, 2, calls)
        }
    }
}

package it.binario.app.data.live

import it.binario.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ItaloPublicJsonTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC)
    private val route = SavedRoute("test", "Roma–Napoli", Station("roma-termini", "Roma Termini", "Roma"), Station("napoli", "Napoli Centrale", "Napoli"))

    @Test fun `legge il catalogo pubblico oggetto o lista senza confondere codici RFI e Italo`() {
        val stations = parseItaloStationCatalog("""<script>var stationCoding = {"RMT":"Roma Termini","BO2":"Bologna Centrale"}; var stationList=[{"code":"MC_","name":"Milano Centrale"}];</script>""")
        assertEquals(setOf("RMT", "BO2", "MC_"), stations.map { it.code }.toSet())
        assertEquals("bologna-centrale", stations.single { it.code == "BO2" }.slug)
    }

    @Test fun `usa il codice del catalogo ufficiale per qualsiasi stazione pubblicata`() = runBlocking {
        val urls = mutableListOf<String>()
        val fetcher = HttpItaloPublicStationFetcher { url ->
            urls += url
            if (url.endsWith("/it/stazione")) "var stationCoding={\"BO2\":\"Bologna Centrale\"};" else "board"
        }
        val slug = fetcher.resolveStationSlug(listOf("BOLOGNA CENTRALE"))
        assertEquals("bologna-centrale", slug)
        assertEquals("board", fetcher.fetch(slug!!))
        assertEquals(listOf("https://italoinviaggio.italotreno.com/it/stazione",
            "https://italoinviaggio.italotreno.com/api/RicercaStazioneService?CodiceStazione=BO2"), urls)
    }

    @Test fun `rifiuta stazioni omonime del catalogo pubblico`() = runBlocking {
        val fetcher = HttpItaloPublicStationFetcher { "var stationCoding={\"AAA\":\"Castello\",\"BBB\":\"Castello\"};" }
        assertNull(fetcher.resolveStationSlug(listOf("Castello")))
    }

    @Test fun `catalogo spostato in script esterno usa codici curati e verifica la risposta`() = runBlocking {
        val urls = mutableListOf<String>()
        val fetcher = HttpItaloPublicStationFetcher { url ->
            urls += url
            if (url.endsWith("/it/stazione")) "<script src='/stations.js'></script>" else board("12")
        }
        assertEquals("bologna-centrale", fetcher.resolveStationSlug(listOf("Bologna Centrale")))
        assertTrue(fetcher.fetch("roma-termini").contains("9923"))
        assertTrue(urls.last().endsWith("CodiceStazione=RMT"))
    }

    @Test fun `JSON pubblicato fornisce ritardo binario e ora aggiornata`() {
        val service = parse(board("12"), clock).single()
        assertEquals("9923", service.number)
        assertEquals("12:42", service.expectedDeparture)
        assertEquals(12, service.officialDelayMinutes)
        assertEquals("5", service.platform)
        assertTrue(service.operationalStatusVerified)
        assertEquals(setOf("italo-status"), service.operationalSourceIds)
    }

    @Test fun `zero pubblicato e valido ma ritardo mancante o timestamp scaduto non verificano lo stato`() {
        assertTrue(parse(board("0"), clock).single().operationalStatusVerified)
        assertFalse(parse(board("null"), clock).single().operationalStatusVerified)
        assertFalse(parse(board("12").replace("12:00:00", "11:50:00"), clock).single().operationalStatusVerified)
        assertFalse(parse(board("12").replace("12:00:00", "not-a-timestamp"), clock).single().operationalStatusVerified)
    }

    @Test fun `risposta di un altra stazione non viene applicata alla tratta`() {
        assertTrue(parse(board("12").replace("Roma Termini", "Milano Centrale"), clock).isEmpty())
        assertTrue(parse(board("12").replace("Roma Termini", "Roma Tiburtina"), clock).isEmpty())
    }

    @Test fun `cancellazione esplicita JSON resta tale`() {
        val service = parse(board("null").replace("Regolare", "Treno soppresso"), clock).single()
        assertEquals(ServiceStatus.CANCELLED, service.status)
        assertTrue(service.operationalStatusVerified)
    }

    private fun parse(json: String, clock: Clock) = parseItaloPublicBoard(json, "Roma Termini", route, Direction.OUTBOUND, clock)
    private fun board(delay: String) = """{"DescrizioneLocalita":"Roma Termini","IsEmpty":false,"LastUpdate":"12:00:00","ListaTreniPartenza":[{"Numero":9923,"DescrizioneLocalita":"Napoli Centrale","OraPassaggio":"12:30","NuovoOrario":"12:42","Ritardo":$delay,"Binario":"5","Informazioni":"Regolare"}]}"""
}

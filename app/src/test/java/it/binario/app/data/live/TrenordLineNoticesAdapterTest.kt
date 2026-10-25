package it.binario.app.data.live

import it.binario.app.data.RailOperator
import it.binario.app.data.notices.NoticeLinks
import it.binario.app.data.notices.NoticePublisher
import it.binario.app.data.notices.RailNoticesService
import it.binario.app.data.notices.TrainNoticeContext
import it.binario.app.data.notices.TrenordLineMatcher
import it.binario.app.data.provider.AlertRequest
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrenordLineNoticesAdapterTest {
    private val clock = MutablePublicSourceClock(Instant.parse("2026-10-25T19:00:00Z"))
    private val direttrici = sourceFixture("trenord-direttrici.json")

    private fun adapter(fetcher: PublicSourceFetcher = RecordingPublicSourceFetcher { direttrici }) = TrenordLineNoticesAdapter(fetcher, clock, 0)

    @Test fun `deduplicates the same news across lines and drops the promotion`() = runBlocking {
        val fetcher = RecordingPublicSourceFetcher { direttrici }
        val notices = (adapter(fetcher).getLineNotices() as ProviderResult.Success).value
        assertEquals("https://www.trenord.it/mgmt/store-management-api/mia/direttrici/", fetcher.urls.single())
        assertEquals(listOf("D034", "D010", "D004", "D005"), notices.lines.map { it.code })
        assertEquals(listOf("LAVENO", "VARESE", "SARONNO", "MILANO"), notices.lines.first().stationTokens)
        assertEquals(5, notices.alerts.size)
        val strike = notices.alerts.single { it.description!!.value.startsWith("Il sindacato USB ha indetto uno sciopero") }
        assertEquals(setOf("D034", "D010", "D004"), strike.affectedLines)
        assertEquals(Instant.parse("2026-10-23T10:36:04Z"), strike.validFrom)
        assertTrue(notices.alerts.none { "Ricaricami" in it.description!!.value })
        // The title is the first sentence, bounded; the full text stays in the description.
        assertTrue(strike.title.value.startsWith("Il sindacato USB ha indetto uno sciopero Nazionale"))
        assertTrue(strike.title.value.length <= 160 && strike.title.value.endsWith("…"))
        assertTrue(strike.description!!.value.endsWith("AvvisoTrenord_2026_228__Sciopero_29-30_ott.pdf"))
        // Newest first.
        assertEquals(notices.alerts.sortedByDescending { it.validFrom }, notices.alerts)
        // The AlertProvider view is the same deduplicated list, with a TTL of at least ten minutes.
        assertEquals(5, (adapter().getAlerts(AlertRequest()) as ProviderResult.Success).value.size)
        assertTrue(adapter().descriptor.ratePolicy.defaultTimeToLive >= Duration.ofMinutes(10))
    }

    @Test fun `the promotional filter keeps operational notices only`() {
        val promo = "Viaggiare è più semplice e più sostenibile con Ricaricami, il biglietto ricaricabile che permette di viaggiare " +
            "con lo stesso supporto su treni, metro, tram e bus in Lombardia. SCOPRI DI PIÙ: short.do/c6g1w7"
        assertFalse(TrenordLineNoticesAdapter.isOperational(promo))
        assertFalse(TrenordLineNoticesAdapter.isOperational("Scopri le nuove offerte estive per il Lago Maggiore"))
        for (text in listOf(
            "i seguenti treni saranno soppressi nella tratta Laveno Mombello Lago/Varese FN  Tradate e sostituiti con bus",
            "Il sindacato USB ha indetto uno sciopero Nazionale",
            "È stato predisposto un servizio di autobus sostitutivi",
            "Dalle 21:00 servizio bus tra Saronno e Seveso",
            "i treni subiscono variazioni",
            "La circolazione è sospesa tra Lecco e Calolziocorte",
            "RITARDI fino a 15 minuti"
        )) assertTrue(text, TrenordLineNoticesAdapter.isOperational(text))
    }

    @Test fun `URLs in the description become separate tappable segments without trailing punctuation`() {
        val text = "Saranno sostituiti con bus: https://a.mktgcdn.com/f/3714691/PjWHEpSTC0pJ8CxIxiFCNpHWgDcXYbgQRqe6xpLiwUM.pdf. Info su short.do/c6g1w7"
        val segments = NoticeLinks.segments(text)
        val links = segments.filterIsInstance<NoticeLinks.Segment.Link>()
        assertEquals(listOf("https://a.mktgcdn.com/f/3714691/PjWHEpSTC0pJ8CxIxiFCNpHWgDcXYbgQRqe6xpLiwUM.pdf"), links.map { it.url })
        assertEquals(text, segments.joinToString("") { it.text })
        assertTrue(NoticeLinks.segments("javascript:alert(1) ftp://x").none { it is NoticeLinks.Segment.Link })
    }

    @Test fun `empty, malformed and failing answers are typed results`() = runBlocking {
        assertTrue((adapter(RecordingPublicSourceFetcher { "[]" }).getLineNotices() as ProviderResult.Success).value.alerts.isEmpty())
        for (body in listOf("{}", "<html>", direttrici.take(400))) {
            val result = adapter(RecordingPublicSourceFetcher { body }).getLineNotices()
            assertEquals(ProviderErrorKind.PARSE_ERROR, (result as ProviderResult.Failure).error.kind)
        }
        val down = adapter(RecordingPublicSourceFetcher { throw PublicSourceHttpException(503, "HTTP 503") }).getAlerts(AlertRequest())
        assertEquals(ProviderErrorKind.UPSTREAM_UNAVAILABLE, (down as ProviderResult.Failure).error.kind)
    }

    @Test fun `notices reach Trenord trains, routes on a line and the boards of its stations`() = runBlocking {
        val service = RailNoticesService(emptyList(), emptyList(), adapter())
        val trenord = TrainNoticeContext("2611", LocalDate.of(2026, 10, 25), null, RailOperator.TRENORD,
            listOf("Milano Cadorna", "Saronno", "Varese Nord", "Laveno Mombello Lago"), listOf("Milano Cadorna", "Laveno Mombello Lago"))
        val forTrenord = service.forTrain(trenord).trenordNotices
        assertEquals(2, forTrenord.size)
        assertTrue(forTrenord.all { notice -> notice.lines.any { it.code == "D034" } })
        assertTrue(forTrenord.all { it.publisher == NoticePublisher.TRENORD })

        // A Trenitalia regional train between two stations of D034, inside the Trenord area.
        val route = trenord.copy(operator = RailOperator.TRENITALIA_REGIONALE, endpoints = listOf("Milano Centrale", "Varese"), nearTrenordNetwork = true)
        assertEquals(forTrenord.map { it.id }.toSet(), service.forTrain(route).trenordNotices.map { it.id }.toSet())

        // Outside the Trenord area the source is not even asked.
        val fetcher = RecordingPublicSourceFetcher { direttrici }
        val far = RailNoticesService(emptyList(), emptyList(), adapter(fetcher))
            .forTrain(route.copy(endpoints = listOf("Roma Termini", "Napoli Centrale"), nearTrenordNetwork = false))
        assertTrue(far.trenordNotices.isEmpty())
        assertTrue(fetcher.urls.isEmpty())

        val varese = service.forStation("Varese Nord").notices
        assertEquals(forTrenord.map { it.id }.toSet(), varese.map { it.id }.toSet())
        val luino = service.forStation("Luino").notices
        assertEquals(3, luino.size)
        assertTrue(service.forStation("Roma Termini").notices.isEmpty())
    }

    @Test fun `the general area groups Trenord notices by line with network-wide notices first`() = runBlocking {
        val general = RailNoticesService(emptyList(), emptyList(), adapter()).general()
        val groups = general.trenordGroups
        assertTrue(groups.first().allLines)
        assertEquals(listOf("D004", "D010", "D034"), groups.first().lines.map { it.code })
        assertEquals(listOf("D004", "D005", "D034"), groups.drop(1).map { it.lines.single().code })
        assertEquals("LUINO-GALLARATE-MILANO", groups[1].lines.single().name)
        assertEquals(5, groups.sumOf { it.notices.size })
        assertTrue(general.unavailable.isEmpty())
    }

    @Test fun `the train detail shows a notice section only when an enabled source covers the train`() {
        val trenitaliaNews = TrenitaliaInfomobilityNewsAdapter(RecordingPublicSourceFetcher { "[]" }, clock, 0)
        val service = RailNoticesService(emptyList(), listOf(trenitaliaNews), adapter())
        val base = TrainNoticeContext("28", LocalDate.of(2026, 10, 25), null, RailOperator.SBB, listOf("Lugano", "Zürich HB"), listOf("Lugano", "Zürich HB"))
        assertFalse(service.appliesTo(base))
        assertTrue(service.appliesTo(base.copy(operator = RailOperator.TRENITALIA_LONG_DISTANCE)))
        assertTrue(service.appliesTo(base.copy(operator = RailOperator.TRENORD)))
        assertTrue(service.appliesTo(base.copy(nearTrenordNetwork = true)))
        assertFalse(RailNoticesService(emptyList(), emptyList(), null).appliesTo(base.copy(operator = RailOperator.TRENORD)))
    }

    @Test fun `line matching uses whole station names, not substrings`() {
        val line = TrenordLine("D034", "LAVENO-VARESE-SARONNO-MILANO")
        assertEquals(setOf("VARESE", "MILANO"), TrenordLineMatcher.matchedTokens(line, listOf("Varese Nord", "Milano P.ta Garibaldi")))
        assertTrue(TrenordLineMatcher.matchedTokens(line, listOf("Varesina", "Saronno Sud").map { it }).let { "VARESE" !in it })
        assertTrue(TrenordLineMatcher.inTrenordArea(45.8183, 8.8335))
        assertFalse(TrenordLineMatcher.inTrenordArea(41.9010, 12.5016))
    }
}

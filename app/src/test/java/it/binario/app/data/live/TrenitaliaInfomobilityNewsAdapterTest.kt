package it.binario.app.data.live

import it.binario.app.data.RailOperator
import it.binario.app.data.core.AlertSeverity
import it.binario.app.data.core.ServiceAlert
import it.binario.app.data.notices.NoticePublisher
import it.binario.app.data.notices.NoticeText
import it.binario.app.data.notices.RailNoticesService
import it.binario.app.data.notices.TrainNoticeContext
import it.binario.app.data.provider.AlertRequest
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrenitaliaInfomobilityNewsAdapterTest {
    private val clock = MutablePublicSourceClock(Instant.parse("2026-10-25T19:00:00Z"))
    private val news = sourceFixture("trenitalia-infomobility-news.json")

    private fun adapter(fetcher: PublicSourceFetcher) = TrenitaliaInfomobilityNewsAdapter(fetcher, clock, 0)

    private suspend fun alerts(body: String): List<ServiceAlert> =
        (adapter(RecordingPublicSourceFetcher { body }).getAlerts(AlertRequest()) as ProviderResult.Success).value

    @Test fun `decodes the twice-escaped HTML of the real feed into plain text with its train tags`() = runBlocking {
        val fetcher = RecordingPublicSourceFetcher { news }
        val alerts = (adapter(fetcher).getAlerts(AlertRequest()) as ProviderResult.Success).value
        assertEquals("https://www.viaggiatreno.it/infomobilitamobile/resteasy/news/infomobility", fetcher.urls.single())
        assertEquals(2, alerts.size)
        val regular = alerts[0]
        assertEquals("CIRCOLAZIONE REGOLARE SULLA RETE ALTA VELOCITÀ", regular.title.value)
        val text = regular.description!!.value
        assertTrue(text, text.startsWith("In questo momento la circolazione si svolge regolarmente sull'intera rete Alta Velocità."))
        for (markup in listOf("<p>", "<br", "&lt;", "&agrave;", "&nbsp;", "&amp;", "\r")) assertFalse(markup, markup in text)
        assertTrue(text.contains("\n\nREGULAR TRAFFIC ON THE HIGH-SPEED RAILWAY NETWORK"))
        assertTrue(regular.affectedTrainNumbers.isEmpty())

        val maddaloni = alerts[1]
        assertEquals(setOf("8382", "8380", "716", "715"), maddaloni.affectedTrainNumbers)
        assertEquals(Instant.ofEpochMilli(1792879200000), maddaloni.validFrom)
        assertEquals(AlertSeverity.SEVERE, maddaloni.severity)
        assertTrue(maddaloni.description!!.value.contains("all’infrastruttura ferroviaria"))
        assertTrue(maddaloni.description!!.value.contains("• FR 8380 Lecce (18:10) - Napoli Centrale (23:27): il treno non ferma a Napoli Afragola."))
        assertEquals("https://www.trenitalia.com/content/trenitalia/it/informazioni/Infomobilita/notizie-infomobilita.html", maddaloni.link)
    }

    @Test fun `markup is never kept and scripts never reach the text`() {
        val hostile = JSONArray(news).getJSONObject(0)
            .put("description", "&lt;script&gt;alert(1)&lt;/script&gt;&lt;p onclick=&quot;x()&quot;&gt;Treni &amp;amp; bus&lt;/p&gt;&lt;img src=x onerror=y&gt;")
            .put("link", "javascript:alert(1)")
        val parsed = adapter(RecordingPublicSourceFetcher { "" }).parse(JSONArray().put(hostile).toString()).single()
        assertEquals("Treni & bus", parsed.description)
        assertEquals(null, parsed.link)
    }

    @Test fun `empty, malformed and failing feeds are typed results`() = runBlocking {
        assertTrue(alerts("[]").isEmpty())
        for (body in listOf("<html>", "{}", news.take(300))) {
            val result = adapter(RecordingPublicSourceFetcher { body }).getAlerts(AlertRequest())
            assertEquals(ProviderErrorKind.PARSE_ERROR, (result as ProviderResult.Failure).error.kind)
        }
        var fail = false
        val fetcher = RecordingPublicSourceFetcher { if (fail) throw IOException("offline") else news }
        val adapter = adapter(fetcher)
        adapter.getAlerts(AlertRequest())
        clock.advance(Duration.ofMinutes(11))
        fail = true
        val stale = adapter.getAlerts(AlertRequest()) as ProviderResult.Partial
        assertEquals(setOf(ProviderWarning.STALE_SOURCE), stale.warnings)
        assertEquals(2, stale.value.size)
    }

    @Test fun `the notices area deduplicates RFI and Trenitalia by title and reports a failed source`() = runBlocking {
        val rfi = RfiInfomobilityRssAdapter(fetcher = { """<?xml version="1.0"?><rss><channel>
            <item><title>Linea Roma - Napoli via Cassino: circolazione sospesa tra Maddaloni e Cancello dal 21 ottobre per lavori di ripristino a seguito dei danni all'infrastruttura causati da una società esterna</title>
            <link>https://www.rfi.it/it/news-e-media/infomobilita/1.html</link><description>&lt;p&gt;Circolazione sospesa&lt;/p&gt;</description>
            <pubDate>Sun, 25 Oct 2026 08:00:00 +0100</pubDate></item>
            <item><title>Linea Milano - Venezia: rallentamenti</title><link>https://www.rfi.it/it/news-e-media/infomobilita/2.html</link></item>
            </channel></rss>""" }, clock = clock)
        val service = RailNoticesService(emptyList(), listOf(rfi, adapter(RecordingPublicSourceFetcher { news })))
        val general = service.general()
        assertEquals(3, general.network.size)
        val maddaloni = general.network.filter { it.title.startsWith("Linea Roma - Napoli via Cassino") }
        assertEquals(listOf(NoticePublisher.RFI), maddaloni.map { it.publisher })
        assertEquals("Circolazione sospesa", maddaloni.single().body)
        assertEquals(Instant.parse("2026-10-25T07:00:00Z"), maddaloni.single().publishedAt)
        assertTrue(general.unavailable.isEmpty())

        val broken = RailNoticesService(emptyList(), listOf(rfi, adapter(RecordingPublicSourceFetcher { throw IOException("offline") }))).general()
        assertEquals(setOf(NoticePublisher.TRENITALIA), broken.unavailable)
        assertEquals(2, broken.network.size)
    }

    @Test fun `the train detail shows the Trenitalia news tagged with the train number only for Trenitalia trains`() = runBlocking {
        val service = RailNoticesService(emptyList(), listOf(adapter(RecordingPublicSourceFetcher { news })))
        val frecciarossa = TrainNoticeContext("8380", LocalDate.of(2026, 10, 25), null, RailOperator.TRENITALIA_AV, listOf("Lecce", "Napoli Centrale"))
        val notices = service.forTrain(frecciarossa)
        assertEquals(1, notices.networkNotices.size)
        assertTrue(notices.networkNotices.single().title.startsWith("Linea Roma - Napoli via Cassino"))
        val trenord = frecciarossa.copy(operator = RailOperator.TRENORD)
        assertTrue(service.forTrain(trenord).isEmpty)
        assertTrue(service.forTrain(frecciarossa.copy(publicNumber = "9999")).isEmpty)
    }

    @Test fun `plain text normalisation keeps paragraphs and bounds the length`() {
        assertEquals("a b\n\nc", NoticeText.normalize("  a \t b \r\n\n\n\n c  ", 100))
        assertEquals("abcd…", NoticeText.normalize("abcdefghij", 5))
        assertEquals(null, NoticeText.fromHtml("<p> </p>", 10))
        assertEquals(NoticeText.dedupKey("Linea Roma–Napoli: circolazione SOSPESA"), NoticeText.dedupKey("linea roma napoli circolazione sospesa"))
    }
}

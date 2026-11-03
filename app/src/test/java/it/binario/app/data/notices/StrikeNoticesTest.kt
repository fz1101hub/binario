package it.binario.app.data.notices

import it.binario.app.data.live.MutablePublicSourceClock
import it.binario.app.data.live.RecordingPublicSourceFetcher
import it.binario.app.data.live.RfiInfomobilityRssAdapter
import it.binario.app.data.live.TrenitaliaInfomobilityNewsAdapter
import it.binario.app.data.live.TrenordLineNoticesAdapter
import it.binario.app.data.live.sourceFixture
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrikeNoticesTest {
    private val clock = MutablePublicSourceClock(Instant.parse("2026-10-25T19:00:00Z"))

    @Test fun `strike wording is recognised as a whole word in either number`() {
        assertTrue(NoticeText.mentionsStrike("Il sindacato USB ha indetto uno sciopero Nazionale"))
        assertTrue(NoticeText.mentionsStrike(null, "Calendario degli SCIOPERI di novembre"))
        assertFalse(NoticeText.mentionsStrike("Lavori notturni tra Rho e Parabiago", null))
        assertFalse(NoticeText.mentionsStrike("Gli scioperanti si riuniranno"))
    }

    @Test fun `RFI, Trenitalia and Trenord notices that mention a strike carry the badge`() = runBlocking {
        val rfi = RfiInfomobilityRssAdapter(fetcher = { """<?xml version="1.0"?><rss><channel>
            <item><title>Sciopero nazionale del personale del Gruppo FS</title><link>https://www.rfi.it/it/news-e-media/infomobilita/s.html</link></item>
            <item><title>Linea Milano - Venezia: rallentamenti</title><link>https://www.rfi.it/it/news-e-media/infomobilita/r.html</link></item>
            </channel></rss>""" }, clock = clock)
        val trenitalia = TrenitaliaInfomobilityNewsAdapter(RecordingPublicSourceFetcher { sourceFixture("trenitalia-infomobility-news.json") }, clock, 0)
        val trenord = TrenordLineNoticesAdapter(RecordingPublicSourceFetcher { sourceFixture("trenord-direttrici.json") }, clock, 0)
        val general = RailNoticesService(emptyList(), listOf(rfi, trenitalia), trenord).general()

        val strikes = general.network.filter { it.mentionsStrike }
        assertEquals(listOf(NoticePublisher.RFI), strikes.map { it.publisher })
        val trenordStrikes = general.trenordGroups.flatMap { it.notices }.filter { it.mentionsStrike }
        assertEquals(1, trenordStrikes.size)
        assertTrue(trenordStrikes.single().body!!.contains("sciopero Nazionale"))
        assertTrue(general.network.filter { it.publisher == NoticePublisher.TRENITALIA }.none { it.mentionsStrike })
    }

    @Test fun `the MIT strike calendar is an HTTPS link on the ministry host`() {
        val uri = URI(RailExternalLinks.MIT_STRIKE_CALENDAR)
        assertEquals("https", uri.scheme)
        assertEquals("scioperi.mit.gov.it", uri.host)
        assertEquals("/mit2/public/scioperi", uri.path)
    }
}

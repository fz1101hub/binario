package it.binario.app.data.live

import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MitStrikeRssAdapterTest {
    private val today = LocalDate.of(2026, 11, 2)
    private val now = Instant.parse("2026-11-02T10:00:00Z")
    private val fixture = sourceFixture("mit-scioperi-rss.xml").toByteArray(Charsets.UTF_8)

    @Test fun `the RSS 2_0 reader takes title, link, description, pubDate and guid of every item`() {
        val items = Rss2Parser.parse(fixture)

        assertEquals(9, items.size)
        val first = items.first()
        assertEquals("Ferroviario - Sciopero nazionale del personale del Gruppo FS Italiane", first.title)
        assertEquals("https://scioperi.mit.gov.it/mit2/public/scioperi/dettaglio?id=12001", first.link)
        assertEquals("mit-sciopero-12001", first.guid)
        assertEquals(Instant.parse("2026-10-19T08:15:00Z"), first.pubDate)
        assertTrue(first.description!!.contains("Data inizio: 03/11/2026"))
    }

    @Test fun `only rail-sector items are kept, whatever the case or accents`() {
        val strikes = StrikeCalendar.strikes(Rss2Parser.parse(fixture), today)

        assertEquals(listOf("mit-sciopero-12001", "mit-sciopero-12004", "mit-sciopero-12006", "mit-sciopero-12008", "mit-sciopero-12009"), strikes.map { it.id })
        // Air, local public transport ("autoferrotranvieri" is not a rail word), maritime and general strikes are out.
        assertTrue(strikes.none { it.title.contains("aereo") || it.title.contains("Autoferrotranvieri") || it.title.startsWith("Marittimo") || it.title.startsWith("Generale") })
        assertTrue(StrikeCalendar.isRail(RssItem("Sciopero", null, "Settore: FERROVIÀRIO", null, null)))
        assertTrue(StrikeCalendar.isRail(RssItem("Personale RFI", null, null, null, null)))
        assertFalse(StrikeCalendar.isRail(RssItem("Sciopero del personale italiano dei porti", null, null, null, null)))
    }

    @Test fun `dates come from the item text, labelled or in order, numeric or with Italian month names`() {
        val strikes = StrikeCalendar.strikes(Rss2Parser.parse(fixture), today).associateBy { it.id }

        assertEquals(LocalDate.of(2026, 11, 3), strikes.getValue("mit-sciopero-12001").startDate)
        assertEquals(LocalDate.of(2026, 11, 4), strikes.getValue("mit-sciopero-12001").endDate)
        assertEquals(LocalDate.of(2026, 11, 21), strikes.getValue("mit-sciopero-12004").startDate)
        assertEquals(LocalDate.of(2026, 11, 22), strikes.getValue("mit-sciopero-12004").endDate)
        assertEquals(LocalDate.of(2026, 12, 3), strikes.getValue("mit-sciopero-12006").startDate)
        assertNull(strikes.getValue("mit-sciopero-12009").startDate)
        // Markup and entities of the description are reduced to text.
        assertTrue(strikes.getValue("mit-sciopero-12001").description!!.contains("USB Lavoro Privato & CUB Trasporti"))

        assertEquals(LocalDate.of(2026, 12, 14) to LocalDate.of(2026, 12, 15), StrikeCalendar.dates("Sciopero dal 14 al 15 dicembre", today))
        assertEquals(LocalDate.of(2027, 1, 9) to LocalDate.of(2027, 1, 9), StrikeCalendar.dates("Sciopero il 9 gennaio", today))
        assertEquals(LocalDate.of(2026, 11, 20) to LocalDate.of(2026, 11, 20), StrikeCalendar.dates("Data: 2026-11-20", today))
        assertEquals(LocalDate.of(2026, 11, 28) to LocalDate.of(2026, 11, 29),
            StrikeCalendar.dates("Pubblicato il 01/11/2026. Data inizio: 28/11/2026 Data fine: 29/11/2026", today))
        assertEquals(null to null, StrikeCalendar.dates("Data da confermare", today))
    }

    @Test fun `upcoming strikes are those not over yet by start date, then recent undated items`() {
        val strikes = StrikeCalendar.strikes(Rss2Parser.parse(fixture), today)

        val upcoming = StrikeCalendar.upcoming(strikes, today, now)

        assertEquals(listOf("mit-sciopero-12001", "mit-sciopero-12004", "mit-sciopero-12006", "mit-sciopero-12009"), upcoming.map { it.id })
        assertEquals(listOf("mit-sciopero-12001"), StrikeCalendar.startingTomorrow(strikes, today).map { it.id })
        assertTrue(StrikeCalendar.upcoming(strikes, today, now.plus(Duration.ofDays(30))).none { it.id == "mit-sciopero-12009" })
    }

    @Test fun `the adapter asks the feed once in six hours and serves the last list when it fails`() = runBlocking {
        val clock = MutablePublicSourceClock(now)
        var calls = 0
        var failing = false
        val adapter = MitStrikeRssAdapter({ url ->
            assertEquals(MitStrikeRssAdapter.URL, url)
            calls++
            if (failing) throw IOException("timeout") else fixture
        }, clock, 0)

        val first = adapter.strikes() as ProviderResult.Success
        assertEquals(5, first.value.size)
        clock.advance(Duration.ofHours(5))
        adapter.strikes()
        assertEquals(1, calls)

        failing = true
        clock.advance(Duration.ofHours(2))
        val stale = adapter.strikes()
        assertTrue(stale is ProviderResult.Partial && ProviderWarning.STALE_SOURCE in stale.warnings)
    }

    @Test fun `an unreachable or malformed feed is a typed failure, never an exception`() = runBlocking {
        val clock = MutablePublicSourceClock(now)
        val unreachable = MitStrikeRssAdapter({ throw IOException("connect timed out") }, clock, 0).strikes()
        val html = MitStrikeRssAdapter({ "<html><body>Manutenzione</body></html>".toByteArray() }, clock, 0).strikes()
        val doctype = MitStrikeRssAdapter({
            """<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY x "y">]><rss><channel><item><title>&x;</title></item></channel></rss>""".toByteArray()
        }, clock, 0).strikes()

        listOf(unreachable, html, doctype).forEach { result ->
            if (result !is ProviderResult.Failure) fail("Expected a failure: $result")
        }
        assertEquals(ProviderErrorKind.NETWORK, (unreachable as ProviderResult.Failure).error.kind)
        assertEquals(ProviderErrorKind.PARSE_ERROR, (html as ProviderResult.Failure).error.kind)
    }

    @Test fun `the feed is an HTTPS URL on the ministry host`() {
        val uri = java.net.URI(MitStrikeRssAdapter.URL)
        assertEquals("https", uri.scheme)
        assertEquals("scioperi.mit.gov.it", uri.host)
    }
}

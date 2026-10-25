package it.binario.app.data.live

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.notices.RailNoticesService
import it.binario.app.data.notices.TrainNoticeContext
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.TrainNoticeRequest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViaggiaTrenoTrainNoticesAdapterTest {
    private val clock = MutablePublicSourceClock(Instant.parse("2026-10-25T19:00:00Z"))
    private val rome = ZoneId.of("Europe/Rome")
    private val notes8829 = sourceFixture("viaggiatreno-smartcaring-8829.json")

    private fun adapter(fetcher: PublicSourceFetcher) = ViaggiaTrenoTrainNoticesAdapter(fetcher, clock, 0)

    @Test fun `keeps only the notes valid on the service day for the same number and origin, latest first`() = runBlocking {
        val fetcher = RecordingPublicSourceFetcher { notes8829 }
        val result = adapter(fetcher).getTrainNotices(TrainNoticeRequest("8829", LocalDate.of(2026, 7, 4), "S01700")) as ProviderResult.Success
        assertEquals("https://www.viaggiatreno.it/infomobilitamobile/resteasy/news/smartcaring?commercialTrainNumber=8829", fetcher.urls.single())
        assertEquals(listOf("viaggiatreno-train-notices:721037", "viaggiatreno-train-notices:721020"), result.value.map { it.id })
        assertTrue(result.value.first().text.startsWith("Il treno 8829 da MILANO CENTRALE a ANCONA viaggia con 32 minuti"))
        assertTrue(result.value.all { it.validFrom == LocalDate.of(2026, 7, 4) && it.validUntil == LocalDate.of(2026, 7, 4) })
    }

    @Test fun `a second real train keeps its own origin and orders same-day notes by insertion time`() = runBlocking {
        val adapter = adapter(RecordingPublicSourceFetcher { sourceFixture("viaggiatreno-smartcaring-9588.json") })
        val notes = (adapter.getTrainNotices(TrainNoticeRequest("9588", LocalDate.of(2026, 10, 11), "S11781")) as ProviderResult.Success).value
        assertEquals(listOf("viaggiatreno-train-notices:815568", "viaggiatreno-train-notices:815521"), notes.map { it.id })
        assertTrue(notes.all { it.text.startsWith("Il treno 9588 da REGGIO DI CALABRIA CENTRALE") })
        val wrongOrigin = adapter.getTrainNotices(TrainNoticeRequest("9588", LocalDate.of(2026, 10, 11), "S01700")) as ProviderResult.Success
        assertTrue(wrongOrigin.value.isEmpty())
    }

    @Test fun `past days, another origin and another number are filtered out`() = runBlocking {
        val adapter = adapter(RecordingPublicSourceFetcher { notes8829 })
        val today = adapter.getTrainNotices(TrainNoticeRequest("8829", LocalDate.of(2026, 10, 25), "S01700")) as ProviderResult.Success
        assertTrue(today.value.isEmpty())
        val otherOrigin = adapter.getTrainNotices(TrainNoticeRequest("8829", LocalDate.of(2026, 9, 13), "S08409")) as ProviderResult.Success
        assertTrue(otherOrigin.value.isEmpty())
        val unknownOrigin = adapter.getTrainNotices(TrainNoticeRequest("8829", LocalDate.of(2026, 9, 13))) as ProviderResult.Success
        assertEquals(listOf("viaggiatreno-train-notices:789111"), unknownOrigin.value.map { it.id })
        val notes = adapter.parseNotes(notes8829)
        assertTrue(adapter.select(notes, "9588", null, LocalDate.of(2026, 9, 13), clock.now).isEmpty())
    }

    @Test fun `the English text is used for other languages only when it is not blank`() = runBlocking {
        val adapter = adapter(RecordingPublicSourceFetcher { notes8829 })
        val notice = (adapter.getTrainNotices(TrainNoticeRequest("8829", LocalDate.of(2026, 9, 13), "S01700")) as ProviderResult.Success).value.single()
        assertTrue(notice.textFor("it").startsWith("Il treno 8829"))
        assertTrue(notice.textFor("en").startsWith("Buonasera, il treno 8829"))
        assertEquals(notice.text, notice.copy(englishText = "  ").textFor("de"))
        assertEquals(notice.text, notice.copy(englishText = null).textFor("fr"))
    }

    @Test fun `empty, malformed and invalid requests are typed results`() = runBlocking {
        assertTrue((adapter(RecordingPublicSourceFetcher { "[]" }).getTrainNotices(TrainNoticeRequest("2974", LocalDate.of(2026, 10, 25))) as ProviderResult.Success).value.isEmpty())
        assertTrue((adapter(RecordingPublicSourceFetcher { "null" }).getTrainNotices(TrainNoticeRequest("2974", LocalDate.of(2026, 10, 25))) as ProviderResult.Success).value.isEmpty())
        for (body in listOf("<html>Service Unavailable</html>", "{}", notes8829.take(500))) {
            val result = adapter(RecordingPublicSourceFetcher { body }).getTrainNotices(TrainNoticeRequest("8829", LocalDate.of(2026, 7, 4)))
            assertEquals(ProviderErrorKind.PARSE_ERROR, (result as ProviderResult.Failure).error.kind)
        }
        val fetcher = RecordingPublicSourceFetcher { error("nessuna richiesta attesa") }
        val invalid = adapter(fetcher).getTrainNotices(TrainNoticeRequest("FR 9588", LocalDate.of(2026, 10, 25)))
        assertEquals(ProviderErrorKind.INVALID_REQUEST, (invalid as ProviderResult.Failure).error.kind)
        assertTrue(fetcher.urls.isEmpty())
    }

    @Test fun `notes without validity, text or trains are skipped instead of guessed`() {
        val adapter = adapter(RecordingPublicSourceFetcher { "" })
        val array = JSONArray(notes8829)
        array.getJSONObject(0).remove("startValidity")
        array.getJSONObject(1).put("infoNote", "  ")
        array.getJSONObject(2).put("trains", JSONArray())
        val parsed = adapter.parseNotes(array.toString())
        assertEquals(JSONArray(notes8829).length() - 3, parsed.size)
        assertNull(parsed.firstOrNull { it.id in setOf("789111", "778132", "757390") })
    }

    @Test fun `the detail context reads origin and service day from the ViaggiaTreno run identity`() = runBlocking {
        val serviceDay = LocalDate.of(2026, 7, 4).atStartOfDay(rome).toInstant().toEpochMilli()
        val service = service("viaggiatreno:S01700:8829:$serviceDay:S01700", "8829", RailOperator.TRENITALIA_AV)
        val context = requireNotNull(TrainNoticeContext.from(service, LocalDate.of(2026, 10, 25)))
        assertEquals("S01700", context.originCode)
        assertEquals(LocalDate.of(2026, 7, 4), context.serviceDate)
        val notices = RailNoticesService(listOf(adapter(RecordingPublicSourceFetcher { notes8829 }))).forTrain(context)
        assertEquals(2, notices.trainNotices.size)
        assertTrue(!notices.unavailable)

        // A Trenord service has no ViaggiaTreno identity: the Trenitalia per-train endpoint is not queried.
        val fetcher = RecordingPublicSourceFetcher { notes8829 }
        val trenord = requireNotNull(TrainNoticeContext.from(service("lombardia-trenord-gtfs:trip:2026-07-04", "R 2611", RailOperator.TRENORD), LocalDate.of(2026, 7, 4)))
        assertEquals("2611", trenord.publicNumber)
        assertTrue(RailNoticesService(listOf(adapter(fetcher))).forTrain(trenord).trainNotices.isEmpty())
        assertTrue(fetcher.urls.isEmpty())

        val failing = RailNoticesService(listOf(adapter(RecordingPublicSourceFetcher { throw java.io.IOException("offline") }))).forTrain(context)
        assertTrue(failing.unavailable && failing.trainNotices.isEmpty())
    }

    private fun service(id: String, number: String, operator: RailOperator) = TrainService(
        id = id, number = number, operator = operator, trainCategory = TrainCategory.OTHER, origin = "Milano Centrale",
        destination = "Ancona", scheduledDeparture = "18:05", expectedDeparture = "18:05", platform = null, delayMinutes = 0,
        status = ServiceStatus.ON_TIME, stops = emptyList(), scheduledDepartureDateTime = LocalDateTime.of(2026, 7, 4, 18, 5)
    )
}

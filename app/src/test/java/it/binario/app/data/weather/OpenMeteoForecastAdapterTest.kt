package it.binario.app.data.weather

import it.binario.app.R
import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.live.MutablePublicSourceClock
import it.binario.app.data.live.RecordingPublicSourceFetcher
import it.binario.app.data.live.sourceFixture
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import it.binario.app.wmoDescription
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoForecastAdapterTest {
    private val clock = MutablePublicSourceClock(Instant.parse("2026-10-25T18:00:00Z"))
    private val sample = sourceFixture("open-meteo-lugano.json")

    private fun adapter(fetcher: RecordingPublicSourceFetcher) = OpenMeteoForecastAdapter(fetcher, clock, 0)

    @Test fun `reads the real daily forecast and sends only the rounded station coordinates`() = runBlocking {
        val fetcher = RecordingPublicSourceFetcher { sample }
        val forecast = (adapter(fetcher).forecast(45.99724, 8.945581, LocalDate.of(2026, 10, 25)) as ProviderResult.Success).value
        assertEquals(DailyForecast(LocalDate.of(2026, 10, 25), 3, 14.0, 6.5, 3), forecast)
        assertEquals(
            "https://api.open-meteo.com/v1/forecast?latitude=46.00&longitude=8.95&daily=weather_code,temperature_2m_max," +
                "temperature_2m_min,precipitation_probability_max&timezone=Europe%2FRome&forecast_days=2",
            fetcher.urls.single()
        )
        assertEquals("45.84", OpenMeteoForecastAdapter.rounded(45.835))
        assertEquals("-0.01", OpenMeteoForecastAdapter.rounded(-0.0149))
    }

    @Test fun `the same place and day is fetched once for three hours`() = runBlocking {
        val fetcher = RecordingPublicSourceFetcher { sample }
        val adapter = adapter(fetcher)
        adapter.forecast(45.99724, 8.945581, LocalDate.of(2026, 10, 25))
        val tomorrow = adapter.forecast(45.9981, 8.9461, LocalDate.of(2026, 10, 26)) as ProviderResult.Success
        assertEquals(17.3, tomorrow.value.maxTemperatureC!!, 0.0)
        assertEquals(1, fetcher.urls.size)
        clock.advance(Duration.ofHours(3).plusMinutes(1))
        adapter.forecast(45.99724, 8.945581, LocalDate.of(2026, 10, 25))
        assertEquals(2, fetcher.urls.size)
    }

    @Test fun `a day outside the forecast, malformed answers and bad coordinates are typed results`() = runBlocking {
        val missing = adapter(RecordingPublicSourceFetcher { sample }).forecast(45.99, 8.94, LocalDate.of(2026, 10, 28))
        assertEquals(ProviderErrorKind.NOT_FOUND, (missing as ProviderResult.Failure).error.kind)
        for (body in listOf("<html>", "{}", sample.take(120))) {
            val result = adapter(RecordingPublicSourceFetcher { body }).forecast(45.99, 8.94, LocalDate.of(2026, 10, 25))
            assertEquals(ProviderErrorKind.PARSE_ERROR, (result as ProviderResult.Failure).error.kind)
        }
        val down = adapter(RecordingPublicSourceFetcher { throw IOException("offline") }).forecast(45.99, 8.94, LocalDate.of(2026, 10, 25))
        assertEquals(ProviderErrorKind.NETWORK, (down as ProviderResult.Failure).error.kind)
        val fetcher = RecordingPublicSourceFetcher { sample }
        val invalid = adapter(fetcher).forecast(Double.NaN, 8.94, LocalDate.of(2026, 10, 25))
        assertEquals(ProviderErrorKind.INVALID_REQUEST, (invalid as ProviderResult.Failure).error.kind)
        assertTrue(fetcher.urls.isEmpty())
    }

    @Test fun `null and implausible values stay unknown instead of being invented`() {
        val root = JSONObject(sample)
        val daily = root.getJSONObject("daily")
        daily.getJSONArray("weather_code").put(0, JSONObject.NULL)
        daily.getJSONArray("temperature_2m_max").put(0, 999.0)
        daily.getJSONArray("precipitation_probability_max").put(0, JSONObject.NULL)
        val first = adapter(RecordingPublicSourceFetcher { "" }).parse(root.toString()).first()
        assertNull(first.weatherCode)
        assertNull(first.maxTemperatureC)
        assertEquals(6.5, first.minTemperatureC!!, 0.0)
        assertNull(first.precipitationProbabilityPercent)
    }

    @Test fun `every WMO code has its own description and unknown codes a neutral one`() {
        val codes = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
        val resources = codes.map(::wmoDescription)
        assertEquals(codes.size, resources.toSet().size)
        assertTrue(R.string.weather_code_unknown !in resources)
        assertEquals(R.string.weather_code_unknown, wmoDescription(4))
        assertEquals(R.string.weather_code_unknown, wmoDescription(null))
    }

    @Test fun `the arrival day follows the last stop and crosses midnight`() {
        fun service(departure: LocalDateTime, arrival: String) = TrainService(
            id = "x", number = "1", operator = RailOperator.OTHER, trainCategory = TrainCategory.OTHER, origin = "Milano Centrale",
            destination = "Lugano", scheduledDeparture = "%02d:%02d".format(departure.hour, departure.minute), expectedDeparture = "",
            platform = null, delayMinutes = 0, status = ServiceStatus.ON_TIME,
            stops = listOf(TrainStop("Milano Centrale", "", "", null, 0, false), TrainStop("Lugano", arrival, arrival, null, 0, false)),
            scheduledDepartureDateTime = departure
        )
        val today = LocalDate.of(2026, 10, 25)
        assertEquals(DestinationWeatherTarget("Lugano", today), DestinationWeatherTarget.of(service(today.atTime(21, 10), "22:25"), today))
        assertEquals(DestinationWeatherTarget("Lugano", today.plusDays(1)), DestinationWeatherTarget.of(service(today.atTime(23, 30), "00:40"), today))
        assertEquals(DestinationWeatherTarget("Lugano", today), DestinationWeatherTarget.of(service(today.atTime(9, 0), "—"), today))
    }
}

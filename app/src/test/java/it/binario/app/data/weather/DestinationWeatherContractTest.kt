package it.binario.app.data.weather

import it.binario.app.R
import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.live.MutablePublicSourceClock
import it.binario.app.data.live.PublicSourceFormatException
import it.binario.app.data.live.RecordingPublicSourceFetcher
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import it.binario.app.wmoDescription
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * contracts/shared-rules/v1/destination-weather-v1.json: the station and day of the forecast, the coordinates and the
 * request sent to Open-Meteo, the forecast read from its answer and the WMO codes described, as the iPhone reads them.
 */
class DestinationWeatherContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/destination-weather-v1.json").readText(Charsets.UTF_8))
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else getString(key)

    @Test fun `the station and day of the forecast`() {
        for (case in rules.getJSONArray("targets").objects()) {
            val lastStop = case.text("lastStop")
            val stops = lastStop?.let { listOf(TrainStop(it, case.text("lastScheduled") ?: "", "", null, 0, false)) }.orEmpty()
            val departure = case.getString("departure")
            val service = TrainService(
                "s", "2153", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "Origine", case.getString("destination"),
                departure, departure, null, 0, ServiceStatus.ON_TIME, stops,
                scheduledDepartureDateTime = case.text("departureDate")?.let { LocalDate.parse(it).atTime(LocalTime.parse(departure)) }
            )
            val expected = case.optJSONObject("expected")?.let { DestinationWeatherTarget(it.getString("station"), LocalDate.parse(it.getString("date"))) }
            assertEquals(case.getString("name"), expected, DestinationWeatherTarget.of(service, LocalDate.parse(case.getString("today"))))
        }
    }

    @Test fun `the coordinates and the request`() {
        for (case in rules.getJSONArray("coordinates").objects()) {
            assertEquals(case.getString("expected"), OpenMeteoForecastAdapter.rounded(case.getDouble("value")))
        }
        val clock = MutablePublicSourceClock(Instant.parse("2026-10-25T08:00:00Z"))
        for (case in rules.getJSONArray("requests").objects()) {
            val fetcher = RecordingPublicSourceFetcher { File(repositoryRoot, "app/src/test/resources/sources/open-meteo-lugano.json").readText() }
            runBlocking { OpenMeteoForecastAdapter(fetcher, clock, 0).forecast(case.getDouble("latitude"), case.getDouble("longitude"), LocalDate.of(2026, 10, 25)) }
            assertEquals(listOf(case.getString("expected")), fetcher.urls)
        }
        for (case in rules.getJSONArray("invalidCoordinates").objects()) {
            val fetcher = RecordingPublicSourceFetcher { throw AssertionError("asked $it") }
            val result = runBlocking { OpenMeteoForecastAdapter(fetcher, clock, 0).forecast(case.getDouble("latitude"), case.getDouble("longitude"), LocalDate.of(2026, 10, 25)) }
            assertEquals(ProviderErrorKind.INVALID_REQUEST, (result as ProviderResult.Failure).error.kind)
            assertTrue(fetcher.urls.isEmpty())
        }
    }

    @Test fun `the forecast read from an answer`() {
        val adapter = OpenMeteoForecastAdapter(RecordingPublicSourceFetcher { "" }, MutablePublicSourceClock(Instant.EPOCH), 0)
        for (case in rules.getJSONArray("forecasts").objects()) {
            val body = case.text("file")?.let { File(repositoryRoot, it).readText(Charsets.UTF_8) } ?: case.getString("body")
            val expected = case.opt("expected")
            if (expected == "unreadable") {
                try {
                    adapter.parse(body)
                    fail(case.getString("name"))
                } catch (_: PublicSourceFormatException) {
                }
                continue
            }
            val days = (expected as JSONArray).objects().map { day ->
                DailyForecast(
                    LocalDate.parse(day.getString("date")),
                    if (day.isNull("weatherCode")) null else day.getInt("weatherCode"),
                    if (day.isNull("maxTemperatureC")) null else day.getDouble("maxTemperatureC"),
                    if (day.isNull("minTemperatureC")) null else day.getDouble("minTemperatureC"),
                    if (day.isNull("precipitationProbabilityPercent")) null else day.getInt("precipitationProbabilityPercent")
                )
            }
            assertEquals(case.getString("name"), days, adapter.parse(body))
        }
    }

    @Test fun `the WMO codes with a text of their own`() {
        val described = rules.getJSONArray("describedCodes")
        val texts = (0 until described.length()).map { wmoDescription(described.getInt(it)) }
        assertEquals(texts.size, texts.toSet().size)
        assertTrue(texts.none { it == R.string.weather_code_unknown })
        val undescribed = rules.getJSONArray("undescribedCodes")
        for (index in 0 until undescribed.length()) assertEquals(R.string.weather_code_unknown, wmoDescription(undescribed.getInt(index)))
        assertEquals(R.string.weather_code_unknown, wmoDescription(null))
        assertNotEquals(R.string.weather_code_unknown, wmoDescription(0))
    }
}

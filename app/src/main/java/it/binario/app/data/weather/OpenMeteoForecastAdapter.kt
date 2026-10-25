package it.binario.app.data.weather

import it.binario.app.data.RailTime
import it.binario.app.data.core.SourceId
import it.binario.app.data.live.HttpPublicSourceFetcher
import it.binario.app.data.live.PublicSourceFetcher
import it.binario.app.data.live.PublicSourceFormatException
import it.binario.app.data.live.PublicSourceRequestPolicy
import it.binario.app.data.live.ResilientSourceCache
import it.binario.app.data.live.SourceRead
import it.binario.app.data.live.toProviderError
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderError
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import it.binario.app.data.provider.RailProvider
import it.binario.app.data.provider.RatePolicy
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One day of the Open-Meteo daily forecast; every value is the provider's, null when it publishes none. */
data class DailyForecast(
    val date: LocalDate,
    val weatherCode: Int?,
    val maxTemperatureC: Double?,
    val minTemperatureC: Double?,
    val precipitationProbabilityPercent: Int?
)

/**
 * Daily forecast for a station from Open-Meteo (CC BY 4.0, free for non-commercial use, no key). Only the
 * station's catalogue coordinates, rounded to two decimals (about 1 km), are sent; never the user's location.
 * Cached per rounded place and day for three hours.
 */
class OpenMeteoForecastAdapter internal constructor(
    private val fetcher: PublicSourceFetcher,
    private val clock: Clock,
    retryDelayMillis: Long
) : RailProvider {
    constructor(clock: Clock = Clock.systemUTC()) : this(HttpPublicSourceFetcher(PublicSourceRequestPolicy(maxBytes = MAX_BYTES)), clock, 750)

    override val descriptor = ProviderDescriptor(
        id = SOURCE_ID,
        displayName = "Open-Meteo — previsioni giornaliere",
        capabilities = setOf(ProviderCapability.WEATHER_FORECAST),
        coverage = CoverageDescriptor(description = "Previsione giornaliera per le coordinate della stazione di arrivo"),
        accessMode = AccessMode.OPEN_DIRECT,
        ratePolicy = RatePolicy(Duration.ofMinutes(1), defaultTimeToLive = TTL),
        schemaVersion = 1
    )

    private val cache = ResilientSourceCache<List<DailyForecast>>(TTL, Duration.ofHours(3), clock, maxEntries = 16, retryDelayMillis = retryDelayMillis)

    /** The forecast for [date] at the given station coordinates; NOT_FOUND when the provider has no such day. */
    suspend fun forecast(latitude: Double, longitude: Double, date: LocalDate): ProviderResult<DailyForecast> = withContext(Dispatchers.Default) {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return@withContext ProviderResult.Failure(ProviderError(ProviderErrorKind.INVALID_REQUEST, "Coordinate della stazione non valide."))
        }
        val lat = rounded(latitude)
        val lon = rounded(longitude)
        val url = "$BASE_URL?latitude=$lat&longitude=$lon&daily=weather_code,temperature_2m_max,temperature_2m_min," +
            "precipitation_probability_max&timezone=Europe%2FRome&forecast_days=$FORECAST_DAYS"
        val today = clock.instant().atZone(RailTime.defaultZone).toLocalDate()
        when (val read = cache.get("$lat,$lon@$today") { parse(fetcher.get(url)) }) {
            is SourceRead.Failed -> ProviderResult.Failure(read.failure.toProviderError(UNAVAILABLE, UNREADABLE))
            is SourceRead.Fresh -> read.value.firstOrNull { it.date == date }
                ?.let { ProviderResult.Success(it, ProviderResponseMeta(SOURCE_ID, read.fetchedAt, read.fetchedAt, read.expiresAt, read.fromCache)) }
                ?: notForecast()
            is SourceRead.Stale -> read.value.firstOrNull { it.date == date }
                ?.let { ProviderResult.Partial(it, setOf(ProviderWarning.STALE_SOURCE), ProviderResponseMeta(SOURCE_ID, read.fetchedAt, read.fetchedAt, fromCache = true)) }
                ?: notForecast()
        }
    }

    private fun notForecast(): ProviderResult<DailyForecast> =
        ProviderResult.Failure(ProviderError(ProviderErrorKind.NOT_FOUND, "Nessuna previsione Open-Meteo per la data richiesta."))

    internal fun parse(body: String): List<DailyForecast> {
        val daily = JSONObject(body).optJSONObject("daily") ?: throw PublicSourceFormatException("Previsione giornaliera assente")
        val days = daily.optJSONArray("time") ?: throw PublicSourceFormatException("Date della previsione assenti")
        val codes = daily.optJSONArray("weather_code")
        val maxima = daily.optJSONArray("temperature_2m_max")
        val minima = daily.optJSONArray("temperature_2m_min")
        val rain = daily.optJSONArray("precipitation_probability_max")
        return (0 until minOf(days.length(), 16)).mapNotNull { index ->
            val date = days.optString(index).takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            DailyForecast(
                date = date,
                weatherCode = codes.number(index)?.toInt()?.takeIf { it in 0..99 },
                maxTemperatureC = maxima.number(index)?.takeIf { it in -90.0..60.0 },
                minTemperatureC = minima.number(index)?.takeIf { it in -90.0..60.0 },
                precipitationProbabilityPercent = rain.number(index)?.toInt()?.takeIf { it in 0..100 }
            )
        }
    }

    private fun JSONArray?.number(index: Int): Double? {
        val value = this?.opt(index) ?: return null
        if (value == JSONObject.NULL) return null
        return (value as? Number)?.toDouble()?.takeIf { it.isFinite() }
    }

    companion object {
        val SOURCE_ID = SourceId("open-meteo")
        internal const val BASE_URL = "https://api.open-meteo.com/v1/forecast"
        const val ATTRIBUTION_URL = "https://open-meteo.com/"
        /** Today and tomorrow, as requested: a forecast further ahead is not shown. */
        const val FORECAST_DAYS = 2
        private val TTL: Duration = Duration.ofHours(3)
        private const val MAX_BYTES = 64 * 1024
        private const val UNAVAILABLE = "Previsione Open-Meteo non raggiungibile."
        private const val UNREADABLE = "Previsione Open-Meteo non leggibile."

        /** Two decimals, half up, in a locale-independent form: the only place information sent to Open-Meteo. */
        internal fun rounded(value: Double): String = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString()
    }
}

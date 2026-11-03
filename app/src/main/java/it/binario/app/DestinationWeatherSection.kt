package it.binario.app

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import it.binario.app.data.RailTime
import it.binario.app.data.TrainService
import it.binario.app.data.notices.StationCoordinateIndex
import it.binario.app.data.weather.DestinationWeatherTarget
import it.binario.app.data.weather.OpenMeteoForecastAdapter
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Meteo a destinazione": the Open-Meteo forecast of the arrival day for the last station shown, placed with the
 * curated catalogue coordinates. Requested only when the detail is shown and the setting is on; a station without
 * catalogue coordinates shows nothing rather than a guessed place.
 */
@Composable
fun DestinationWeatherSection(service: TrainService, viewModel: RailNoticesViewModel, index: StationCoordinateIndex?) {
    if (!viewModel.destinationWeatherEnabled || !viewModel.hasWeatherSource || index == null) return
    val target = remember(service.id, service.stops, service.scheduledDepartureDateTime) {
        DestinationWeatherTarget.of(service, LocalDate.now(RailTime.defaultZone))
    } ?: return
    val station = remember(target, index) { index.find(target.stationName) } ?: return
    val latitude = station.latitude ?: return
    val longitude = station.longitude ?: return
    val key = "${station.id}@${target.arrivalDate}"
    LaunchedEffect(key) { viewModel.loadWeather(key, latitude, longitude, target.arrivalDate) }
    val locale = LocalConfiguration.current.locales[0]
    val uriHandler = LocalUriHandler.current
    SourceSectionCard(title = stringResource(R.string.weather_title)) {
        SourceSectionNote(
            stringResource(R.string.weather_station_date, station.name,
                target.arrivalDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)))
        )
        when (val state = viewModel.weatherFor(key)) {
            SourceSectionState.Idle, SourceSectionState.Loading -> SourceSectionNote(stringResource(R.string.weather_loading))
            is SourceSectionState.Ready -> when (val result = state.value) {
                DestinationWeatherResult.OutOfRange -> SourceSectionNote(stringResource(R.string.weather_out_of_range))
                DestinationWeatherResult.Unavailable -> SourceSectionNote(stringResource(R.string.weather_unavailable))
                is DestinationWeatherResult.Forecast -> {
                    val forecast = result.forecast
                    Text(stringResource(wmoDescription(forecast.weatherCode)), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    val numbers = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1; minimumFractionDigits = 0 } }
                    if (forecast.maxTemperatureC != null && forecast.minTemperatureC != null) {
                        Text(stringResource(R.string.weather_temperatures, numbers.format(forecast.maxTemperatureC), numbers.format(forecast.minTemperatureC)),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    forecast.precipitationProbabilityPercent?.let {
                        Text(stringResource(R.string.weather_precipitation, it), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (result.stale) SourceSectionNote(stringResource(R.string.notices_stale))
                }
            }
        }
        // CC BY 4.0 attribution, shown with every forecast state; the link opens only on tap.
        TextButton(onClick = { openExternalLink(uriHandler, OpenMeteoForecastAdapter.ATTRIBUTION_URL) }) {
            Text(stringResource(R.string.weather_attribution), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Localised description of every WMO weather interpretation code published by Open-Meteo. */
@StringRes
internal fun wmoDescription(code: Int?): Int = when (code) {
    0 -> R.string.weather_code_0
    1 -> R.string.weather_code_1
    2 -> R.string.weather_code_2
    3 -> R.string.weather_code_3
    45 -> R.string.weather_code_45
    48 -> R.string.weather_code_48
    51 -> R.string.weather_code_51
    53 -> R.string.weather_code_53
    55 -> R.string.weather_code_55
    56 -> R.string.weather_code_56
    57 -> R.string.weather_code_57
    61 -> R.string.weather_code_61
    63 -> R.string.weather_code_63
    65 -> R.string.weather_code_65
    66 -> R.string.weather_code_66
    67 -> R.string.weather_code_67
    71 -> R.string.weather_code_71
    73 -> R.string.weather_code_73
    75 -> R.string.weather_code_75
    77 -> R.string.weather_code_77
    80 -> R.string.weather_code_80
    81 -> R.string.weather_code_81
    82 -> R.string.weather_code_82
    85 -> R.string.weather_code_85
    86 -> R.string.weather_code_86
    95 -> R.string.weather_code_95
    96 -> R.string.weather_code_96
    99 -> R.string.weather_code_99
    else -> R.string.weather_code_unknown
}

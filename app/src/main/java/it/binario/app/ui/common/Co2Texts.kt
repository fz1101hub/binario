package it.binario.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import it.binario.app.R
import it.binario.app.co2.Co2Estimate
import it.binario.app.co2.Co2Estimator
import it.binario.app.co2.Co2Point
import it.binario.app.co2.EmissionFactors
import it.binario.app.data.TrainService
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.maps.OpenMapStationIndex
import it.binario.app.ui.journeys.JourneyLegKind
import kotlin.math.roundToInt

// The CO2 estimate of a solution, from the catalogue positions of its stations (no request leaves the device).

/** The catalogue position of a published station name, or null when the catalogue cannot tell it for sure. */
internal fun OpenMapStationIndex.co2Point(name: String): Co2Point? = locate(name)?.let { station ->
    val latitude = station.latitude ?: return@let null
    val longitude = station.longitude ?: return@let null
    Co2Point(latitude, longitude)
}

/** A direct train: the great circle between its departure and arrival stations. */
@Composable
internal fun rememberCo2(service: TrainService, index: OpenMapStationIndex): Co2Estimate? =
    remember(service.origin, service.destination, index) {
        Co2Estimator.estimateOrNull(listOf(index.co2Point(service.origin) to index.co2Point(service.destination)))
    }

/** A journey with changes: every vehicle leg between its stations (walks between stations are left out). */
@Composable
internal fun rememberCo2(row: JourneyOptionRow, index: OpenMapStationIndex): Co2Estimate? =
    remember(row.id, index) {
        Co2Estimator.estimateOrNull(
            row.legs.filter { JourneyLegKind.of(it.mode) != JourneyLegKind.WALK }
                .map { index.co2Point(it.fromLabel) to index.co2Point(it.toLabel) }
        )
    }

/** "CO₂ stimata: 4,8 kg (auto ~31 kg, aereo ~74 kg)". */
@Composable
internal fun co2Line(estimate: Co2Estimate): String {
    val locale = LocalConfiguration.current.locales[0]
    return stringResource(
        R.string.co2_estimate_line,
        Co2Estimator.displayKg(estimate.trainKg, locale),
        Co2Estimator.displayKg(estimate.carKg, locale),
        Co2Estimator.displayKg(estimate.planeKg, locale)
    )
}

/** The same estimate in words for screen readers. */
@Composable
internal fun co2Spoken(estimate: Co2Estimate): String {
    val locale = LocalConfiguration.current.locales[0]
    return stringResource(
        R.string.co2_estimate_accessible,
        Co2Estimator.displayKg(estimate.trainKg, locale),
        Co2Estimator.displayKg(estimate.carKg, locale),
        Co2Estimator.displayKg(estimate.planeKg, locale)
    )
}

/** The credit of the emission factors, with source, values and the link to the report. */
@Composable
internal fun co2Credits(): String = stringResource(
    R.string.settings_credits_co2,
    EmissionFactors.SOURCE,
    EmissionFactors.TRAIN_GRAMS_PER_PKM.roundToInt().toString(),
    EmissionFactors.CAR_GRAMS_PER_PKM.roundToInt().toString(),
    EmissionFactors.PLANE_GRAMS_PER_PKM_LONG.roundToInt().toString(),
    EmissionFactors.PLANE_GRAMS_PER_PKM_SHORT.roundToInt().toString(),
    EmissionFactors.SOURCE_URL
)

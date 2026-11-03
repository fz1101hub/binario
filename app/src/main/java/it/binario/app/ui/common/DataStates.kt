package it.binario.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import it.binario.app.R
import it.binario.app.data.DataConfidence
import it.binario.app.data.Freshness
import it.binario.app.data.TrainService

/**
 * What the departures on screen are, in one visual language for every surface: "Live" only when a source verified
 * at least one run, "Orario programmato" for a timetable (offline GTFS, RFI programmed timetable, a live source
 * without a match), "Copia salvata" for a cache or an expired snapshot; demonstration and unavailable data apart.
 * A refresh never makes scheduled data "live" (design system: «aggiornare non implica necessariamente dato live»).
 */
internal enum class DataState {
    LIVE, SCHEDULED, SAVED_COPY, DEMONSTRATION, UNAVAILABLE;

    companion object {
        /**
         * @param anyVerified at least one listed run carries an operational status a source verified.
         * @param hasServices false for an empty snapshot, whose state then follows the confidence alone.
         */
        fun of(
            freshness: Freshness?,
            confidence: DataConfidence?,
            anyVerified: Boolean,
            hasServices: Boolean,
            isDemonstration: Boolean
        ): DataState = when {
            isDemonstration || confidence == DataConfidence.DEMONSTRATION -> DEMONSTRATION
            freshness == null || freshness == Freshness.UNAVAILABLE -> UNAVAILABLE
            confidence == DataConfidence.CACHED || freshness == Freshness.STALE -> SAVED_COPY
            anyVerified -> LIVE
            !hasServices && confidence != null && confidence != DataConfidence.STATIC_ONLY -> LIVE
            else -> SCHEDULED
        }

        fun of(freshness: Freshness?, confidence: DataConfidence?, services: List<TrainService>, isDemonstration: Boolean): DataState =
            of(freshness, confidence, services.any { it.operationalStatusVerified }, services.isNotEmpty(), isDemonstration)

        /** The state of one run of a list in [listState]: a saved copy stays a copy, otherwise its own verification decides. */
        fun ofService(service: TrainService, listState: DataState): DataState = when {
            listState == SAVED_COPY -> SAVED_COPY
            service.operationalStatusVerified -> LIVE
            else -> SCHEDULED
        }
    }
}

/** The headline of the data status card: what the data are, never "live" for a timetable. */
@Composable
internal fun DataState.statusLabel(freshness: Freshness?, updatedAt: String): String = when (this) {
    DataState.DEMONSTRATION -> stringResource(R.string.data_status_demo)
    DataState.UNAVAILABLE -> stringResource(R.string.data_status_unavailable)
    DataState.SAVED_COPY -> stringResource(R.string.data_status_cached, updatedAt)
    DataState.SCHEDULED -> stringResource(R.string.data_status_scheduled)
    DataState.LIVE -> stringResource(R.string.data_status_live, (freshness ?: Freshness.FRESH).localizedLabel())
}

/** The short label of a card's chip: "In tempo reale", "Programmato" or "Copia salvata". */
@Composable
internal fun DataState.chipLabel(): String = when (this) {
    DataState.LIVE -> stringResource(R.string.data_chip_live)
    DataState.SAVED_COPY -> stringResource(R.string.data_chip_saved_copy)
    DataState.SCHEDULED, DataState.DEMONSTRATION, DataState.UNAVAILABLE -> stringResource(R.string.service_status_scheduled_badge)
}

@Composable
internal fun DataState.accentColor(): Color = when (this) {
    DataState.LIVE -> MaterialTheme.colorScheme.secondary
    DataState.SCHEDULED -> MaterialTheme.colorScheme.onSurfaceVariant
    DataState.SAVED_COPY, DataState.DEMONSTRATION -> MaterialTheme.colorScheme.tertiary
    DataState.UNAVAILABLE -> MaterialTheme.colorScheme.error
}

/** The small chip of a result card: the three states in the colours of the data status card. */
@Composable
internal fun DataStateChip(state: DataState, modifier: Modifier = Modifier) {
    val (container, content) = when (state) {
        DataState.LIVE -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        DataState.SAVED_COPY -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Pill(state.chipLabel(), modifier = modifier, container = container, content = content)
}

/**
 * Provider labels as the data layer composes them ("Trenitalia · orario programmato", "RFI — Quadro orario
 * programmato", "… + fonte ferroviaria"): operator and source names stay data, the Italian descriptors between the
 * separators are shown in the app language.
 */
internal object ProviderLabels {
    /** Descriptor (lower case) → string resource; anything else in a label is a proper name and stays as published. */
    val DESCRIPTORS: Map<String, Int> = mapOf(
        "orario programmato" to R.string.provider_descriptor_scheduled,
        "quadro orario programmato" to R.string.provider_descriptor_rfi_programmed,
        "quadri orario programmati" to R.string.provider_descriptor_rfi_programmed,
        "fonte ferroviaria" to R.string.provider_descriptor_rail_source,
        "fallback best-effort" to R.string.provider_descriptor_fallback,
        "best effort" to R.string.provider_descriptor_best_effort,
        "tabellone pubblico" to R.string.provider_descriptor_public_board,
        "monitor arrivi/partenze live" to R.string.provider_descriptor_rfi_monitor_live,
        "monitor arrivi/partenze" to R.string.provider_descriptor_rfi_monitor,
        "tabelloni svizzeri" to R.string.provider_descriptor_swiss_boards
    )

    private val SEPARATORS = Regex("( \\+ | / | · | — )")

    /** [raw] with each known descriptor replaced by [translate] of its resource; unknown parts unchanged. */
    fun localize(raw: String, translate: (Int) -> String): String {
        if (raw.isBlank()) return raw
        val result = StringBuilder()
        var last = 0
        fun part(text: String) {
            val trimmed = text.trim()
            val resource = DESCRIPTORS[trimmed.lowercase(java.util.Locale.ROOT)]
            result.append(if (resource == null) text else text.replace(trimmed, translate(resource)))
        }
        SEPARATORS.findAll(raw).forEach { match ->
            part(raw.substring(last, match.range.first))
            result.append(match.value)
            last = match.range.last + 1
        }
        part(raw.substring(last))
        return result.toString()
    }
}

/** [raw] (a provider label of the data layer) in the app language; see [ProviderLabels]. */
@Composable
internal fun localizedProviderName(raw: String): String {
    val resources = LocalResources.current
    return ProviderLabels.localize(raw) { resources.getString(it) }
}

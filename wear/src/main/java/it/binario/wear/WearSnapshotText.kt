package it.binario.wear

import android.content.Context
import androidx.annotation.StringRes
import it.binario.wear.model.WearFreshness
import it.binario.wear.model.WearOperationalState
import it.binario.wear.model.WearService
import it.binario.wear.model.WearSnapshot
import it.binario.wear.model.WearVehicle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Resource seam: Tile and complication payloads are built, and unit-tested, without a Context. */
internal fun interface WearStringProvider {
    fun string(@StringRes id: Int, vararg formatArgs: Any): String
}

internal fun Context.wearStrings(): WearStringProvider = WearStringProvider { id, formatArgs -> getString(id, *formatArgs) }

/** A string resource with its arguments; Compose resolves it with stringResource, Tile and complication with a [WearStringProvider]. */
internal data class WearText(@StringRes val id: Int, val args: List<Any> = emptyList())

internal fun WearStringProvider.string(text: WearText): String = string(text.id, *text.args.toTypedArray())

/** The phone formats rail times as Italian clock times ("HH:mm" in Europe/Rome, RailTime.defaultZone). */
internal val RAIL_TIME_ZONE: ZoneId = ZoneId.of("Europe/Rome")

private val RAIL_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(RAIL_TIME_ZONE)

/** [epochMillis] as the clock time the departures use. */
internal fun railClockTime(epochMillis: Long): String = RAIL_CLOCK.format(Instant.ofEpochMilli(epochMillis))

/**
 * The data status line. The watch keeps a snapshot for up to fifteen minutes, so live values name
 * the time the phone's source observed them instead of reading as current for that long; data the
 * phone already marked as not recent say so, rather than "updating" while nothing updates.
 */
internal fun WearSnapshot.dataStatus(): WearText = when (operationalState) {
    WearOperationalState.PROGRAMMED -> WearText(R.string.status_programmed)
    WearOperationalState.OPERATIONAL -> when (freshness) {
        WearFreshness.FRESH -> WearText(R.string.status_live_as_of, listOf(railClockTime(provenance.observedAtEpochMillis)))
        WearFreshness.AGING -> WearText(R.string.status_not_recent_as_of, listOf(railClockTime(provenance.observedAtEpochMillis)))
        WearFreshness.STALE -> WearText(R.string.status_local_snapshot)
        WearFreshness.UNAVAILABLE, WearFreshness.UNKNOWN -> WearText(R.string.status_live_unavailable)
    }
    WearOperationalState.UNAVAILABLE, WearOperationalState.UNKNOWN -> WearText(R.string.status_live_unavailable)
}

/**
 * A missing phone app gets its own message; a snapshot that [expired] asks for a refresh, since
 * sharing is already on; otherwise the phone setting that enables sharing is named.
 */
@StringRes
internal fun emptySnapshotMessageResId(phoneApp: WearPhoneAppPresence, expired: Boolean): Int = when {
    phoneApp == WearPhoneAppPresence.ABSENT -> R.string.empty_snapshot_phone_app_missing
    expired -> R.string.snapshot_expired_message
    else -> R.string.empty_snapshot_message
}

/**
 * The name of a city line's mode in the watch's language: the phone's `journey_leg_metro`, `journey_leg_tram` and
 * `journey_leg_funicular`, in the same 17 languages. Null for a train, a bus or an unknown vehicle.
 */
@StringRes
internal fun WearVehicle.urbanLabelRes(): Int? = when (this) {
    WearVehicle.METRO -> R.string.vehicle_metro
    WearVehicle.TRAM -> R.string.vehicle_tram
    WearVehicle.FUNICULAR -> R.string.vehicle_funicular
    else -> null
}

/**
 * The category the watch screen shows: the mode of a city line in the watch's language («Metro»), else the category the
 * phone sent (for a bus its label, «Bus Trenitalia»).
 */
internal fun WearService.shownCategory(strings: WearStringProvider): String =
    vehicle?.urbanLabelRes()?.let { strings.string(it) } ?: category

/**
 * The number as the Tile and the complication show it: a bus with its label first («Bus Trenitalia 3601», the phone
 * sends the label as its category), a city line with its mode and line («Metro M1», [WearUrbanLabel]), a train its
 * number alone. The glances are text only, so the label stands for the vehicle's icon.
 */
internal fun WearService.glanceNumber(strings: WearStringProvider): String = when {
    isUrban -> WearUrbanLabel.of(shownCategory(strings), number)
    isBus -> listOf(category, number).filter(String::isNotBlank).joinToString(" ")
    else -> number
}

/**
 * A city line named as the phone names it (Android's `journeyLegLabel`, the iPhone's `TimetableVehicle.urbanLabel`):
 * the mode and the line, «Metro M1», «Tram 14», «Funicolare FRI»; a city run has no public number, and the phone sends
 * the line in its place. A line that is a long name, a placeholder or only repeats the mode («METRO», «Tramvia») is left
 * out. The cases are the phones' (`urbanLabels` in contracts/shared-rules/v1/journey-leg-vehicles-v1.json).
 */
internal object WearUrbanLabel {
    private const val MAX_LINE_LENGTH = 10
    private val MODE_WORDS = setOf(
        "metro", "metropolitana", "métro", "u-bahn", "subway", "tram", "tramvia", "tranvia", "tranvía", "tramway",
        "straßenbahn", "funicolare", "funicular", "funiculaire", "standseilbahn"
    )

    fun of(label: String, line: String?): String {
        val shown = line?.trim()?.takeIf {
            it.isNotEmpty() && it.length <= MAX_LINE_LENGTH && '(' !in it && !it.equals("INCONNU", ignoreCase = true) &&
                !repeatsMode(it, label)
        }
        return if (shown == null) label else "$label $shown"
    }

    /** The line, without spaces and in lower case, is the label itself or a word that names a city mode. */
    fun repeatsMode(line: String, label: String): Boolean {
        val compact = line.replace(" ", "").lowercase()
        return compact == label.replace(" ", "").lowercase() || compact in MODE_WORDS
    }
}

/** [reliability] is the spoken reliability line of the watch screen; the tile and the complication have none. */
internal fun WearService.accessibleLabel(context: Context, reliability: String? = null): String = accessibleLabel(context.wearStrings(), reliability)

internal fun WearService.accessibleLabel(strings: WearStringProvider, reliability: String? = null): String = listOfNotNull(
    strings.string(R.string.accessibility_scheduled_departure, scheduledDeparture),
    strings.string(R.string.accessibility_expected_departure, expectedDeparture),
    // A bus or a city line is said with its label («Bus Trenitalia 3601», «Metro M1»), never as a train.
    if (isBus || isUrban) glanceNumber(strings) else strings.string(R.string.accessibility_train, number),
    destination.takeIf { it.isNotBlank() },
    operator.takeIf { it.isNotBlank() },
    category.takeIf { it.isNotBlank() && !isBus && !isUrban },
    platform?.let { strings.string(R.string.accessibility_platform, it) },
    status.takeIf { it.isNotBlank() },
    reliability?.let { strings.string(R.string.accessibility_reliability, it) },
).joinToString(". ")

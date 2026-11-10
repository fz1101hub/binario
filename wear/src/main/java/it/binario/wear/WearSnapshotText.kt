package it.binario.wear

import android.content.Context
import androidx.annotation.StringRes
import it.binario.wear.model.WearFreshness
import it.binario.wear.model.WearOperationalState
import it.binario.wear.model.WearService
import it.binario.wear.model.WearSnapshot
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

/** A missing phone app gets its own message; otherwise the phone setting that enables sharing is named. */
@StringRes
internal fun emptySnapshotMessageResId(phoneApp: WearPhoneAppPresence): Int = when (phoneApp) {
    WearPhoneAppPresence.ABSENT -> R.string.empty_snapshot_phone_app_missing
    WearPhoneAppPresence.PRESENT, WearPhoneAppPresence.UNKNOWN -> R.string.empty_snapshot_message
}

/** [reliability] is the spoken reliability line of the watch screen; the tile and the complication have none. */
internal fun WearService.accessibleLabel(context: Context, reliability: String? = null): String = accessibleLabel(context.wearStrings(), reliability)

internal fun WearService.accessibleLabel(strings: WearStringProvider, reliability: String? = null): String = listOfNotNull(
    strings.string(R.string.accessibility_scheduled_departure, scheduledDeparture),
    strings.string(R.string.accessibility_expected_departure, expectedDeparture),
    strings.string(R.string.accessibility_train, number),
    destination.takeIf { it.isNotBlank() },
    operator.takeIf { it.isNotBlank() },
    category.takeIf { it.isNotBlank() },
    platform?.let { strings.string(R.string.accessibility_platform, it) },
    status.takeIf { it.isNotBlank() },
    reliability?.let { strings.string(R.string.accessibility_reliability, it) },
).joinToString(". ")

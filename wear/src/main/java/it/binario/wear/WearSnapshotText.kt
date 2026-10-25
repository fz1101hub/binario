package it.binario.wear

import android.content.Context
import androidx.annotation.StringRes
import it.binario.wear.model.WearFreshness
import it.binario.wear.model.WearOperationalState
import it.binario.wear.model.WearService
import it.binario.wear.model.WearSnapshot

/** Resource seam: Tile and complication payloads are built, and unit-tested, without a Context. */
internal fun interface WearStringProvider {
    fun string(@StringRes id: Int, vararg formatArgs: Any): String
}

internal fun Context.wearStrings(): WearStringProvider = WearStringProvider { id, formatArgs -> getString(id, *formatArgs) }

@StringRes
internal fun WearSnapshot.dataStatusResId(): Int = when (operationalState) {
    WearOperationalState.PROGRAMMED -> R.string.status_programmed
    WearOperationalState.OPERATIONAL -> when (freshness) {
        WearFreshness.FRESH -> R.string.status_received_from_phone
        WearFreshness.AGING -> R.string.status_updating
        WearFreshness.STALE -> R.string.status_local_snapshot
        WearFreshness.UNAVAILABLE, WearFreshness.UNKNOWN -> R.string.status_live_unavailable
    }
    WearOperationalState.UNAVAILABLE, WearOperationalState.UNKNOWN -> R.string.status_live_unavailable
}

internal fun WearService.accessibleLabel(context: Context): String = accessibleLabel(context.wearStrings())

internal fun WearService.accessibleLabel(strings: WearStringProvider): String = listOfNotNull(
    strings.string(R.string.accessibility_scheduled_departure, scheduledDeparture),
    strings.string(R.string.accessibility_expected_departure, expectedDeparture),
    strings.string(R.string.accessibility_train, number),
    destination.takeIf { it.isNotBlank() },
    operator.takeIf { it.isNotBlank() },
    category.takeIf { it.isNotBlank() },
    platform?.let { strings.string(R.string.accessibility_platform, it) },
    status.takeIf { it.isNotBlank() },
).joinToString(". ")

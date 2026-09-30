package it.binario.wear

import android.content.Context
import androidx.annotation.StringRes
import it.binario.wear.model.WearFreshness
import it.binario.wear.model.WearOperationalState
import it.binario.wear.model.WearService
import it.binario.wear.model.WearSnapshot

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

internal fun WearService.accessibleLabel(context: Context): String = listOfNotNull(
    context.getString(R.string.accessibility_scheduled_departure, scheduledDeparture),
    context.getString(R.string.accessibility_expected_departure, expectedDeparture),
    context.getString(R.string.accessibility_train, number),
    destination.takeIf { it.isNotBlank() },
    operator.takeIf { it.isNotBlank() },
    category.takeIf { it.isNotBlank() },
    platform?.let { context.getString(R.string.accessibility_platform, it) },
    status.takeIf { it.isNotBlank() },
).joinToString(". ")

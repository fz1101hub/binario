package it.binario.wear

import it.binario.wear.complication.DEPARTED_GRACE_MILLIS
import it.binario.wear.complication.departureInstant
import it.binario.wear.model.WearConfidence
import it.binario.wear.model.WearDataNature
import it.binario.wear.model.WearOperationalState
import it.binario.wear.model.WearSnapshot

/**
 * How long the live values of an operational snapshot are shown after its generation (contracts/wear-snapshot/v1,
 * «Ricaduta sull'orario programmato»): Android's display window. A phone that knows this watch falls back sends the
 * programmed lifetime as the expiry, so after the window the glance shows the programmed times instead of expiring.
 */
internal const val LIVE_WINDOW_MILLIS = 15 * 60 * 1_000L

/** Until when the live values of [this] are shown; null for a snapshot without live values. */
internal val WearSnapshot.liveUntilEpochMillis: Long?
    get() = if (operationalState == WearOperationalState.OPERATIONAL) generatedAtEpochMillis + LIVE_WINDOW_MILLIS else null

/** The snapshot as the watch shows it at [nowEpochMillis]: its live values within their window, else [programmedFallback]. */
internal fun WearSnapshot.shownAt(nowEpochMillis: Long, programmedStatus: String): WearSnapshot {
    val liveUntil = liveUntilEpochMillis ?: return this
    return if (nowEpochMillis < liveUntil) this else programmedFallback(programmedStatus)
}

/**
 * The programmed snapshot under the live one: the live part dropped, the services kept. Each service goes back to its
 * scheduled time (its expected departure less its delay, so a late train listed after its scheduled time is placed
 * right), with no delay, no platform and [programmedStatus]; a service whose scheduled departure is gone when the live
 * values expire is left out, and the others are in scheduled order. The live notice and source label go too: the
 * status line says «Orario programmato».
 */
internal fun WearSnapshot.programmedFallback(programmedStatus: String): WearSnapshot {
    val liveUntil = liveUntilEpochMillis ?: return this
    val scheduled = services.map { service ->
        service to departureInstant(service.expectedDeparture, generatedAtEpochMillis)?.minusSeconds(service.delayMinutes * 60L)
    }
    return copy(
        provenance = provenance.copy(dataNature = WearDataNature.PROGRAMMED, sourceLabel = ""),
        operationalState = WearOperationalState.PROGRAMMED,
        confidence = WearConfidence.STATIC_ONLY,
        notice = "",
        services = scheduled
            .filter { (_, departure) -> departure == null || departure.toEpochMilli() + DEPARTED_GRACE_MILLIS > liveUntil }
            .sortedBy { (_, departure) -> departure?.toEpochMilli() ?: Long.MAX_VALUE }
            .map { (service, _) ->
                service.copy(expectedDeparture = service.scheduledDeparture, delayMinutes = 0, status = programmedStatus, platform = null)
            },
    )
}

/**
 * [this] as the app lists it at [nowEpochMillis]: [shownAt], without the services gone by then (one minute after their
 * departure, as the glances drop them). A phone that knows this watch falls back keeps a snapshot until its last
 * train, so a train that has left must not stay in the list for that long.
 */
internal fun WearSnapshot.listedAt(nowEpochMillis: Long, programmedStatus: String): WearSnapshot {
    val shown = shownAt(nowEpochMillis, programmedStatus)
    val remaining = shown.services.filter { service ->
        val departure = departureInstant(service.expectedDeparture, shown.generatedAtEpochMillis) ?: return@filter true
        departure.toEpochMilli() + DEPARTED_GRACE_MILLIS > nowEpochMillis
    }
    return if (remaining.size == shown.services.size) shown else shown.copy(services = remaining)
}

/** The next instant after [nowEpochMillis], before the deadline, at which [listedAt] changes; null when it does not. */
internal fun WearSnapshot.nextListChangeAt(nowEpochMillis: Long): Long? {
    fun gone(phase: WearSnapshot) = phase.services.mapNotNull { service ->
        departureInstant(service.expectedDeparture, phase.generatedAtEpochMillis)?.toEpochMilli()?.plus(DEPARTED_GRACE_MILLIS)
    }
    val liveUntil = liveUntilEpochMillis
    // The live departures count until the live values expire, the programmed ones after.
    val changes = if (liveUntil == null) gone(this) else gone(this).filter { at -> at < liveUntil } + liveUntil + gone(programmedFallback(""))
    return changes.filter { at -> at > nowEpochMillis && at < expiresAtEpochMillis }.minOrNull()
}

package it.binario.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import it.binario.wear.R
import it.binario.wear.WearMainActivity
import it.binario.wear.WearStringProvider
import it.binario.wear.accessibleLabel
import it.binario.wear.RAIL_TIME_ZONE
import it.binario.wear.dataStatus
import it.binario.wear.string
import it.binario.wear.model.WearService
import it.binario.wear.model.WearSnapshot
import it.binario.wear.sync.WearSnapshotStore
import it.binario.wear.wearStrings
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Watch-face surface for the same local snapshot rendered by the Wear app and Tile. Nothing requests
 * an update when a train leaves, so the value comes as a timeline: the watch face itself moves to
 * the next service one minute after a departure.
 */
class BinarioComplicationService : SuspendingTimelineComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline {
        val snapshot = WearSnapshotStore.current(this)
        val strings = wearStrings()
        val now = System.currentTimeMillis()
        val tapAction = openAppIntent()
        val current = binarioComplicationData(request.complicationType, snapshot, strings, now, tapAction)
        val later = if (snapshot == null || current is NoDataComplicationData) emptyList() else laterGlanceSegments(snapshot, now)
        return ComplicationDataTimeline(
            current,
            later.map { segment ->
                TimelineEntry(
                    TimeInterval(Instant.ofEpochMilli(segment.startEpochMillis), Instant.ofEpochMilli(segment.endEpochMillis)),
                    binarioComplicationData(request.complicationType, snapshot, strings, segment.startEpochMillis, tapAction),
                )
            },
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val label = PlainComplicationText.Builder(getString(R.string.app_name)).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(label, label).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(label, label).build()
            else -> NoDataComplicationData()
        }
    }

    /** A tap opens the watch app on the services behind the glance. */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, WearMainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Texts resolved before they are wrapped in platform types, so the choice is unit-testable. With
 * [countdownTo] the watch face renders [text] as a countdown: "^1" marks where the time left goes.
 */
internal data class WearComplicationTexts(
    val text: String,
    val title: String?,
    val contentDescription: String,
    val countdownTo: Instant? = null,
)

/** A service counts as gone one minute after its expected departure; the next one is shown then. */
private const val DEPARTED_GRACE_MILLIS = 60_000L

/**
 * A listed departure up to this long before the snapshot's generation is one that has just left;
 * any other clock time is its next occurrence. The phone lists departures from the time of the
 * request and, after the day's last train, the next morning's first ones up to a day ahead.
 */
private const val LISTED_DEPARTURE_LOOKBACK_MILLIS = 2 * 60 * 60 * 1_000L

/**
 * The instant of an "HH:mm" departure: the first occurrence, on the day before, the same day or the
 * day after the snapshot's generation, that is not more than two hours before the generation. A
 * 00:10 train listed at 23:50 is the next day's, a 23:58 one listed at 00:05 the previous day's, a
 * 05:50 one listed at 17:30 the next morning's. Null for a time that does not parse.
 */
internal fun departureInstant(time: String, generatedAtEpochMillis: Long, zone: ZoneId = RAIL_TIME_ZONE): Instant? {
    val clock = runCatching { LocalTime.parse(time) }.getOrNull() ?: return null
    val earliest = generatedAtEpochMillis - LISTED_DEPARTURE_LOOKBACK_MILLIS
    val day = Instant.ofEpochMilli(generatedAtEpochMillis).atZone(zone).toLocalDate()
    return listOf(day.minusDays(1), day, day.plusDays(1))
        .map { date -> date.atTime(clock).atZone(zone).toInstant() }
        .firstOrNull { candidate -> candidate.toEpochMilli() >= earliest }
}

/**
 * The service a glance should show at [nowEpochMillis]: the first not yet gone; null once every listed one has left
 * (the glance then shows «Nessuna corsa da mostrare» with the data status, never a departed train as «Adesso»).
 */
internal fun upcomingService(snapshot: WearSnapshot, nowEpochMillis: Long): Pair<WearService, Instant?>? {
    val departures = snapshot.services.map { service -> service to departureInstant(service.expectedDeparture, snapshot.generatedAtEpochMillis) }
    return departures.firstOrNull { (_, departure) -> departure == null || departure.toEpochMilli() + DEPARTED_GRACE_MILLIS > nowEpochMillis }
}

/** A stretch of a snapshot's validity, [startEpochMillis] included and [endEpochMillis] excluded. */
internal data class GlanceSegment(val startEpochMillis: Long, val endEpochMillis: Long)

/**
 * The stretches after [nowEpochMillis] in which the glance shows another service: each starts one
 * minute after a departure that hands over to the next service ([upcomingService]), or to none after
 * the last one, and lasts until the next hand-over or the snapshot deadline. Empty when the glance
 * keeps its service until then.
 * The Tile and the complication timelines switch on these instants without a new request.
 */
internal fun laterGlanceSegments(snapshot: WearSnapshot, nowEpochMillis: Long): List<GlanceSegment> {
    val handOvers = snapshot.services
        .mapNotNull { service -> departureInstant(service.expectedDeparture, snapshot.generatedAtEpochMillis) }
        .map { departure -> departure.toEpochMilli() + DEPARTED_GRACE_MILLIS }
        .filter { at -> at > nowEpochMillis && at < snapshot.expiresAtEpochMillis }
        .distinct()
        .sorted()
        .filter { at -> upcomingService(snapshot, at - 1)?.first != upcomingService(snapshot, at)?.first }
    return handOvers.mapIndexed { index, start -> GlanceSegment(start, handOvers.getOrElse(index + 1) { snapshot.expiresAtEpochMillis }) }
}

/**
 * A short slot renders a few characters: the time left to the departure is the text and the
 * clock time the title, the watch face keeping the countdown current without a new request. A time
 * that does not parse falls back to the departure as text and the train number as title. Without
 * a service the app name stands in and the data status is still read by accessibility.
 */
internal fun shortTextComplicationTexts(snapshot: WearSnapshot, strings: WearStringProvider, nowEpochMillis: Long): WearComplicationTexts {
    val (service, departure) = upcomingService(snapshot, nowEpochMillis)
        ?: return WearComplicationTexts(strings.string(R.string.app_name), null, strings.string(snapshot.dataStatus()))
    if (departure == null) return WearComplicationTexts(service.expectedDeparture, service.number, service.accessibleLabel(strings))
    return WearComplicationTexts(COUNTDOWN, service.expectedDeparture, service.accessibleLabel(strings), departure)
}

internal fun longTextComplicationTexts(snapshot: WearSnapshot, strings: WearStringProvider, nowEpochMillis: Long): WearComplicationTexts {
    val title = snapshot.routeLabel.take(48)
    val (service, departure) = upcomingService(snapshot, nowEpochMillis)
        ?: return WearComplicationTexts(strings.string(snapshot.dataStatus()), title, strings.string(snapshot.dataStatus()))
    val label = strings.string(R.string.service_time_number, service.expectedDeparture, service.number)
    // Same " · " separator as service_time_number in every language.
    return if (departure == null) WearComplicationTexts(label, title, service.accessibleLabel(strings))
    else WearComplicationTexts("$label · $COUNTDOWN", title, service.accessibleLabel(strings), departure)
}

/**
 * Push-driven payload: the manifest declares no polling period, the Data Layer listener and the
 * store's expiry callback request updates, and the valid time range lets the watch face drop the
 * value at the snapshot deadline even when neither of them runs. [nowEpochMillis] picks the service
 * shown (a later timeline entry passes its own start). [tapAction] opens the app.
 */
internal fun binarioComplicationData(
    type: ComplicationType,
    snapshot: WearSnapshot?,
    strings: WearStringProvider,
    nowEpochMillis: Long,
    tapAction: PendingIntent? = null,
): ComplicationData {
    if (snapshot == null) return NoDataComplicationData()
    val validity = TimeRange.before(Instant.ofEpochMilli(snapshot.expiresAtEpochMillis))
    return when (type) {
        ComplicationType.SHORT_TEXT -> {
            val texts = shortTextComplicationTexts(snapshot, strings, nowEpochMillis)
            ShortTextComplicationData.Builder(texts.complicationText(TimeDifferenceStyle.SHORT_SINGLE_UNIT), texts.contentDescription.plain())
                .setTitle(texts.title?.plain())
                .setValidTimeRange(validity)
                .setTapAction(tapAction)
                .build()
        }
        ComplicationType.LONG_TEXT -> {
            val texts = longTextComplicationTexts(snapshot, strings, nowEpochMillis)
            LongTextComplicationData.Builder(texts.complicationText(TimeDifferenceStyle.SHORT_DUAL_UNIT), texts.contentDescription.plain())
                .setTitle(texts.title?.plain())
                .setValidTimeRange(validity)
                .setTapAction(tapAction)
                .build()
        }
        else -> NoDataComplicationData()
    }
}

/** The placeholder TimeDifferenceComplicationText replaces with the time left. */
private const val COUNTDOWN = "^1"

/** Minutes are the finest unit; past the departure the watch face shows its localized "now". */
private fun WearComplicationTexts.complicationText(style: TimeDifferenceStyle): ComplicationText =
    countdownTo?.let { departure ->
        TimeDifferenceComplicationText.Builder(style, CountDownTimeReference(departure))
            .setText(text)
            .setMinimumTimeUnit(TimeUnit.MINUTES)
            .setDisplayAsNow(true)
            .build()
    } ?: text.plain()

private fun String.plain(): PlainComplicationText = PlainComplicationText.Builder(this).build()

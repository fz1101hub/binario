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
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import it.binario.wear.R
import it.binario.wear.WearMainActivity
import it.binario.wear.WearStringProvider
import it.binario.wear.accessibleLabel
import it.binario.wear.dataStatusResId
import it.binario.wear.model.WearService
import it.binario.wear.model.WearSnapshot
import it.binario.wear.sync.WearSnapshotStore
import it.binario.wear.wearStrings
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Watch-face surface for the same local snapshot rendered by the Wear app and Tile. */
class BinarioComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        binarioComplicationData(
            request.complicationType,
            WearSnapshotStore.current(this),
            wearStrings(),
            System.currentTimeMillis(),
            openAppIntent(),
        )

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

/** The phone formats rail times as Italian clock times ("HH:mm" in Europe/Rome, RailTime.defaultZone). */
internal val RAIL_TIME_ZONE: ZoneId = ZoneId.of("Europe/Rome")

/** A service counts as gone one minute after its expected departure; the next one is shown then. */
private const val DEPARTED_GRACE_MILLIS = 60_000L

/**
 * The instant of an "HH:mm" departure: of the occurrences on the day before, the same day and the
 * day after the snapshot's generation, the nearest one. A 00:10 train listed at 23:50 is the next
 * day's, a 23:58 one listed at 00:05 the previous day's. Null for a time that does not parse.
 */
internal fun departureInstant(time: String, generatedAtEpochMillis: Long, zone: ZoneId = RAIL_TIME_ZONE): Instant? {
    val clock = runCatching { LocalTime.parse(time) }.getOrNull() ?: return null
    val generated = Instant.ofEpochMilli(generatedAtEpochMillis)
    val day = generated.atZone(zone).toLocalDate()
    return listOf(day.minusDays(1), day, day.plusDays(1))
        .map { date -> date.atTime(clock).atZone(zone).toInstant() }
        .minByOrNull { candidate -> abs(candidate.toEpochMilli() - generatedAtEpochMillis) }
}

/** The service a glance should show at [nowEpochMillis]: the first not yet gone, else the last one. */
internal fun upcomingService(snapshot: WearSnapshot, nowEpochMillis: Long): Pair<WearService, Instant?>? {
    val departures = snapshot.services.map { service -> service to departureInstant(service.expectedDeparture, snapshot.generatedAtEpochMillis) }
    return departures.firstOrNull { (_, departure) -> departure == null || departure.toEpochMilli() + DEPARTED_GRACE_MILLIS > nowEpochMillis }
        ?: departures.lastOrNull()
}

/**
 * A short slot renders a few characters: the time left to the departure is the text and the
 * clock time the title, the watch face keeping the countdown current without a new request. A time
 * that does not parse falls back to the departure as text and the train number as title. Without
 * a service the app name stands in and the data status is still read by accessibility.
 */
internal fun shortTextComplicationTexts(snapshot: WearSnapshot, strings: WearStringProvider, nowEpochMillis: Long): WearComplicationTexts {
    val (service, departure) = upcomingService(snapshot, nowEpochMillis)
        ?: return WearComplicationTexts(strings.string(R.string.app_name), null, strings.string(snapshot.dataStatusResId()))
    if (departure == null) return WearComplicationTexts(service.expectedDeparture, service.number, service.accessibleLabel(strings))
    return WearComplicationTexts(COUNTDOWN, service.expectedDeparture, service.accessibleLabel(strings), departure)
}

internal fun longTextComplicationTexts(snapshot: WearSnapshot, strings: WearStringProvider, nowEpochMillis: Long): WearComplicationTexts {
    val title = snapshot.routeLabel.take(48)
    val (service, departure) = upcomingService(snapshot, nowEpochMillis)
        ?: return WearComplicationTexts(strings.string(snapshot.dataStatusResId()), title, strings.string(snapshot.dataStatusResId()))
    val label = strings.string(R.string.service_time_number, service.expectedDeparture, service.number)
    // Same " · " separator as service_time_number in every language.
    return if (departure == null) WearComplicationTexts(label, title, service.accessibleLabel(strings))
    else WearComplicationTexts("$label · $COUNTDOWN", title, service.accessibleLabel(strings), departure)
}

/**
 * Push-driven payload: the manifest declares no polling period, the Data Layer listener and the
 * store's expiry callback request updates, and the valid time range lets the watch face drop the
 * value at the snapshot deadline even when neither of them runs. [tapAction] opens the app.
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

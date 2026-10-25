package it.binario.wear.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import it.binario.wear.R
import it.binario.wear.WearStringProvider
import it.binario.wear.accessibleLabel
import it.binario.wear.dataStatusResId
import it.binario.wear.model.WearSnapshot
import it.binario.wear.sync.WearSnapshotStore
import it.binario.wear.wearStrings
import java.time.Instant

/** Watch-face surface for the same local snapshot rendered by the Wear app and Tile. */
class BinarioComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        binarioComplicationData(request.complicationType, WearSnapshotStore.current(this), wearStrings())

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val label = PlainComplicationText.Builder(getString(R.string.app_name)).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(label, label).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(label, label).build()
            else -> NoDataComplicationData()
        }
    }
}

/** Texts resolved before they are wrapped in platform types, so the choice is unit-testable. */
internal data class WearComplicationTexts(val text: String, val title: String?, val contentDescription: String)

/**
 * A short slot renders a few characters: the expected departure is the text and the train number
 * the title, instead of one combined string the watch face would cut off. Without a service the
 * app name stands in and the data status is still read by accessibility.
 */
internal fun shortTextComplicationTexts(snapshot: WearSnapshot, strings: WearStringProvider): WearComplicationTexts {
    val service = snapshot.services.firstOrNull()
        ?: return WearComplicationTexts(strings.string(R.string.app_name), null, strings.string(snapshot.dataStatusResId()))
    return WearComplicationTexts(service.expectedDeparture, service.number, service.accessibleLabel(strings))
}

internal fun longTextComplicationTexts(snapshot: WearSnapshot, strings: WearStringProvider): WearComplicationTexts {
    val title = snapshot.routeLabel.take(48)
    val service = snapshot.services.firstOrNull()
        ?: return WearComplicationTexts(strings.string(snapshot.dataStatusResId()), title, strings.string(snapshot.dataStatusResId()))
    return WearComplicationTexts(
        strings.string(R.string.service_time_number, service.expectedDeparture, service.number),
        title,
        service.accessibleLabel(strings),
    )
}

/**
 * Push-driven payload: the manifest declares no polling period, the Data Layer listener and the
 * store's expiry callback request updates, and the valid time range lets the watch face drop the
 * value at the snapshot deadline even when neither of them runs.
 */
internal fun binarioComplicationData(type: ComplicationType, snapshot: WearSnapshot?, strings: WearStringProvider): ComplicationData {
    if (snapshot == null) return NoDataComplicationData()
    val validity = TimeRange.before(Instant.ofEpochMilli(snapshot.expiresAtEpochMillis))
    return when (type) {
        ComplicationType.SHORT_TEXT -> {
            val texts = shortTextComplicationTexts(snapshot, strings)
            ShortTextComplicationData.Builder(texts.text.plain(), texts.contentDescription.plain())
                .setTitle(texts.title?.plain())
                .setValidTimeRange(validity)
                .build()
        }
        ComplicationType.LONG_TEXT -> {
            val texts = longTextComplicationTexts(snapshot, strings)
            LongTextComplicationData.Builder(texts.text.plain(), texts.contentDescription.plain())
                .setTitle(texts.title?.plain())
                .setValidTimeRange(validity)
                .build()
        }
        else -> NoDataComplicationData()
    }
}

private fun String.plain(): PlainComplicationText = PlainComplicationText.Builder(this).build()

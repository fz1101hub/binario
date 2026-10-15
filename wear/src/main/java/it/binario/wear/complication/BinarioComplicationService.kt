package it.binario.wear.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import it.binario.wear.R
import it.binario.wear.dataStatusResId
import it.binario.wear.sync.WearSnapshotStore

/** Watch-face surface for the same local snapshot rendered by the Wear app and Tile. */
class BinarioComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val snapshot = WearSnapshotStore.current(this) ?: return NoDataComplicationData()
        val label = snapshot.services.firstOrNull()?.let { service ->
            getString(R.string.service_time_number, service.expectedDeparture, service.number)
        } ?: getString(snapshot.dataStatusResId())
        val content = PlainComplicationText.Builder(label.take(32)).build()
        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(content, content).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(content, content)
                .setTitle(PlainComplicationText.Builder(snapshot.routeLabel.take(48)).build())
                .build()
            else -> NoDataComplicationData()
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val label = PlainComplicationText.Builder(getString(R.string.app_name)).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(label, label).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(label, label).build()
            else -> NoDataComplicationData()
        }
    }
}

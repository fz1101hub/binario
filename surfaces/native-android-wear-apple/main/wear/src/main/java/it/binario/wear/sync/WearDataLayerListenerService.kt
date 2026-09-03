package it.binario.wear.sync

import android.content.ComponentName
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import it.binario.wear.complication.BinarioComplicationService
import it.binario.wear.tile.BinarioTileService

class WearDataLayerListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.filter { event -> event.dataItem.uri.path == "/binario/wear/snapshot" }.forEach { event ->
            WearSnapshotStore.updateFromDataMap(applicationContext, DataMapItem.fromDataItem(event.dataItem).dataMap)
            TileService.getUpdater(applicationContext).requestUpdate(BinarioTileService::class.java)
            ComplicationDataSourceUpdateRequester.create(applicationContext, ComponentName(applicationContext, BinarioComplicationService::class.java)).requestUpdateAll()
        }
    }
}

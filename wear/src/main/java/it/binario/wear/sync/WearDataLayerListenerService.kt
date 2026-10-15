package it.binario.wear.sync

import android.content.ComponentName
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import it.binario.wear.complication.BinarioComplicationService
import it.binario.wear.tile.BinarioTileService

class WearDataLayerListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        var surfaceChanged = false
        dataEvents.filter { event -> event.dataItem.uri.path == SNAPSHOT_PATH }.forEach { event ->
            when (event.type) {
                DataEvent.TYPE_CHANGED -> {
                    WearSnapshotStore.updateFromDataMap(applicationContext, DataMapItem.fromDataItem(event.dataItem).dataMap)
                    surfaceChanged = true
                }
                DataEvent.TYPE_DELETED -> {
                    WearSnapshotStore.clear(applicationContext)
                    surfaceChanged = true
                }
            }
        }
        if (surfaceChanged) {
            TileService.getUpdater(applicationContext).requestUpdate(BinarioTileService::class.java)
            ComplicationDataSourceUpdateRequester.create(applicationContext, ComponentName(applicationContext, BinarioComplicationService::class.java)).requestUpdateAll()
        }
    }

    private companion object {
        const val SNAPSHOT_PATH = "/binario/wear/snapshot"
    }
}

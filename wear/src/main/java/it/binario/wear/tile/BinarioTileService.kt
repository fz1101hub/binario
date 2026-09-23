package it.binario.wear.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import it.binario.wear.model.WearSnapshot
import it.binario.wear.sync.WearSnapshotStore

/** Glanceable local cache; it never initiates a railway-network request. */
class BinarioTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val snapshot = WearSnapshotStore.current(this)
        return immediate(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(30L * 60L * 1000L)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(snapshot)))
                .build()
        )
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        immediate(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    private fun layout(snapshot: WearSnapshot?): LayoutElementBuilders.LayoutElement {
        val details = snapshot?.services?.firstOrNull()
        return LayoutElementBuilders.Column.Builder()
            .addContent(text("Binario", 18f, 0xFFFFFFFF.toInt(), 1))
            .addContent(text(snapshot?.routeLabel ?: "Nessuna tratta sincronizzata", 15f, 0xFFB8F3D7.toInt(), 2))
            .addContent(text(details?.let { listOfNotNull(it.expectedDeparture, it.number, it.destination).joinToString(" · ") } ?: "Apri Binario sul telefono", 14f, 0xFFFFFFFF.toInt(), 2))
            .addContent(text(snapshot?.dataStatusLabel ?: "Snapshot non disponibile", 12f, 0xFFB3C8BE.toInt(), 2))
            .build()
    }

    private fun text(value: String, size: Float, color: Int, maxLines: Int): LayoutElementBuilders.Text =
        LayoutElementBuilders.Text.Builder()
            .setText(value.take(120))
            .setMaxLines(maxLines)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.SpProp.Builder().setValue(size).build())
                    .setColor(ColorBuilders.ColorProp.Builder(color).build())
                    .build()
            )
            .build()

    private fun <T> immediate(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "BinarioTileService.immediate"
        }

    private companion object {
        const val RESOURCES_VERSION = "binario-wear-local-v1"
    }
}

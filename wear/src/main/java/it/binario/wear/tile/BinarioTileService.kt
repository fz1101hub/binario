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
import it.binario.wear.R
import it.binario.wear.WearStringProvider
import it.binario.wear.dataStatusResId
import it.binario.wear.model.WearSnapshot
import it.binario.wear.sync.WearSnapshotStore
import it.binario.wear.wearStrings

/** Glanceable local cache; it never initiates a railway-network request. */
class BinarioTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val snapshot = WearSnapshotStore.current(this)
        val strings = wearStrings()
        return immediate(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(30L * 60L * 1000L)
                .setTileTimeline(binarioTileTimeline(snapshot) { entry -> binarioTileLayout(entry, strings) })
                .build()
        )
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        immediate(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    private fun <T> immediate(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "BinarioTileService.immediate"
        }

    private companion object {
        const val RESOURCES_VERSION = "binario-wear-local-v1"
    }
}

/**
 * The renderer owns this expiry transition even when the app process and its expiry callback are
 * not running. The unbounded entry is the fallback selected whenever the snapshot entry is no
 * longer valid; both entries come from the same [layout] so the fallback is the production one.
 */
internal fun binarioTileTimeline(
    snapshot: WearSnapshot?,
    layout: (WearSnapshot?) -> LayoutElementBuilders.LayoutElement,
): TimelineBuilders.Timeline =
    TimelineBuilders.Timeline.Builder()
        .addTimelineEntry(
            TimelineBuilders.TimelineEntry.fromLayoutElement(layout(null))
        )
        .apply {
            if (snapshot != null) {
                addTimelineEntry(
                    TimelineBuilders.TimelineEntry.Builder()
                        .setLayout(LayoutElementBuilders.Layout.fromLayoutElement(layout(snapshot)))
                        .setValidity(
                            TimelineBuilders.TimeInterval.Builder()
                                .setEndMillis(snapshot.expiresAtEpochMillis)
                                .build()
                        )
                        .build()
                )
            }
        }
        .build()

/** Production layout for both timeline entries; a null snapshot renders the "nothing synced" texts. */
internal fun binarioTileLayout(snapshot: WearSnapshot?, strings: WearStringProvider): LayoutElementBuilders.LayoutElement {
    val details = snapshot?.services?.firstOrNull()
    return LayoutElementBuilders.Column.Builder()
        .addContent(binarioTileText(strings.string(R.string.app_name), 18f, 0xFFFFFFFF.toInt(), 1))
        .addContent(binarioTileText(snapshot?.routeLabel ?: strings.string(R.string.tile_no_synced_route), 15f, 0xFFB8F3D7.toInt(), 2))
        .addContent(binarioTileText(details?.let { strings.string(R.string.tile_service_details, it.expectedDeparture, it.number, it.destination) } ?: strings.string(R.string.empty_snapshot_message), 14f, 0xFFFFFFFF.toInt(), 2))
        .addContent(binarioTileText(snapshot?.let { strings.string(it.dataStatusResId()) } ?: strings.string(R.string.tile_snapshot_unavailable), 12f, 0xFFB3C8BE.toInt(), 2))
        .build()
}

private fun binarioTileText(value: String, size: Float, color: Int, maxLines: Int): LayoutElementBuilders.Text =
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

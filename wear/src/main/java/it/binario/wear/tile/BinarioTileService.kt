package it.binario.wear.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import it.binario.wear.BinarioWearPalette
import it.binario.wear.R
import it.binario.wear.WearMainActivity
import it.binario.wear.WearStringProvider
import it.binario.wear.complication.laterGlanceSegments
import it.binario.wear.complication.upcomingService
import it.binario.wear.dataStatusResId
import it.binario.wear.model.WearService
import it.binario.wear.model.WearSnapshot
import it.binario.wear.sync.WearSnapshotStore
import it.binario.wear.wearStrings

/** Glanceable local cache; it never initiates a railway-network request. */
class BinarioTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val snapshot = WearSnapshotStore.current(this)
        val device = requestParams.deviceConfiguration
        val strings = wearStrings()
        return immediate(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(30L * 60L * 1000L)
                .setTileTimeline(binarioTileTimeline(snapshot, System.currentTimeMillis()) { content -> binarioTileLayout(content, device, strings, packageName) })
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

/** What one Tile timeline entry shows. */
internal sealed interface WearTileContent {
    /** Nothing received from the phone, or sharing revoked. */
    data object NotSynced : WearTileContent

    /** [snapshot] with [service], the one the glance shows during the entry (null without services). */
    data class Current(val snapshot: WearSnapshot, val service: WearService?) : WearTileContent
}

/**
 * The renderer owns the transitions even when the app process and its callbacks are not running.
 * The unbounded entry is the fallback selected whenever no snapshot entry is valid, so after the
 * deadline. The snapshot entries cover the time up to the deadline: the first one shows the service
 * of the glance at [nowEpochMillis], each later one the next service from one minute after the
 * previous one leaves ([laterGlanceSegments]). Every entry comes from the same [layout].
 */
internal fun binarioTileTimeline(
    snapshot: WearSnapshot?,
    nowEpochMillis: Long,
    layout: (WearTileContent) -> LayoutElementBuilders.LayoutElement,
): TimelineBuilders.Timeline =
    TimelineBuilders.Timeline.Builder()
        .addTimelineEntry(
            TimelineBuilders.TimelineEntry.fromLayoutElement(layout(WearTileContent.NotSynced))
        )
        .apply {
            if (snapshot != null) {
                val later = laterGlanceSegments(snapshot, nowEpochMillis)
                addTimelineEntry(
                    tileEntry(
                        layout(WearTileContent.Current(snapshot, upcomingService(snapshot, nowEpochMillis)?.first)),
                        TimelineBuilders.TimeInterval.Builder()
                            .setEndMillis(later.firstOrNull()?.startEpochMillis ?: snapshot.expiresAtEpochMillis)
                            .build(),
                    )
                )
                later.forEach { segment ->
                    addTimelineEntry(
                        tileEntry(
                            layout(WearTileContent.Current(snapshot, upcomingService(snapshot, segment.startEpochMillis)?.first)),
                            TimelineBuilders.TimeInterval.Builder()
                                .setStartMillis(segment.startEpochMillis)
                                .setEndMillis(segment.endEpochMillis)
                                .build(),
                        )
                    )
                }
            }
        }
        .build()

private fun tileEntry(layout: LayoutElementBuilders.LayoutElement, validity: TimelineBuilders.TimeInterval): TimelineBuilders.TimelineEntry =
    TimelineBuilders.TimelineEntry.Builder()
        .setLayout(LayoutElementBuilders.Layout.fromLayoutElement(layout))
        .setValidity(validity)
        .build()

/**
 * Production layout for every timeline entry; [WearTileContent.NotSynced] renders the "nothing
 * synced" texts. The column is centred and inset from the bezel: a round display clips the corners
 * of a full-width block, so the margin is a share of the screen width that keeps every line inside
 * the visible disc, while a square display keeps a smaller one.
 */
internal fun binarioTileLayout(
    content: WearTileContent,
    device: DeviceParametersBuilders.DeviceParameters,
    strings: WearStringProvider,
    packageName: String,
): LayoutElementBuilders.LayoutElement {
    val lines = when (content) {
        WearTileContent.NotSynced -> listOf(
            strings.string(R.string.tile_no_synced_route),
            strings.string(R.string.empty_snapshot_message),
            strings.string(R.string.tile_snapshot_unavailable),
        )
        is WearTileContent.Current -> listOf(
            content.snapshot.routeLabel,
            content.service?.let { strings.string(R.string.tile_service_details, it.expectedDeparture, it.number, it.destination) }
                ?: strings.string(R.string.empty_snapshot_message),
            strings.string(content.snapshot.dataStatusResId()),
        )
    }
    val inset = DimensionBuilders.dp(device.screenWidthDp * tileInsetFraction(device))
    val column = LayoutElementBuilders.Column.Builder()
        .setWidth(DimensionBuilders.expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .addContent(binarioTileText(strings.string(R.string.app_name), 18f, BinarioWearPalette.ON_BACKGROUND.toInt(), 1))
        .addContent(binarioTileText(lines[0], 15f, BinarioWearPalette.PRIMARY.toInt(), 2))
        .addContent(binarioTileText(lines[1], 14f, BinarioWearPalette.ON_BACKGROUND.toInt(), 2))
        .addContent(binarioTileText(lines[2], 12f, BinarioWearPalette.ON_SURFACE_VARIANT.toInt(), 2))
        .build()
    return LayoutElementBuilders.Box.Builder()
        .setWidth(DimensionBuilders.expand())
        .setHeight(DimensionBuilders.expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setPadding(ModifiersBuilders.Padding.Builder().setStart(inset).setEnd(inset).build())
                .setClickable(openAppClickable(packageName))
                .build()
        )
        .addContent(column)
        .build()
}

/** A tap anywhere on the Tile opens the watch app, with the services, the status and the refresh. */
internal fun openAppClickable(packageName: String): ModifiersBuilders.Clickable =
    ModifiersBuilders.Clickable.Builder()
        .setId(OPEN_APP_CLICKABLE_ID)
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(packageName)
                        .setClassName(WearMainActivity::class.java.name)
                        .build()
                )
                .build()
        )
        .build()

internal const val OPEN_APP_CLICKABLE_ID = "open_binario"

/** Share of the screen width kept free on each side; the disc of a round screen needs the larger one. */
internal fun tileInsetFraction(device: DeviceParametersBuilders.DeviceParameters): Float =
    if (device.screenShape == DeviceParametersBuilders.SCREEN_SHAPE_ROUND) ROUND_TILE_INSET else RECT_TILE_INSET

private const val ROUND_TILE_INSET = 0.14f
private const val RECT_TILE_INSET = 0.06f

private fun binarioTileText(value: String, size: Float, color: Int, maxLines: Int): LayoutElementBuilders.Text =
    LayoutElementBuilders.Text.Builder()
        .setText(value.take(120))
        .setMaxLines(maxLines)
        .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder()
                .setSize(DimensionBuilders.SpProp.Builder().setValue(size).build())
                .setColor(ColorBuilders.ColorProp.Builder(color).build())
                .build()
        )
        .build()

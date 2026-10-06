package im.binario.companion.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures

class BinarioTileService : TileService() {
  override fun onTileRequest(requestParams: RequestBuilders.TileRequest) = Futures.immediateFuture(
    TileBuilders.Tile.Builder()
      .setResourcesVersion("1")
      .setFreshnessIntervalMillis(BinarioWearStore.tileFreshnessMillis)
      .setTileTimeline(timelineFor(BinarioWearStore.read(this)))
      .build()
  )
  // The renderer switches to the unavailable layout at expiry by itself, even
  // when this process (and any in-process timer) is no longer running.
  private fun timelineFor(state: BinarioWearState): TimelineBuilders.Timeline {
    val timeline = TimelineBuilders.Timeline.Builder()
      .addTimelineEntry(TimelineBuilders.TimelineEntry.fromLayoutElement(textFor(BinarioWearStore.unavailable(this))))
    val expiresAt = state.expiresAtUtcMs
    if (state.canComplicate && expiresAt != null) {
      timeline.addTimelineEntry(
        TimelineBuilders.TimelineEntry.Builder()
          .setLayout(LayoutElementBuilders.Layout.fromLayoutElement(textFor(state)))
          .setValidity(TimelineBuilders.TimeInterval.Builder().setEndMillis(expiresAt).build())
          .build()
      )
    }
    return timeline.build()
  }
  override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) = Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())
  private fun textFor(state: BinarioWearState): LayoutElementBuilders.Column {
    val column = LayoutElementBuilders.Column.Builder()
      .setModifiers(ModifiersBuilders.Modifiers.Builder().setSemantics(
        ModifiersBuilders.Semantics.Builder().setContentDescription(getString(R.string.binario_tile_heading, state.title, state.status)).setHeading(true).build()
      ).build())
      .addContent(LayoutElementBuilders.Text.Builder().setText("${state.title}\n${state.status}").build())
      .addContent(LayoutElementBuilders.Text.Builder().setText(getString(R.string.binario_microphone))
        .setModifiers(ModifiersBuilders.Modifiers.Builder().setSemantics(
          ModifiersBuilders.Semantics.Builder().setContentDescription(getString(R.string.voice_request_label)).setStateDescription(TypeBuilders.StringProp.Builder(getString(R.string.binario_voice_state)).build()).build()
        ).setClickable(
          ModifiersBuilders.Clickable.Builder().setId("binario-voice-request").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
              ActionBuilders.AndroidActivity.Builder().setPackageName("im.binario.companion").setClassName(BinarioVoiceRequestActivity::class.java.name).build()
            ).build()
          ).build()
        ).build())
        .build())
    if (state.plannedChecklistRemaining > 0) column.addContent(
      LayoutElementBuilders.Text.Builder().setText(getString(R.string.binario_checklist, state.plannedChecklistRemaining))
        .setModifiers(ModifiersBuilders.Modifiers.Builder().setSemantics(
          ModifiersBuilders.Semantics.Builder().setContentDescription(getString(R.string.checklist_request_label)).setStateDescription(TypeBuilders.StringProp.Builder(getString(R.string.binario_checklist_state, state.plannedChecklistRemaining)).build()).build()
        ).setClickable(
          ModifiersBuilders.Clickable.Builder().setId("binario-open-checklist").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
              ActionBuilders.AndroidActivity.Builder().setPackageName("im.binario.companion").setClassName(BinarioChecklistRequestActivity::class.java.name).build()
            ).build()
          ).build()
        ).build())
        .build()
    )
    return column.build()
  }
}

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
      .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(textFor(BinarioWearStore.read(this))))
      .build()
  )
  override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) = Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())
  private fun textFor(state: BinarioWearState): LayoutElementBuilders.Column {
    val column = LayoutElementBuilders.Column.Builder()
      .setModifiers(ModifiersBuilders.Modifiers.Builder().setSemantics(
        ModifiersBuilders.Semantics.Builder().setContentDescription("Binario. ${state.title}. ${state.status}").setHeading(true).build()
      ).build())
      .addContent(LayoutElementBuilders.Text.Builder().setText("${state.title}\n${state.status}").build())
      .addContent(LayoutElementBuilders.Text.Builder().setText("Microfono · Parla a Binario")
        .setModifiers(ModifiersBuilders.Modifiers.Builder().setSemantics(
          ModifiersBuilders.Semantics.Builder().setContentDescription("Parla a Binario").setStateDescription(TypeBuilders.StringProp.Builder("Apre la richiesta vocale sullo smartwatch").build()).build()
        ).setClickable(
          ModifiersBuilders.Clickable.Builder().setId("binario-voice-request").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
              ActionBuilders.AndroidActivity.Builder().setPackageName("im.binario.companion.wear").setClassName(BinarioVoiceRequestActivity::class.java.name).build()
            ).build()
          ).build()
        ).build())
        .build())
    if (state.plannedChecklistRemaining > 0) column.addContent(
      LayoutElementBuilders.Text.Builder().setText("Checklist · ${state.plannedChecklistRemaining} voci sul telefono")
        .setModifiers(ModifiersBuilders.Modifiers.Builder().setSemantics(
          ModifiersBuilders.Semantics.Builder().setContentDescription("Apri checklist sul telefono").setStateDescription(TypeBuilders.StringProp.Builder("${state.plannedChecklistRemaining} voci da preparare").build()).build()
        ).setClickable(
          ModifiersBuilders.Clickable.Builder().setId("binario-open-checklist").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
              ActionBuilders.AndroidActivity.Builder().setPackageName("im.binario.companion.wear").setClassName(BinarioChecklistRequestActivity::class.java.name).build()
            ).build()
          ).build()
        ).build())
        .build()
    )
    return column.build()
  }
}

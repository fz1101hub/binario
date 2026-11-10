package it.binario.wear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import it.binario.wear.ResourceNameStrings
import it.binario.wear.WearMainActivity
import it.binario.wear.model.WearConfidence
import it.binario.wear.model.WearDataNature
import it.binario.wear.model.WearDirection
import it.binario.wear.model.WearFreshness
import it.binario.wear.model.WearOperationalState
import it.binario.wear.model.WearProvenance
import it.binario.wear.model.WearProvenanceProducer
import it.binario.wear.model.WearService
import it.binario.wear.model.WearSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinarioTileTimelineTest {
    @Test
    fun `snapshot entry expires at its payload deadline and unavailable fallback remains valid`() {
        val expiresAt = 1_893_485_700_000L

        val entries = binarioTileTimeline(snapshot(expiresAt), expiresAt - 60_000L, ::productionLayout).timelineEntries

        assertEquals(2, entries.size)
        assertNull(entries[0].validity)
        val validity = requireNotNull(entries[1].validity)
        assertEquals(0L, validity.startMillis)
        assertEquals(expiresAt, validity.endMillis)
    }

    @Test
    fun `fallback used after expiry is the same unavailable layout returned without a snapshot`() {
        val withSnapshot = binarioTileTimeline(snapshot(1_893_485_700_000L), 1_893_485_640_000L, ::productionLayout).timelineEntries
        val unavailableOnly = binarioTileTimeline(null, 1_893_485_640_000L, ::productionLayout).timelineEntries

        assertEquals(1, unavailableOnly.size)
        assertNull(unavailableOnly.single().validity)
        assertArrayEquals(
            requireNotNull(unavailableOnly.single().layout).toByteArray(),
            requireNotNull(withSnapshot.first().layout).toByteArray(),
        )
    }

    @Test
    fun `production layout without a snapshot uses the localized unavailable texts`() {
        assertEquals(
            listOf("app_name", "tile_no_synced_route", "empty_snapshot_message", "tile_snapshot_unavailable"),
            texts(productionLayout(null)),
        )
    }

    @Test
    fun `production layout renders route first service and data status from the snapshot`() {
        assertEquals(
            listOf(
                "app_name",
                "Roma Termini → Firenze S. M. Novella",
                "tile_service_details(08:15, RV 2345, Firenze S. M. Novella)",
                "status_received_from_phone",
            ),
            texts(productionLayout(snapshot(1_893_485_700_000L, services = listOf(service)))),
        )
    }

    @Test
    fun `the Tile moves to the next service a minute after the first one leaves, without a new request`() {
        // 08:00 in Rome; services at 08:15 and 08:42; the snapshot is valid until 08:30.
        val now = 1_893_481_200_000L
        val expiresAt = now + 30 * 60_000L
        val next = service.copy(number = "RV 2347", scheduledDeparture = "08:42", expectedDeparture = "08:42", platform = "5")
        val snapshot = snapshot(expiresAt, services = listOf(service, next)).copy(generatedAtEpochMillis = now, ttlMillis = expiresAt - now)
        val handOver = now + 16 * 60_000L // 08:16

        val entries = binarioTileTimeline(snapshot, now, ::contentLayout).timelineEntries

        assertEquals(3, entries.size)
        assertNull(entries[0].validity)
        assertEquals(listOf(0L to handOver, handOver to expiresAt), entries.drop(1).map { requireNotNull(it.validity).let { v -> v.startMillis to v.endMillis } })
        assertEquals(
            listOf("not-synced", "RV 2345", "RV 2347"),
            entries.map { texts(requireNotNull(requireNotNull(it.layout).root)).single() },
        )

        // Rendered after the first train left, the Tile shows the second one at once and has nothing later to switch to.
        val late = binarioTileTimeline(snapshot, handOver + 60_000L, ::contentLayout).timelineEntries
        assertEquals(listOf("not-synced", "RV 2347"), late.map { texts(requireNotNull(requireNotNull(it.layout).root)).single() })
        assertEquals(expiresAt, requireNotNull(late[1].validity).endMillis)
    }

    @Test
    fun `round screens inset the centred column more than square ones`() {
        val round = binarioTileLayout(WearTileContent.NotSynced, device(DeviceParametersBuilders.SCREEN_SHAPE_ROUND), ResourceNameStrings, PACKAGE) as LayoutElementBuilders.Box
        val square = binarioTileLayout(WearTileContent.NotSynced, device(DeviceParametersBuilders.SCREEN_SHAPE_RECT), ResourceNameStrings, PACKAGE) as LayoutElementBuilders.Box

        assertTrue(tileInsetFraction(device(DeviceParametersBuilders.SCREEN_SHAPE_ROUND)) > tileInsetFraction(device(DeviceParametersBuilders.SCREEN_SHAPE_RECT)))
        assertEquals(192f * tileInsetFraction(device(DeviceParametersBuilders.SCREEN_SHAPE_ROUND)), sidePadding(round), 0.001f)
        assertEquals(192f * tileInsetFraction(device(DeviceParametersBuilders.SCREEN_SHAPE_RECT)), sidePadding(square), 0.001f)
        assertEquals(LayoutElementBuilders.VERTICAL_ALIGN_CENTER, requireNotNull(round.verticalAlignment).value)
        column(round).contents.forEach { line ->
            assertEquals(LayoutElementBuilders.TEXT_ALIGN_CENTER, requireNotNull((line as LayoutElementBuilders.Text).multilineAlignment).value)
        }
    }

    @Test
    fun `a tap on the Tile opens the watch app with or without a snapshot`() {
        for (layout in listOf(productionLayout(null), productionLayout(snapshot(1_893_485_700_000L, services = listOf(service))))) {
            val clickable = requireNotNull(requireNotNull((layout as LayoutElementBuilders.Box).modifiers).clickable)
            val activity = requireNotNull((clickable.onClick as ActionBuilders.LaunchAction).androidActivity)

            assertEquals(OPEN_APP_CLICKABLE_ID, clickable.id)
            assertEquals(PACKAGE, activity.packageName)
            assertEquals(WearMainActivity::class.java.name, activity.className)
        }
    }

    private fun productionLayout(snapshot: WearSnapshot?): LayoutElementBuilders.LayoutElement =
        productionLayout(snapshot?.let { WearTileContent.Current(it, it.services.firstOrNull()) } ?: WearTileContent.NotSynced)

    private fun productionLayout(content: WearTileContent): LayoutElementBuilders.LayoutElement =
        binarioTileLayout(content, device(DeviceParametersBuilders.SCREEN_SHAPE_ROUND), ResourceNameStrings, PACKAGE)

    /** One text per entry: which content the timeline asked for. */
    private fun contentLayout(content: WearTileContent): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(
                                when (content) {
                                    WearTileContent.NotSynced -> "not-synced"
                                    is WearTileContent.Current -> content.service?.number ?: "no-service"
                                }
                            )
                            .build()
                    )
                    .build()
            )
            .build()

    private fun device(shape: Int): DeviceParametersBuilders.DeviceParameters =
        DeviceParametersBuilders.DeviceParameters.Builder().setScreenWidthDp(192).setScreenHeightDp(192).setScreenShape(shape).build()

    private fun column(box: LayoutElementBuilders.Box): LayoutElementBuilders.Column =
        box.contents.single() as LayoutElementBuilders.Column

    private fun sidePadding(box: LayoutElementBuilders.Box): Float {
        val padding = requireNotNull(requireNotNull(box.modifiers).padding)
        assertEquals(requireNotNull(padding.start).value, requireNotNull(padding.end).value, 0.001f)
        return requireNotNull(padding.start).value
    }

    private fun texts(element: LayoutElementBuilders.LayoutElement): List<String> =
        column(element as LayoutElementBuilders.Box).contents.map { requireNotNull((it as LayoutElementBuilders.Text).text).value }

    private val service = WearService(
        number = "RV 2345",
        operator = "Trenitalia",
        category = "Regionale Veloce",
        destination = "Firenze S. M. Novella",
        scheduledDeparture = "08:05",
        expectedDeparture = "08:15",
        status = "In orario",
        platform = "4",
        delayMinutes = 10,
    )

    private fun snapshot(expiresAt: Long, services: List<WearService> = emptyList()): WearSnapshot = WearSnapshot(
        routeId = "route-id",
        routeLabel = "Roma Termini → Firenze S. M. Novella",
        direction = WearDirection.OUTBOUND,
        provenance = WearProvenance(
            producer = WearProvenanceProducer.ANDROID_APP,
            dataNature = WearDataNature.OPERATIONAL,
            sourceLabel = "Fonte",
            observedAtEpochMillis = expiresAt - 60_000L,
        ),
        operationalState = WearOperationalState.OPERATIONAL,
        freshness = WearFreshness.FRESH,
        confidence = WearConfidence.OPERATOR_PRIMARY,
        generatedAtEpochMillis = expiresAt - 60_000L,
        expiresAtEpochMillis = expiresAt,
        ttlMillis = 60_000L,
        notice = "",
        services = services,
    )

    private companion object {
        const val PACKAGE = "it.binario.app"
    }
}

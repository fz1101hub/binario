package it.binario.wear

import androidx.wear.protolayout.LayoutElementBuilders
import it.binario.wear.complication.GlanceSegment
import it.binario.wear.complication.laterGlanceSegments
import it.binario.wear.complication.shortTextComplicationTexts
import it.binario.wear.model.WearConfidence
import it.binario.wear.model.WearDataNature
import it.binario.wear.model.WearDirection
import it.binario.wear.model.WearFreshness
import it.binario.wear.model.WearOperationalState
import it.binario.wear.model.WearProvenance
import it.binario.wear.model.WearProvenanceProducer
import it.binario.wear.model.WearService
import it.binario.wear.model.WearSnapshot
import it.binario.wear.tile.WearTileContent
import it.binario.wear.tile.binarioTileTimeline
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Past the live window of an operational snapshot the watch shows its programmed times, until the deadline. */
class WearProgrammedFallbackTest {
    /** 2030-01-01 08:00 in Rome: the snapshot is generated now and, for a watch that falls back, valid one hour. */
    private val now = 1_893_481_200_000L
    private val minute = 60_000L
    private val liveUntil = now + 15 * minute

    @Test
    fun `live values are shown for fifteen minutes from generation, then the programmed times`() {
        val snapshot = snapshot()

        assertEquals(liveUntil, snapshot.liveUntilEpochMillis)
        assertSame(snapshot, snapshot.shownAt(liveUntil - 1, PROGRAMMED))
        val fallback = snapshot.shownAt(liveUntil, PROGRAMMED)

        assertEquals(WearOperationalState.PROGRAMMED, fallback.operationalState)
        assertEquals(WearDataNature.PROGRAMMED, fallback.provenance.dataNature)
        assertEquals(WearConfidence.STATIC_ONLY, fallback.confidence)
        assertEquals(WearText(R.string.status_programmed), fallback.dataStatus())
        assertEquals("", fallback.notice)
        assertEquals("", fallback.provider)
        assertEquals(snapshot.expiresAtEpochMillis, fallback.expiresAtEpochMillis)
        // The 07:40 train, 40 minutes late at 08:20, had left by its programmed time: only the others, in programmed order.
        assertEquals(listOf("R 2", "R 3"), fallback.services.map { it.number })
        val first = fallback.services.first()
        assertEquals("08:25", first.expectedDeparture)
        assertEquals("08:25", first.scheduledDeparture)
        assertEquals(0, first.delayMinutes)
        assertNull(first.platform)
        assertEquals(PROGRAMMED, first.status)
        val spoken = first.accessibleLabel(ResourceNameStrings)
        assertTrue(spoken, spoken.endsWith(PROGRAMMED))
        assertTrue(spoken, !spoken.contains("accessibility_platform"))
    }

    @Test
    fun `a snapshot without live values is shown as it is`() {
        val programmed = snapshot().copy(operationalState = WearOperationalState.PROGRAMMED)

        assertNull(programmed.liveUntilEpochMillis)
        assertSame(programmed, programmed.shownAt(now + 50 * minute, PROGRAMMED))
        assertSame(programmed, programmed.programmedFallback(PROGRAMMED))
    }

    @Test
    fun `the glances switch to the programmed times when the live values expire, without a new request`() {
        val snapshot = snapshot()

        assertEquals(
            listOf(
                GlanceSegment(liveUntil, now + 26 * minute),
                GlanceSegment(now + 26 * minute, now + 31 * minute),
                GlanceSegment(now + 31 * minute, now + 60 * minute),
            ),
            laterGlanceSegments(snapshot, now),
        )
        val entries = binarioTileTimeline(snapshot, now, ::contentLayout, PROGRAMMED).timelineEntries
        assertEquals(
            listOf("expired", "OPERATIONAL R 1", "PROGRAMMED R 2", "PROGRAMMED R 3", "PROGRAMMED -"),
            entries.map { text(requireNotNull(requireNotNull(it.layout).root)) },
        )
        assertEquals(liveUntil, requireNotNull(entries[1].validity).endMillis)
        // The complication counts down to the programmed time once the live values expire.
        val atFallback = snapshot.shownAt(liveUntil, PROGRAMMED)
        assertEquals("08:25", shortTextComplicationTexts(atFallback, ResourceNameStrings, liveUntil).title)
        assertEquals("08:20", shortTextComplicationTexts(snapshot, ResourceNameStrings, now).title)
    }

    @Test
    fun `the app list drops the trains that have left, live or programmed, and moves on at each change`() {
        val snapshot = snapshot()

        assertEquals(listOf("R 1", "R 2", "R 3"), snapshot.listedAt(now, PROGRAMMED).services.map { it.number })
        assertEquals(liveUntil, snapshot.nextListChangeAt(now))
        // After the live window: the programmed R 2 (08:25) and R 3 (08:30), each gone a minute after it leaves.
        assertEquals(listOf("R 2", "R 3"), snapshot.listedAt(liveUntil, PROGRAMMED).services.map { it.number })
        assertEquals(now + 26 * minute, snapshot.nextListChangeAt(liveUntil))
        assertEquals(listOf("R 3"), snapshot.listedAt(now + 26 * minute, PROGRAMMED).services.map { it.number })
        assertEquals(now + 31 * minute, snapshot.nextListChangeAt(now + 26 * minute))
        assertEquals(emptyList<String>(), snapshot.listedAt(now + 31 * minute, PROGRAMMED).services.map { it.number })
        assertNull(snapshot.nextListChangeAt(now + 31 * minute))
        // Programmed data lasting until its last train: the first one goes a minute after it leaves.
        val programmed = snapshot().copy(operationalState = WearOperationalState.PROGRAMMED, services = snapshot().services.drop(1).reversed())
        assertEquals(now + 31 * minute, programmed.nextListChangeAt(now))
        assertEquals(listOf("R 2"), programmed.listedAt(now + 31 * minute, PROGRAMMED).services.map { it.number })
    }

    @Test
    fun `a phone that sends the fifteen-minute window alone never shows the fallback`() {
        val earlier = snapshot().copy(expiresAtEpochMillis = liveUntil, ttlMillis = liveUntil - now)

        // R 1 leaves after the deadline: nothing changes before it.
        assertEquals(emptyList<GlanceSegment>(), laterGlanceSegments(earlier, now))
        val entries = binarioTileTimeline(earlier, now, ::contentLayout, PROGRAMMED).timelineEntries
        assertEquals(listOf("expired", "OPERATIONAL R 1"), entries.map { text(requireNotNull(requireNotNull(it.layout).root)) })
    }

    @Test
    fun `the watch advertises the capability the phones check before sending the programmed lifetime`() {
        val wear = File(sequenceOf(File("src/main/res"), File("wear/src/main/res")).first(File::isDirectory), "values/wear.xml").readText()
        assertTrue(wear.contains("<item>binario_wear_programmed_fallback_v1</item>"))
    }

    private fun contentLayout(content: WearTileContent): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(
                when (content) {
                    WearTileContent.NotSynced -> "not-synced"
                    WearTileContent.Expired -> "expired"
                    is WearTileContent.Current -> "${content.snapshot.operationalState} ${content.service?.number ?: "-"}"
                }
            )
            .build()

    private fun text(element: LayoutElementBuilders.LayoutElement): String =
        requireNotNull((element as LayoutElementBuilders.Text).text).value

    private fun service(number: String, scheduled: String, expected: String, delay: Int, platform: String?) = WearService(
        number = number,
        operator = "Trenitalia",
        category = "Regionale",
        destination = "Firenze S. M. Novella",
        scheduledDeparture = scheduled,
        expectedDeparture = expected,
        status = if (delay > 0) "In ritardo" else "In orario",
        platform = platform,
        delayMinutes = delay,
    )

    /** Operational, generated at 08:00, valid until 09:00 as a phone sends it to a watch that falls back. */
    private fun snapshot(): WearSnapshot = WearSnapshot(
        routeId = "active",
        routeLabel = "Roma Termini → Firenze S. M. Novella",
        direction = WearDirection.OUTBOUND,
        provenance = WearProvenance(WearProvenanceProducer.ANDROID_APP, WearDataNature.OPERATIONAL, "Fonte primaria dell’operatore", now - minute),
        operationalState = WearOperationalState.OPERATIONAL,
        freshness = WearFreshness.FRESH,
        confidence = WearConfidence.OPERATOR_PRIMARY,
        generatedAtEpochMillis = now,
        expiresAtEpochMillis = now + 60 * minute,
        ttlMillis = 60 * minute,
        notice = "Soppresso: R 9 delle 08:05",
        services = listOf(
            service("R 1", "07:40", "08:20", 40, "2"),
            service("R 2", "08:25", "08:31", 6, "3"),
            service("R 3", "08:30", "08:30", 0, "4"),
        ),
    )

    private companion object {
        const val PROGRAMMED = "Programmato"
    }
}

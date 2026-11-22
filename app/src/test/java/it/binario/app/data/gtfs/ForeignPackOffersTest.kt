package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.ForeignPackFixtures.archive
import it.binario.app.data.gtfs.ForeignPackFixtures.oebb
import it.binario.app.data.gtfs.ForeignPackFixtures.packJson
import it.binario.app.data.gtfs.ForeignPackFixtures.pins
import it.binario.app.data.gtfs.ForeignPackFixtures.swiss
import it.binario.app.data.gtfs.ForeignPackSettingsRow.State
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The process-wide offers: declines, the one download a tap starts, Settings' rows (design §3.3–3.5, D2, Q4). */
class ForeignPackOffersTest {
    private val milano = "curated:S01700"
    private val bern = "curated:NC000041"
    private val zurich = "curated:NC001381"
    private val brig = "curated:NC000063"
    private val wien = "curated:NC001632"

    private val swissBytes = archive("ch-otd-gtfs")
    private val austrianBytes = archive("oebb-gtfs")
    private val allPinned = pins(
        packJson("ch-otd-gtfs", "CH", swissBytes, served = listOf("NC000041", "NC000063", "NC001381"), zone = listOf("NC000063")),
        packJson("oebb-gtfs", "AT", austrianBytes, served = listOf("S02001", "NC001632"), zone = listOf("S02001")),
        packJson("sncf-gtfs", "FR", null, served = listOf("S04501", "NC000556"), zone = listOf("S04501"))
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val today = LocalDate.of(2026, 11, 30)
    private val installed = mutableSetOf<SourceId>()
    private val statuses = mutableMapOf<SourceId, ForeignPackStatus>()
    private val removed = mutableListOf<SourceId>()

    @After fun stop() = scope.cancel()

    private fun offers(
        pins: ForeignPackPins = allPinned,
        download: suspend (SourceId, (Long, Long) -> Unit) -> ForeignPackDownloadResult = { _, _ -> ForeignPackDownloadResult.Installed }
    ) = ForeignPackOffers(
        pins, MutableStateFlow(0L), { it in installed }, { entry, _ -> statuses[entry.sourceId] ?: ForeignPackStatus.BORDER_ONLY },
        download, { removed += it }, scope, { today }
    )

    private fun ForeignPackOffers.awaitState(predicate: (ForeignPackDownloadState) -> Boolean): ForeignPackDownloadState =
        runBlocking { withTimeout(10_000) { downloadState.first(predicate) } }

    @Test
    fun `a declined dialog stays away for the rest of the process but the banner does not`() {
        val offers = offers()
        val dialog = offers.afterSearch(milano, bern, found = false)!!
        assertEquals(listOf(swiss), dialog.packs.map { it.sourceId })

        offers.decline(dialog)

        assertNull(offers.afterSearch(milano, bern, found = false))
        assertEquals(setOf(swiss), offers.declined.value)
        assertEquals(ForeignPackOffer.Style.BANNER, offers.afterSearch(bern, zurich, found = true)!!.style)
        // Another country is still asked.
        assertEquals(listOf(oebb), offers.afterSearch(milano, wien, found = false)!!.packs.map { it.sourceId })
        // The board line is not a dialog: it stays.
        assertEquals(listOf(swiss), offers.forStation(bern).map { it.sourceId })
    }

    @Test
    fun `only searches touching a border pack depend on the files`() {
        val offers = offers()
        assertTrue(offers.touches(milano, bern))
        assertTrue(offers.touches(brig, milano))
        assertFalse(offers.touches(milano, "curated:S08409"))
        // SNCF is bundled whole (no pin): its stations never depend on a download.
        assertFalse(offers.touches(milano, "curated:NC000556"))
        assertFalse(offers(ForeignPackPins.EMPTY).touches(milano, bern))
    }

    @Test
    fun `a tap downloads with progress and an install leaves no state`() {
        val gate = CompletableDeferred<Unit>()
        val offers = offers(download = { id, progress ->
            progress(10L, swissBytes.size.toLong())
            gate.await()
            assertEquals(swiss, id)
            ForeignPackDownloadResult.Installed
        })

        offers.start(swiss)
        val running = offers.awaitState { it is ForeignPackDownloadState.Running && it.written == 10L } as ForeignPackDownloadState.Running
        assertEquals(swissBytes.size.toLong(), running.total)
        assertTrue(running.fraction > 0f && running.fraction < 1f)
        gate.complete(Unit)

        assertEquals(ForeignPackDownloadState.Idle, offers.awaitState { it == ForeignPackDownloadState.Idle })
    }

    @Test
    fun `a second tap while a download runs starts nothing`() {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val offers = offers(download = { _, _ ->
            calls.incrementAndGet()
            gate.await()
            ForeignPackDownloadResult.Installed
        })

        offers.start(swiss)
        offers.start(oebb)
        offers.awaitState { it is ForeignPackDownloadState.Running }
        assertEquals(swiss, (offers.downloadState.value as ForeignPackDownloadState.Running).sourceId)
        gate.complete(Unit)
        offers.awaitState { it == ForeignPackDownloadState.Idle }
        assertEquals(1, calls.get())
    }

    @Test
    fun `cancel stops the download and keeps nothing`() {
        val cancelled = CompletableDeferred<Unit>()
        val offers = offers(download = { _, _ ->
            try {
                CompletableDeferred<Unit>().await()
                ForeignPackDownloadResult.Installed
            } finally {
                cancelled.complete(Unit)
            }
        })

        offers.start(swiss)
        offers.awaitState { it is ForeignPackDownloadState.Running }
        offers.cancel()

        assertEquals(ForeignPackDownloadState.Idle, offers.downloadState.value)
        runBlocking { withTimeout(10_000) { cancelled.await() } }
        assertEquals(ForeignPackDownloadState.Idle, offers.downloadState.value)
    }

    @Test
    fun `each failure has its text and only a new tap tries again`() {
        val results = ArrayDeque(listOf(
            ForeignPackDownloadResult.NetworkError, ForeignPackDownloadResult.Refused, ForeignPackDownloadResult.Invalid
        ))
        val calls = AtomicInteger()
        val offers = offers(download = { _, _ ->
            calls.incrementAndGet()
            results.removeFirst()
        })
        for (expected in listOf(ForeignPackDownloadError.NETWORK, ForeignPackDownloadError.REFUSED, ForeignPackDownloadError.INVALID)) {
            val before = calls.get()
            offers.start(swiss)
            val failed = offers.awaitState { it is ForeignPackDownloadState.Failed && calls.get() == before + 1 } as ForeignPackDownloadState.Failed
            assertEquals(expected, failed.error)
            assertEquals(swiss, failed.sourceId)
        }
        assertEquals(3, calls.get())
        offers.clearError()
        assertEquals(ForeignPackDownloadState.Idle, offers.downloadState.value)
        assertEquals(ForeignPackDownloadState.Idle, ForeignPackOffers.outcome(swiss, ForeignPackDownloadResult.Busy))
        assertEquals(ForeignPackDownloadState.Idle, ForeignPackOffers.outcome(swiss, ForeignPackDownloadResult.NotOffered))
    }

    @Test
    fun `Settings lists Switzerland, Austria and France with their state`() {
        statuses[swiss] = ForeignPackStatus.INSTALLED
        statuses[oebb] = ForeignPackStatus.UPDATE_AVAILABLE

        val rows = offers().settingsRows()

        assertEquals(listOf("CH", "AT", "FR"), rows.map { it.country })
        val (ch, at, fr) = rows
        assertEquals(State.INSTALLED, ch.state)
        assertEquals(swissBytes.size.toLong(), ch.sizeBytes)
        assertEquals(LocalDate.of(2026, 12, 12), ch.lastServiceDate)
        assertTrue(ch.canDelete)
        assertFalse(ch.canDownload)
        assertEquals(State.UPDATE_AVAILABLE, at.state)
        assertTrue(at.canDownload)
        assertFalse(at.canDelete)
        // D2: no pin, bundled whole: a static status and no button.
        assertEquals(ForeignPackSettingsRow(country = "FR", sourceId = null, state = State.INCLUDED, sizeBytes = 0L, lastServiceDate = null), fr)
        assertFalse(fr.canDownload || fr.canDelete)
    }

    @Test
    fun `Settings rows for border only, expired, the sample pins and no pins at all`() {
        statuses[oebb] = ForeignPackStatus.EXPIRED
        val rows = offers().settingsRows()
        assertEquals(State.BORDER_ONLY, rows[0].state)
        assertTrue(rows[0].canDownload)
        assertEquals(State.EXPIRED, rows[1].state)
        assertFalse(rows[1].canDownload || rows[1].canDelete)

        // The repository's sample (today's D2 state): only Austria can be downloaded.
        statuses.clear()
        val sample = offers(ForeignPackPins.parse(ForeignPackFixtures.sampleJson())).settingsRows()
        assertEquals(listOf(State.INCLUDED, State.BORDER_ONLY, State.INCLUDED), sample.map { it.state })
        assertEquals(listOf(null, oebb, null), sample.map { it.sourceId })

        // No valid pins file: every pack is read as bundled.
        assertEquals(List(3) { State.INCLUDED }, offers(ForeignPackPins.EMPTY).settingsRows().map { it.state })

        // A border subset without a download pin: border only, no button.
        val unpinnedSubset = pins(packJson("oebb-gtfs", "AT", null, served = listOf("NC001632"), zone = emptyList(), borderOnly = true))
        val row = offers(unpinnedSubset).settingsRows()[1]
        assertEquals(State.BORDER_ONLY, row.state)
        assertNull(row.sourceId)
        assertFalse(row.canDownload)
    }

    @Test
    fun `delete removes the pack through the store`() {
        offers().delete(swiss)
        assertEquals(listOf(swiss), removed)
    }

    @Test
    fun `the expired and declined packs of a dialog are both suppressed by Non ora`() {
        val expired = pins(packJson("ch-otd-gtfs", "CH", swissBytes, served = listOf("NC000041"), zone = emptyList(), lastServiceDate = LocalDate.of(2026, 11, 1)))
        val offers = offers(expired)
        val dialog = offers.afterSearch(milano, bern, found = false)!!
        assertTrue(dialog.packs.isEmpty())
        assertEquals(listOf(swiss), dialog.expired.map { it.sourceId })

        offers.decline(dialog)

        assertNull(offers.afterSearch(milano, bern, found = false))
    }
}

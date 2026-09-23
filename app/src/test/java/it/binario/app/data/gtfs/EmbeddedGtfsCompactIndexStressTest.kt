package it.binario.app.data.gtfs

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.core.SourceId
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressione C1/PR-DATA-2: dimensione e cardinalità corrispondono al
 * stop_times AMT (1.355.184 righe). Il task Gradle dedicato lo esegue con
 * -Xmx256m; non va convertito in fixture ByteArray, che falserebbe la misura.
 */
class EmbeddedGtfsCompactIndexStressTest {
    private val sourceId = SourceId("lombardia-trenord-gtfs")
    private val pack = EmbeddedGtfsPack(sourceId, "gtfs/large.zip")
    private val route = SavedRoute(
        id = "large",
        name = "Origine – Destinazione",
        outwardOrigin = Station("gtfs:lombardia-trenord-gtfs:ORIGIN", "Origine", "Test"),
        outwardDestination = Station("gtfs:lombardia-trenord-gtfs:DESTINATION", "Destinazione", "Test")
    )

    @Test
    fun `indicizza 1_3 milioni di stop times con heap bounded senza mappe per riga`() = runBlocking {
        val archive = File.createTempFile("gtfs-compact-", ".zip")
        try {
            writeRealisticLargeFeed(archive, stopTimeRows = 1_355_184)
            val adapter = EmbeddedGtfsRouteAdapter(
                openAsset = { FileInputStream(archive) },
                packs = listOf(pack),
                clock = { 1_000L }
            )

            val result = adapter.loadRoute(
                route,
                Direction.OUTBOUND,
                JourneyQuery(LocalDate.of(2026, 9, 4), LocalTime.of(8, 0))
            )

            assertTrue(result is DataResult.Success)
            assertEquals("LARGE", (result as DataResult.Success).snapshot.services.single().number)
            val stats = adapter.compactIndexStats().single()
            assertEquals(1_355_184, stats.stopTimeRowsRead)
            assertEquals(2, stats.indexedStopTimeRows)
            assertEquals(2L * Int.SIZE_BYTES * 6L, stats.stopTimeColumnBytes)
            // Le 1,3M righe sono consumate ma non trattenute: solo le due del trip dichiarato.
            assertTrue("colonne stop_times oltre il budget", stats.stopTimeColumnBytes < 32L * 1024L * 1024L)
        } finally {
            archive.delete()
        }
    }

    @Test(expected = GtfsCompactIndexLimitException::class)
    fun `rifiuta una riga csv prima di allocare una stringa illimitata`() {
        val archive = File.createTempFile("gtfs-oversized-line-", ".zip")
        try {
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("stops.txt"))
                zip.write("stop_id,stop_name\nS,".toByteArray())
                zip.write("x".repeat(32_769).toByteArray())
                zip.write("\n".toByteArray())
                zip.closeEntry()
            }
            BoundedGtfsZipScanner(GtfsCompactIndexLimits()).scan(FileInputStream(archive), setOf("stops.txt")) { _, rows ->
                rows.forEachRow(1) { }
            }
        } finally {
            archive.delete()
        }
    }

    private fun writeRealisticLargeFeed(archive: File, stopTimeRows: Int) {
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            entry(zip, "agency.txt") { write("agency_id,agency_name\nT,Test Rail\n".toByteArray()) }
            entry(zip, "stops.txt") { write("stop_id,stop_name\nORIGIN,Origine\nDESTINATION,Destinazione\n".toByteArray()) }
            entry(zip, "routes.txt") { write("route_id,agency_id,route_short_name,route_long_name,route_type\nR,T,RE,Test route,2\n".toByteArray()) }
            entry(zip, "trips.txt") { write("route_id,service_id,trip_id,trip_short_name\nR,W,TARGET,LARGE\n".toByteArray()) }
            entry(zip, "calendar.txt") {
                write("service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n".toByteArray())
                write("W,1,1,1,1,1,1,1,20260101,20261231\n".toByteArray())
            }
            zip.putNextEntry(ZipEntry("stop_times.txt"))
            zip.write("trip_id,arrival_time,departure_time,stop_id,stop_sequence\n".toByteArray())
            zip.write("TARGET,08:05:00,08:05:00,ORIGIN,1\n".toByteArray())
            repeat(stopTimeRows - 2) { index ->
                zip.write("NOISE$index,08:10:00,08:10:00,ORIGIN,1\n".toByteArray())
            }
            zip.write("TARGET,08:20:00,08:20:00,DESTINATION,2\n".toByteArray())
            zip.closeEntry()
        }
    }

    private fun entry(zip: ZipOutputStream, name: String, body: ZipOutputStream.() -> Unit) {
        zip.putNextEntry(ZipEntry(name))
        zip.body()
        zip.closeEntry()
    }

    private fun FileInputStream(file: File) = file.inputStream()
}

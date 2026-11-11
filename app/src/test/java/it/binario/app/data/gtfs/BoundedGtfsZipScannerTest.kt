package it.binario.app.data.gtfs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundedGtfsZipScannerTest {
    private val rows = 20_000

    private fun archive(): ByteArray {
        val table = buildString {
            append("stop_id,stop_name\n")
            repeat(rows) { append("S").append(it).append(",Fermata ").append(it).append('\n') }
        }.toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            // The wanted table, then a larger one that is only drained.
            zip.putNextEntry(ZipEntry("stops.txt")); zip.write(table); zip.closeEntry()
            zip.putNextEntry(ZipEntry("stop_times.txt")); repeat(4) { zip.write(table) }; zip.closeEntry()
            zip.putNextEntry(ZipEntry("trips.txt")); zip.write(table); zip.closeEntry()
        }
        return output.toByteArray()
    }

    @Test fun `a scan without a checkpoint reads every row`() {
        var read = 0
        BoundedGtfsZipScanner(GtfsCompactIndexLimits()).scan(ByteArrayInputStream(archive()), setOf("stops.txt", "trips.txt")) { _, table ->
            read += table.forEachRow(rows) { }
        }
        assertEquals(2 * rows, read)
    }

    @Test fun `a cancelled scan stops inside a table, not at the end of the pack`() {
        var active = true
        var read = 0
        var checks = 0
        val scanner = BoundedGtfsZipScanner(GtfsCompactIndexLimits()) {
            checks += 1
            if (!active) throw CancellationException("cancelled")
        }
        try {
            scanner.scan(ByteArrayInputStream(archive()), setOf("stops.txt", "trips.txt")) { _, table ->
                table.forEachRow(rows) {
                    read += 1
                    if (read == 100) active = false
                }
            }
            fail("The scan went on after the cancellation")
        } catch (_: CancellationException) {
            // Stopped at the first checkpoint after row 100, well before the end of the first table.
            assertEquals(BoundedGtfsZipScanner.CHECKPOINT_ROWS - 1, read)
        }
        assertTrue(checks >= 2)
    }

    @Test fun `a cancelled scan stops while it drains a table nobody asked for`() {
        var active = true
        var tables = 0
        val scanner = BoundedGtfsZipScanner(GtfsCompactIndexLimits()) { if (!active) throw CancellationException("cancelled") }
        try {
            scanner.scan(ByteArrayInputStream(archive()), setOf("stops.txt", "trips.txt")) { _, table ->
                tables += 1
                table.forEachRow(rows) { }
                active = false
            }
            fail("The scan went on after the cancellation")
        } catch (_: CancellationException) {
            // stop_times.txt and trips.txt were never handed out.
            assertEquals(1, tables)
        }
    }
}

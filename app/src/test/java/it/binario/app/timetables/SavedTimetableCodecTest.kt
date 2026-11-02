package it.binario.app.timetables

import it.binario.app.LocalBackupResult
import it.binario.app.LocalDataBackup
import it.binario.app.LocalDataBackupCodec
import it.binario.app.MobilityPreferences
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import java.time.LocalDate
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedTimetableCodecTest {
    private val direct = TimetableRow(
        departure = "17:40", arrival = "21:00", durationMinutes = 200, trains = "FRECCIAROSSA 9520", changes = 0,
        platform = "7", scheduledPlatform = "5", source = "ViaggiaTreno", fetchedAtEpochMillis = 1_762_100_000_000L, scheduledOnly = false
    )
    private val withChange = TimetableRow(
        departure = "23:10", arrival = "06:05", arrivalDayOffset = 1, durationMinutes = 415, trains = "REGIONALE 2153 › RE 24617",
        changes = 1, transfers = listOf("Cambio a Bologna Centrale · Attesa di 12 minuti"), source = "Orario programmato offline (open data GTFS)"
    )
    private val timetable = SavedTimetable(
        id = "t1", name = "Casa – lavoro", origin = "Roma Termini", destination = "Milano Centrale",
        serviceDate = LocalDate.of(2026, 11, 2), fromTime = "17:00", savedAtEpochMillis = 1_762_101_000_000L,
        rows = listOf(direct, withChange), returnRows = listOf(direct.copy(departure = "08:10", arrival = "11:30"))
    )

    @Test fun `a saved list keeps every field it was saved with`() {
        val read = SavedTimetableCodec.decode(SavedTimetableCodec.encode(listOf(timetable)))
        assertEquals(listOf(timetable), read)
        assertEquals(3, read.single().solutionCount)
    }

    @Test fun `unreadable entries are dropped one by one and limits hold`() {
        val raw = JSONArray(SavedTimetableCodec.encode(listOf(timetable)))
            .put("not an object")
            .put(org.json.JSONObject().put("id", "broken").put("origin", "A").put("destination", "B").put("date", "2026-13-40").put("from", "17:00"))
            .put(org.json.JSONObject().put("id", "empty").put("origin", "A").put("destination", "B").put("date", "2026-11-02").put("from", "17:00").put("rows", JSONArray()))
        assertEquals(listOf("t1"), SavedTimetableCodec.fromJson(raw).map { it.id })
        assertEquals(emptyList<SavedTimetable>(), SavedTimetableCodec.decode("{not json"))
        assertEquals(emptyList<SavedTimetable>(), SavedTimetableCodec.decode(null))

        val many = (1..40).map { timetable.copy(id = "t$it", rows = List(50) { direct }) }
        val bounded = SavedTimetableCodec.decode(SavedTimetableCodec.encode(many))
        assertEquals(SavedTimetableCodec.MAX_TIMETABLES, bounded.size)
        assertTrue(bounded.all { it.rows.size == SavedTimetableCodec.MAX_ROWS })
    }

    @Test fun `saving puts the newest first, replaces the same id and renaming ignores blank names`() {
        val older = timetable.copy(id = "old", name = "Vecchio")
        val saved = SavedTimetableCodec.withSaved(listOf(older, timetable), timetable.copy(name = "Aggiornato"))
        assertEquals(listOf("t1", "old"), saved.map { it.id })
        assertEquals("Aggiornato", saved.first().name)
        assertEquals("Ufficio", SavedTimetableCodec.renamed(saved, "t1", "  Ufficio ").first().name)
        assertEquals("Aggiornato", SavedTimetableCodec.renamed(saved, "t1", "   ").first().name)
        val long = "x".repeat(200)
        assertEquals(SavedTimetableCodec.MAX_NAME_LENGTH, SavedTimetableCodec.renamed(saved, "t1", long).first().name.length)
    }

    @Test fun `the local backup carries the saved timetables and an older backup keeps the device ones`() {
        val roma = Station("roma", "Roma Termini", "Roma")
        val milano = Station("milano", "Milano Centrale", "Milano")
        val backup = LocalDataBackup(
            routes = listOf(SavedRoute("route", "Roma – Milano", roma, milano)), favouriteStationIds = emptySet(),
            monitoredServices = emptyList(), stationMonitors = emptyList(), commuterAlerts = emptyList(),
            mobilityPreferences = MobilityPreferences(), savedTimetables = listOf(timetable)
        )
        val restored = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(backup), listOf(roma, milano))
        assertTrue(restored is LocalBackupResult.Success)
        assertEquals(listOf(timetable), (restored as LocalBackupResult.Success).backup.savedTimetables)

        // A file written without the field (an earlier version) leaves the device's saved timetables alone.
        val older = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(backup.copy(savedTimetables = null)), listOf(roma, milano))
        assertNull((older as LocalBackupResult.Success).backup.savedTimetables)
    }
}

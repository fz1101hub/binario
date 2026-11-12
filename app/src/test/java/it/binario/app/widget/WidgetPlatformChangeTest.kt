package it.binario.app.widget

import it.binario.app.TestStringCatalogue
import it.binario.app.data.DataConfidence
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.core.RunStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The route, station and trains widgets keep the timetable platform next to the actual one and say the change. */
class WidgetPlatformChangeTest {
    private val catalogue = TestStringCatalogue.of()
    private val widget = italianWidgetStrings().copy(platformChanged = catalogue.getValue("platform_changed_accessible"))
    private val now = Instant.parse("2026-11-02T08:00:00Z").toEpochMilli()

    @Test fun `route widget rows keep the scheduled platform across a save and read older rows without it`() {
        val service = WidgetService(
            number = "2153", operator = RailOperator.TRENITALIA_REGIONALE, category = TrainCategory.REGIONALE,
            destination = "Milano Centrale", expectedDeparture = "09:05", status = ServiceStatus.ON_TIME, delayMinutes = 0,
            platform = "7", operationalStatusVerified = true, departureEpochMillis = now, scheduledPlatform = "5"
        )
        val line = WidgetStateStore.encodeService(service)
        val read = WidgetStateStore.decodeService(line, DataConfidence.OPERATOR_PRIMARY, now)
        assertEquals("5", read?.scheduledPlatform)
        assertEquals("7", read?.platform)
        assertTrue(read!!.accessibleLabel(widget).contains("Binario cambiato: 7, previsto 5"))
        // A row of the previous version (15 fields) has no timetable platform: no change is claimed.
        val legacy = line.split(",").dropLast(1).joinToString(",")
        val older = WidgetStateStore.decodeService(legacy, DataConfidence.OPERATOR_PRIMARY, now)
        assertNull(older?.scheduledPlatform)
        assertTrue(older!!.accessibleLabel(widget).contains("Binario 7"))
    }

    @Test fun `a stale route row shows and reads the timetable platform and no old expected arrival`() {
        val service = WidgetService(
            number = "2153", operator = RailOperator.TRENITALIA_REGIONALE, category = TrainCategory.REGIONALE,
            destination = "Milano Centrale", expectedDeparture = "09:12", status = ServiceStatus.DELAYED, delayMinutes = 7,
            platform = "7", operationalStatusVerified = true, scheduledDeparture = "09:05", departureEpochMillis = now,
            arrivalLabel = "10:02", scheduledPlatform = "5"
        )
        assertEquals("7" to "5", service.shownPlatform(live = true))
        assertEquals("10:02", service.shownArrival(live = true))
        // Old data: the row is the timetable, «in viaggio» without the old arrival and the platform of the timetable.
        assertEquals("5" to null, service.shownPlatform(live = false))
        assertNull(service.shownArrival(live = false))
        val spoken = service.accessibleLabel(widget, live = false)
        assertTrue(spoken, spoken.contains("Binario 5"))
        assertFalse(spoken, spoken.contains("Binario cambiato"))
        // Without a change the shown platform stays.
        assertEquals("7" to null, service.copy(scheduledPlatform = null).shownPlatform(live = false))
    }

    @Test fun `station widget shows BIN 7 era 5 in the attention colour flag and reads it`() {
        val strings = StationWidgetStrings(
            widget = widget, statusLate = "RIT.", statusChanged = "VAR.", platformShort = catalogue.getValue("widget_platform_short"),
            trainNumber = "Treno %1\$s", departureTo = "Per %1\$s", scheduledTime = "Orario programmato %1\$s",
            livePrediction = "Previsto %1\$s", liveStatus = "Stato %1\$s", plannedOnly = "Programmato",
            runStatusLabels = RunStatus.entries.associateWith { it.name }, titleAccessibility = "%1\$s %2\$s %3\$s %4\$s",
            platformChangedShort = catalogue.getValue("widget_platform_changed")
        )
        val departure = StationWidgetDeparture(now, null, "2153", "trenitalia", "Milano Centrale", "7", null, scheduledPlatform = "5")
        val row = StationWidgetPresenter.row(departure, Freshness.FRESH, strings)
        assertEquals("BIN 7\nera 5", row.platform)
        assertTrue(row.platformChanged)
        assertTrue(row.accessibility.contains("Binario cambiato: 7, previsto 5"))
        val same = StationWidgetPresenter.row(departure.copy(scheduledPlatform = "7"), Freshness.FRESH, strings)
        assertEquals("BIN 7", same.platform)
        assertFalse(same.platformChanged)

        val stored = StationWidgetCodec.decodeDepartures(StationWidgetCodec.encodeDepartures(listOf(departure))).single()
        assertEquals("5", stored.scheduledPlatform)
    }

    @Test fun `trains widget status keeps the scheduled platform and reads records of the previous version`() {
        val status = FavouriteTrainStatus(number = "9520", retrievedAt = now, platform = "12", scheduledPlatform = "9", providerName = "ViaggiaTreno")
        val encoded = FavouriteTrainStatusCodec.encode(status)
        assertEquals("9", FavouriteTrainStatusCodec.decode(encoded)?.scheduledPlatform)
        val legacy = encoded.split(",").dropLast(1).joinToString(",")
        val older = FavouriteTrainStatusCodec.decode(legacy)
        assertEquals("12", older?.platform)
        assertNull(older?.scheduledPlatform)
    }
}

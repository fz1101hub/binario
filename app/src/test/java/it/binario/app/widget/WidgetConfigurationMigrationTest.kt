package it.binario.app.widget

import it.binario.app.data.DataConfidence
import it.binario.app.data.Direction
import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.core.RunStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Configurations and rows saved by earlier versions keep working, with the previous behaviour, until edited. */
class WidgetConfigurationMigrationTest {
    private fun field(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun record(vararg values: String) = values.joinToString("|") { field(it) }

    @Test
    fun `route configurations of every earlier format read with the legacy settings`() {
        val three = WidgetConfigurationCodec.decode(record("ROUTE", "roma-torino", "Roma → Torino"))
        val four = WidgetConfigurationCodec.decode(record("ROUTE", "roma-torino", "Roma → Torino", "Pendolare"))
        val twelve = WidgetConfigurationCodec.decode(
            record("ROUTE", "route:roma:torino", "Roma → Torino", "", "roma", "Roma Termini", "Roma", "Lazio", "torino", "Torino Porta Nuova", "Torino", "")
        )
        val twentyTwo = WidgetConfigurationCodec.decode(
            record(
                "ROUTE", "route:roma:torino", "Roma → Torino", "Casa",
                "roma", "Roma Termini", "Roma", "Lazio", "S08409", "830008409", "", "41.9", "12.5",
                "torino", "Torino Porta Nuova", "Torino", "", "", "", "", "", ""
            )
        )

        listOf(three, four, twelve, twentyTwo).forEach { configuration ->
            assertNotNull(configuration)
            assertEquals(RouteWidgetSettings.LEGACY, configuration!!.settings)
            assertEquals(AutoRefreshMode.ALWAYS, configuration.settings.autoRefresh.mode)
            assertEquals(RefreshInterval.MINUTES_15, configuration.settings.autoRefresh.interval)
            assertEquals(RouteTimeRange.NEXT, configuration.settings.timeRange)
        }
        assertEquals("Pendolare", four!!.displayName)
        assertEquals("S08409", twentyTwo!!.routeOrigin!!.railwayCode)
        assertEquals(Station("torino", "Torino Porta Nuova", "Torino"), twelve!!.routeDestination)
    }

    @Test
    fun `a saved configuration keeps its stations and settings`() {
        val configuration = WidgetDisplayConfiguration(
            targetType = WidgetTargetType.ROUTE,
            targetId = "route:roma:napoli",
            targetLabel = "Roma Termini → Napoli Centrale",
            displayName = "Lavoro",
            routeOrigin = Station("roma", "Roma Termini", "Roma", railwayCode = "S08409", aliases = listOf("Termini"), latitude = 41.9, longitude = 12.5),
            routeDestination = Station("napoli", "Napoli Centrale", "Napoli"),
            settings = RouteWidgetSettings(
                autoRefresh = AutoRefreshSettings(AutoRefreshMode.WINDOWS, RefreshInterval.MINUTES_60),
                returnFrom = LocalTime.of(14, 30),
                excludedTypes = setOf(TrainTypeGroup.INTERNATIONAL),
                untilArrival = true,
                timeRange = RouteTimeRange.NEXT_6H
            )
        )

        assertEquals(configuration, WidgetConfigurationCodec.decode(WidgetConfigurationCodec.encode(configuration)))
        assertNull(WidgetConfigurationCodec.decode("not|a|configuration|at|all"))
    }

    @Test
    fun `station configurations of the earlier format read with the legacy settings`() {
        val station = Station("S08409", "Roma Termini", "Roma", railwayCode = "S08409")

        val legacy = StationWidgetCodec.decodeConfiguration(StationWidgetCodec.encodeStation(station))

        assertEquals(StationWidgetConfiguration(station, StationWidgetSettings.LEGACY), legacy)
        assertEquals("Roma Termini", legacy!!.title)
        val edited = StationWidgetConfiguration(
            station,
            StationWidgetSettings(displayName = "Stazione di casa", excludedTypes = setOf(TrainTypeGroup.HIGH_SPEED), showNearestButton = true)
        )
        assertEquals(edited, StationWidgetCodec.decodeConfiguration(StationWidgetCodec.encodeConfiguration(edited)))
        assertEquals("Stazione di casa", edited.title)
        assertNull(StationWidgetCodec.decodeConfiguration("garbage"))
    }

    @Test
    fun `departures saved before the type groups get theirs from the operator`() {
        val legacyRecord = listOf("1000", "", "9921", "italo", "Napoli Centrale", "", "ON_TIME").joinToString(",") { field(it) }
        val unknownRecord = listOf("2000", "", "2153", "trenitalia", "Orte", "", "").joinToString(",") { field(it) }

        val decoded = StationWidgetCodec.decodeDepartures("$legacyRecord;$unknownRecord")

        assertEquals(listOf(TrainTypeGroup.ITALO, null), decoded.map { it.typeGroup })
        assertEquals(RunStatus.ON_TIME, decoded.first().status)
    }

    @Test
    fun `route rows of earlier versions get a departure instant from the retrieval time`() {
        val zone = ZoneId.of("Europe/Rome")
        val retrieved = ZonedDateTime.of(LocalDate.of(2026, 11, 2), LocalTime.of(23, 30), zone).toInstant().toEpochMilli()
        val nine = listOf("2153", "TRENITALIA_REGIONALE", "Regionale", "Orte", "00:15", "DELAYED", "5", "4", "true").joinToString(",") { field(it) }
        val eight = listOf("9921", "ITALO", "AV_ITALO", "Napoli", "23:45", "ON_TIME", "0", "").joinToString(",") { field(it) }

        val first = WidgetStateStore.decodeService(nine, DataConfidence.OPERATOR_PRIMARY, retrieved)!!
        val second = WidgetStateStore.decodeService(eight, DataConfidence.STATIC_ONLY, retrieved)!!

        assertEquals(TrainCategory.REGIONALE, first.category)
        assertEquals(ZonedDateTime.of(LocalDate.of(2026, 11, 3), LocalTime.of(0, 15), zone).toInstant().toEpochMilli(), first.departureEpochMillis)
        assertEquals(TrainTypeGroup.REGIONAL_SUBURBAN, first.typeGroup)
        assertEquals("00:15", first.scheduledDeparture)
        assertEquals(TrainTypeGroup.ITALO, second.typeGroup)
        assertEquals(false, second.operationalStatusVerified)
        assertEquals(RailOperator.ITALO, second.operator)
        assertEquals(ServiceStatus.ON_TIME, second.status)
        assertNull(WidgetStateStore.decodeService("broken", DataConfidence.OPERATOR_PRIMARY, retrieved))
    }

    @Test
    fun `a direction swap setting does not touch widgets that have none`() {
        assertEquals(Direction.RETURN, WidgetDirectionSchedule.effectiveDirection(RouteWidgetSettings.LEGACY.returnFrom, Direction.RETURN, null, 0L, ZonedDateTime.now()))
    }
}

package it.binario.app.ui.departures

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.widget.TrainTypeGroup
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartenzeTrainTypesTest {
    private fun service(number: String, category: TrainCategory, operator: RailOperator, scheduledBus: Boolean = false) = TrainService(
        id = number, number = number, operator = operator, trainCategory = category, origin = "Roma Termini", destination = "Napoli Centrale",
        scheduledDeparture = "10:00", expectedDeparture = "10:00", platform = null, delayMinutes = 0, status = ServiceStatus.ON_TIME,
        stops = emptyList(), scheduledBus = scheduledBus
    )

    private fun entry(number: String, kind: ServiceKind, operator: String? = null, line: String? = null) = StationBoardEntry(
        runId = TrainRunId(number), scheduledTime = ZonedDateTime.of(2026, 11, 2, 10, 0, 0, 0, ZoneId.of("Europe/Rome")),
        destinationOrOrigin = StationId("napoli"), publicNumber = number, operatorId = operator?.let(::OperatorId), lineLabel = line, serviceKind = kind
    )

    @Test fun `route board rows map onto the groups of the widget settings`() {
        assertEquals(TrainTypeGroup.HIGH_SPEED, PartenzeTrainTypes.groupOf(service("9520", TrainCategory.FRECCE, RailOperator.TRENITALIA_AV)))
        assertEquals(TrainTypeGroup.ITALO, PartenzeTrainTypes.groupOf(service("8901", TrainCategory.AV_ITALO, RailOperator.ITALO)))
        assertEquals(TrainTypeGroup.INTERCITY_NIGHT, PartenzeTrainTypes.groupOf(service("701", TrainCategory.INTERCITY, RailOperator.TRENITALIA_LONG_DISTANCE)))
        assertEquals(TrainTypeGroup.REGIONAL_SUBURBAN, PartenzeTrainTypes.groupOf(service("2153", TrainCategory.REGIONALE, RailOperator.TRENITALIA_REGIONALE)))
        assertEquals(TrainTypeGroup.INTERNATIONAL, PartenzeTrainTypes.groupOf(service("EC 51", TrainCategory.OTHER, RailOperator.SBB)))
        assertEquals(TrainTypeGroup.REPLACEMENT_BUS, PartenzeTrainTypes.groupOf(service("4417", TrainCategory.OTHER, RailOperator.TRENITALIA_REGIONALE, scheduledBus = true)))
        assertNull(PartenzeTrainTypes.groupOf(service("1", TrainCategory.OTHER, RailOperator.OTHER)))
    }

    @Test fun `station board rows use kind operator and line, a scheduled coach is a bus`() {
        assertEquals(TrainTypeGroup.REPLACEMENT_BUS, PartenzeTrainTypes.groupOf(entry("1", ServiceKind.BUS)))
        assertEquals(TrainTypeGroup.ITALO, PartenzeTrainTypes.groupOf(entry("2", ServiceKind.UNKNOWN, operator = "italo")))
        assertEquals(TrainTypeGroup.HIGH_SPEED, PartenzeTrainTypes.groupOf(entry("3", ServiceKind.UNKNOWN, line = "FR")))
        assertEquals(TrainTypeGroup.REGIONAL_SUBURBAN, PartenzeTrainTypes.groupOf(entry("4", ServiceKind.UNKNOWN, line = "RE 80")))
        assertNull(PartenzeTrainTypes.groupOf(entry("5", ServiceKind.UNKNOWN)))
    }

    @Test fun `excluding a type hides only trains known to be of that type`() {
        val rows = listOf(
            service("9520", TrainCategory.FRECCE, RailOperator.TRENITALIA_AV),
            service("2153", TrainCategory.REGIONALE, RailOperator.TRENITALIA_REGIONALE),
            service("1", TrainCategory.OTHER, RailOperator.OTHER)
        )
        val excludeEverything = TrainTypeGroup.entries.toSet()
        // The unknown train survives even when every group is excluded.
        assertEquals(listOf("1"), PartenzeTrainTypes.visible(rows, excludeEverything, PartenzeTrainTypes::groupOf).map { it.number })
        assertEquals(listOf("2153", "1"), PartenzeTrainTypes.visible(rows, setOf(TrainTypeGroup.HIGH_SPEED), PartenzeTrainTypes::groupOf).map { it.number })
        assertTrue(PartenzeTrainTypes.visible(rows, emptySet(), PartenzeTrainTypes::groupOf) === rows)
        val entries = listOf(entry("a", ServiceKind.BUS), entry("b", ServiceKind.UNKNOWN))
        assertEquals(listOf("b"), PartenzeTrainTypes.visible(entries, setOf(TrainTypeGroup.REPLACEMENT_BUS), PartenzeTrainTypes::groupOf).map { it.publicNumber })
    }

    @Test fun `the stored exclusion round-trips and ignores names it does not know`() {
        val excluded = setOf(TrainTypeGroup.ITALO, TrainTypeGroup.REPLACEMENT_BUS)
        assertEquals(excluded, PartenzeTrainTypes.decode(PartenzeTrainTypes.encode(excluded)))
        assertEquals(setOf(TrainTypeGroup.ITALO), PartenzeTrainTypes.decode("ITALO, MONORAIL,,"))
        assertEquals(emptySet<TrainTypeGroup>(), PartenzeTrainTypes.decode(null))
        assertEquals("", PartenzeTrainTypes.encode(emptySet()))
    }
}

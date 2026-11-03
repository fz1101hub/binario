package it.binario.app.journeys

import it.binario.app.data.TrainCategory
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Freshness
import it.binario.app.data.core.JourneyId
import it.binario.app.data.core.JourneyLeg
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationId
import it.binario.app.data.journey.EmbeddedGtfsJourneyProvider
import it.binario.app.data.repository.JourneySearchResult
import it.binario.app.data.repository.JourneySearchStatus
import it.binario.app.data.repository.SourcedJourneyOption
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class JourneyResultsPresenterTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val now = Instant.parse("2026-11-02T08:00:00Z")

    private fun leg(number: String, line: String?, from: String, to: String, departs: String, arrives: String,
        mode: LegMode = LegMode.TRAIN, kind: ServiceKind = ServiceKind.REGIONAL, operator: String? = "Trenord", nextDay: Boolean = false) = JourneyLeg(
        mode = mode,
        origin = StationId("curated:$from"),
        destination = StationId("curated:$to"),
        scheduledDeparture = LocalDateTime.parse("2026-11-10T$departs").atZone(zone),
        scheduledArrival = LocalDateTime.parse("2026-11-10T$arrives").plusDays(if (nextDay) 1 else 0).atZone(zone),
        publicNumber = number,
        lineLabel = line,
        originLabel = from,
        destinationLabel = to,
        serviceKind = kind,
        operatorName = operator
    )

    private fun option(id: String, vararg legs: JourneyLeg, source: SourceId = EmbeddedGtfsJourneyProvider.SOURCE_ID) = SourcedJourneyOption(
        option = JourneyOption(
            id = JourneyId(id), origin = legs.first().origin, destination = legs.last().destination,
            departure = legs.first().scheduledDeparture, arrival = legs.last().scheduledArrival, legs = legs.toList(),
            snapshot = SnapshotMeta(1, now, freshness = Freshness.AGING, completeness = Completeness.STATIC_ONLY, contributingSources = emptySet())
        ),
        sourceId = source,
        sourceName = "Fonte",
        scheduledOnly = true
    )

    // 08:09 → 09:54 with a change at Greco Pirelli (1 change, 105 min)
    private val oneChange = option("a",
        leg("10894", "R38", "Lodi", "Milano Greco Pirelli", "08:09", "08:50"),
        leg("24828", "S8", "Milano Greco Pirelli", "Lecco", "08:59", "09:54", kind = ServiceKind.SUBURBAN))
    // 08:30 → 09:40 direct (70 min)
    private val direct = option("b", leg("2818", "RE8", "Lodi", "Lecco", "08:30", "09:40"))
    // 08:14 → 09:30 with two changes (76 min)
    private val twoChanges = option("c",
        leg("2158", "RE11", "Lodi", "Milano Lambrate", "08:14", "08:36"),
        leg("10894", "R38", "Milano Lambrate", "Milano Greco Pirelli", "08:44", "08:50"),
        leg("24830", "S8", "Milano Greco Pirelli", "Lecco", "08:55", "09:30", kind = ServiceKind.SUBURBAN))

    private fun result(vararg options: SourcedJourneyOption, status: JourneySearchStatus = JourneySearchStatus.FOUND, incomplete: Boolean = false) =
        JourneySearchResult(status, options.toList(), listOf(EmbeddedGtfsJourneyProvider.SOURCE_ID), incomplete, now)

    @Test fun `a row carries times, duration, changes, legs and the transfer with its wait`() {
        val row = JourneyResultsPresenter.row(oneChange, zone)
        assertEquals("08:09", row.departureTime)
        assertEquals("09:54", row.arrivalTime)
        assertEquals(0, row.arrivalDayOffset)
        assertEquals(105, row.durationMinutes)
        assertEquals(1, row.durationHours)
        assertEquals(45, row.durationRemainderMinutes)
        assertEquals(1, row.changes)
        assertEquals(JourneyChangesLabel.Changes(1), row.changesLabel)
        assertEquals(listOf("R38" to "10894", "S8" to "24828"), row.legs.map { it.lineLabel to it.number })
        assertEquals(listOf("Lodi" to "Milano Greco Pirelli", "Milano Greco Pirelli" to "Lecco"), row.legs.map { it.fromLabel to it.toLabel })
        assertEquals(listOf("08:09" to "08:50", "08:59" to "09:54"), row.legs.map { it.departureTime to it.arrivalTime })
        assertEquals(TrainCategory.TRENORD_REGIO, row.legs.first().trainCategory)
        val transfer = row.transfers.single()
        assertEquals("Milano Greco Pirelli", transfer.stationLabel)
        assertEquals("08:50", transfer.arrivalTime)
        assertEquals("08:59", transfer.departureTime)
        assertEquals(9, transfer.waitMinutes)
        assertEquals(JourneySourceLabel.OFFLINE_TIMETABLE, row.sourceLabel)
        assertTrue(row.scheduledOnly)
        assertEquals(JourneyChangesLabel.Direct, JourneyResultsPresenter.row(direct, zone).changesLabel)
        assertTrue(JourneyResultsPresenter.row(direct, zone).transfers.isEmpty())
    }

    @Test fun `filters show all by departure, only direct ones, fewest changes first or fastest first`() {
        val result = result(twoChanges, direct, oneChange)
        fun ids(filter: JourneyResultsFilter) = JourneyResultsPresenter.present(result, filter, zone).rows.map { it.id }
        assertEquals(listOf("a", "c", "b"), ids(JourneyResultsFilter.ALL))
        assertEquals(listOf("b"), ids(JourneyResultsFilter.DIRECT))
        assertEquals(listOf("b", "a", "c"), ids(JourneyResultsFilter.FEWEST_CHANGES))
        assertEquals(listOf("b", "c", "a"), ids(JourneyResultsFilter.FASTEST))
        val presentation = JourneyResultsPresenter.present(result, JourneyResultsFilter.DIRECT, zone)
        assertEquals(listOf(3, 1, 3, 3, 3), presentation.filters.map { it.count })
        assertEquals(listOf(false, true, false, false, false), presentation.filters.map { it.selected })
        assertNull(presentation.emptyReason)
        assertTrue(presentation.showScheduledOnlyNotice)
        assertTrue(presentation.showCoverageNotice)
        assertFalse(presentation.showIncompleteNotice)
    }

    @Test fun `empty lists say why`() {
        fun reason(result: JourneySearchResult, filter: JourneyResultsFilter = JourneyResultsFilter.ALL) =
            JourneyResultsPresenter.present(result, filter, zone).emptyReason
        assertEquals(JourneyEmptyReason.NO_DIRECT, reason(result(oneChange), JourneyResultsFilter.DIRECT))
        assertEquals(JourneyEmptyReason.NO_SOLUTION, reason(result(status = JourneySearchStatus.NO_SOLUTION)))
        assertEquals(JourneyEmptyReason.NOT_COVERED, reason(result(status = JourneySearchStatus.NOT_COVERED)))
        assertEquals(JourneyEmptyReason.SCHEDULE_EXPIRED, reason(result(status = JourneySearchStatus.SCHEDULE_EXPIRED)))
        assertEquals(JourneyEmptyReason.UNAVAILABLE, reason(result(status = JourneySearchStatus.UNAVAILABLE)))
        assertTrue(JourneyResultsPresenter.present(result(oneChange, incomplete = true), zone = zone).showIncompleteNotice)
    }

    @Test fun `an arrival after midnight is flagged as next day and a replacement bus is no train category`() {
        val night = option("n",
            leg("901", "RE11", "Alfa", "Beta", "23:50", "00:20", nextDay = true),
            leg("B1", null, "Beta", "Gamma", "00:35", "01:05", mode = LegMode.REPLACEMENT_BUS, kind = ServiceKind.REPLACEMENT_BUS).let {
                it.copy(scheduledDeparture = it.scheduledDeparture.plusDays(1), scheduledArrival = it.scheduledArrival.plusDays(1))
            })
        val row = JourneyResultsPresenter.row(night, zone)
        assertEquals(1, row.arrivalDayOffset)
        assertEquals("01:05", row.arrivalTime)
        assertEquals(75, row.durationMinutes)
        assertEquals(15, row.transfers.single().waitMinutes)
        assertEquals(TrainCategory.OTHER, row.legs[1].trainCategory)
        assertEquals(LegMode.REPLACEMENT_BUS, row.legs[1].mode)
    }

    @Test fun `a walk between two vehicles is a leg and the change follows it where the next vehicle leaves`() {
        val walkThenMetro = option("w",
            leg("2158", "RE11", "Lodi", "Milano Centrale", "08:14", "08:45"),
            leg("", null, "Milano Centrale", "Centrale FS M3", "08:45", "08:51", mode = LegMode.WALK, kind = ServiceKind.UNKNOWN, operator = null)
                .copy(publicNumber = null),
            leg("", "M3", "Centrale FS M3", "Duomo", "08:54", "09:02", mode = LegMode.METRO, kind = ServiceKind.URBAN, operator = "ATM")
                .copy(publicNumber = null))
        val row = JourneyResultsPresenter.row(walkThenMetro, zone)
        // A walk is no vehicle: one change, shown after the walk, at the metro stop, waiting from 08:51 to 08:54.
        assertEquals(1, row.changes)
        val transfer = row.transfers.single()
        assertEquals(1, transfer.afterLegIndex)
        assertEquals("Centrale FS M3", transfer.stationLabel)
        assertEquals("08:51" to "08:54", transfer.arrivalTime to transfer.departureTime)
        assertEquals(3, transfer.waitMinutes)
        assertEquals(listOf(JourneyLegVehicle.TRAIN, JourneyLegVehicle.OTHER, JourneyLegVehicle.METRO), row.legs.map { it.vehicle })
        assertFalse(row.hasBusLeg)

        // A walk from the origin to the first stop is no change either.
        val accessWalk = option("x",
            leg("", null, "Milano Centrale", "Centrale FS M2", "08:40", "08:45", mode = LegMode.WALK, kind = ServiceKind.UNKNOWN, operator = null),
            leg("", "14", "Centrale FS M2", "Cairoli", "08:47", "09:05", mode = LegMode.TRAM, kind = ServiceKind.URBAN, operator = "ATM"))
        val accessRow = JourneyResultsPresenter.row(accessWalk, zone)
        assertEquals(0, accessRow.changes)
        assertTrue(accessRow.transfers.isEmpty())
        assertEquals(JourneyLegVehicle.TRAM, accessRow.legs[1].vehicle)
        assertEquals(JourneyLegVehicle.FUNICULAR, JourneyLegVehicle.of(LegMode.FUNICULAR, "AMT", "FRI"))
        assertEquals(TrainCategory.OTHER, JourneyResultsPresenter.trainCategory(walkThenMetro.option.legs[2]))
    }

    @Test fun `foreign high-speed trains are not called Frecce and other sources keep their name`() {
        val railjet = leg("88", "RJ", "Brennero", "Innsbruck", "11:58", "12:32", kind = ServiceKind.HIGH_SPEED, operator = "ÖBB")
        assertEquals(TrainCategory.OTHER, JourneyResultsPresenter.trainCategory(railjet))
        val freccia = leg("9511", "FR", "Milano", "Roma", "08:00", "11:00", kind = ServiceKind.HIGH_SPEED, operator = "Trenitalia")
        assertEquals(TrainCategory.FRECCE, JourneyResultsPresenter.trainCategory(freccia))
        val ec = leg("12", "EC", "Milano", "Zurigo", "08:10", "11:27", kind = ServiceKind.INTERCITY, operator = "SBB")
        assertEquals(TrainCategory.INTERCITY, JourneyResultsPresenter.trainCategory(ec))
        val other = JourneyResultsPresenter.row(option("x", ec, source = SourceId("other-source")), zone)
        assertEquals(JourneySourceLabel.OTHER, other.sourceLabel)
        assertEquals("Fonte", other.sourceName)
    }

    @Test fun `every label key the presenter hands out exists in the default strings`() {
        val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(repositoryRoot, "app/src/main/res/values/strings.xml")).documentElement
        fun names(tag: String): Set<String> {
            val nodes = root.getElementsByTagName(tag)
            return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("name") }.toSet()
        }
        val strings = names("string")
        val plurals = names("plurals")
        val expectedStrings = JourneyResultsFilter.entries.map { it.resourceName } +
            JourneyLegVehicle.entries.mapNotNull { it.resourceName } +
            JourneyEmptyReason.entries.map { it.resourceName } +
            JourneySourceLabel.entries.mapNotNull { it.resourceName } +
            listOf(JourneyChangesLabel.directResourceName, "journey_results_title", "journey_searching", "journey_transfer_at",
                "journey_arrival_next_day", "journey_leg_replacement_bus", "journey_scheduled_only_notice",
                "journey_leg_metro", "journey_leg_tram", "journey_leg_funicular", "journey_leg_walk",
                "journey_coverage_notice", "journey_incomplete_notice", "journey_option_accessibility")
        assertEquals(emptyList<String>(), expectedStrings.filterNot(strings::contains))
        val expectedPlurals = listOf(JourneyChangesLabel.changesPluralName, "journey_transfer_wait", "journey_duration_hours", "minutes_short")
        assertEquals(emptyList<String>(), expectedPlurals.filterNot(plurals::contains))
    }
}

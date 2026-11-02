package it.binario.app.data.journey

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteFailureReason
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.repository.SourcedJourneyOption
import it.binario.app.journeys.JourneyLegVehicle
import it.binario.app.journeys.JourneyResultsPresenter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner and the route search on small extracts of the Trenitalia and Italo feeds
 * (`src/test/resources/gtfs-extracts`, cut by `tools/extract_gtfs_fixture.py` for Tuesday 10 November 2026) with
 * the real station catalogue: stops bound to catalogue stations by their codes, scheduled coaches as bus legs.
 */
class EmbeddedGtfsNationalPacksTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val tuesday = LocalDate.of(2026, 11, 10)
    private val stations: List<Station> by lazy {
        EmbeddedCuratedRailStationCatalog({ File("src/main/assets", it).inputStream() }).load()
    }
    private val trenitalia = EmbeddedGtfsPacks.all.single { it.sourceId.value == "trenitalia-gtfs" }
    private val italo = EmbeddedGtfsPacks.all.single { it.sourceId.value == "italo-gtfs" }
    private val extracts: Map<String, ByteArray> by lazy {
        listOf(trenitalia, italo).associate { it.assetPath to zipDirectory(File("src/test/resources/gtfs-extracts/${it.sourceId.value}")) }
    }

    private fun zipDirectory(directory: File): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            directory.listFiles().orEmpty().filter { it.name.endsWith(".txt") }.sortedBy { it.name }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                zip.write(file.readBytes())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private val openAsset = { path: String -> ByteArrayInputStream(extracts.getValue(path)) }

    private fun provider(
        packs: List<EmbeddedGtfsPack> = listOf(trenitalia, italo),
        settings: GtfsJourneyPlannerSettings = GtfsJourneyPlannerSettings()
    ) = EmbeddedGtfsJourneyProvider(
        adapter = EmbeddedGtfsRouteAdapter(openAsset = openAsset, packs = packs, indexCacheCapacity = 1),
        packs = packs,
        loadStations = { stations },
        settings = settings,
        clock = Clock.fixed(Instant.parse("2026-11-01T06:00:00Z"), zone)
    )

    private fun station(name: String): Station = stations.single { it.name == name }

    private fun request(from: String, to: String, time: String) = JourneySearchRequest(
        origin = StationId(station(from).id),
        destination = StationId(station(to).id),
        departureAt = ZonedDateTime.of(tuesday, LocalTime.parse(time), zone)
    )

    private fun options(result: ProviderResult<List<JourneyOption>>): List<JourneyOption> =
        (result as? ProviderResult.Success)?.value ?: throw AssertionError("Expected a complete result, got $result")

    private fun JourneyOption.summary(): String = legs.joinToString(" | ") { leg ->
        "${leg.lineLabel} ${leg.publicNumber} ${leg.originLabel} ${leg.scheduledDeparture.toLocalTime()}-" +
            "${leg.scheduledArrival.toLocalTime()} ${leg.destinationLabel}"
    }

    @Test fun `Venezia to Cortina changes from a Trenitalia train to the ItaloBus at Mestre`() = runBlocking {
        val option = options(provider().searchJourneys(request("Venezia S. Lucia", "Cortina D'Ampezzo", "09:30"))).first()
        assertEquals(
            "REG 17180 Venezia S. Lucia 10:04-10:15 Venezia Mestre | ItaloBus 1060 Venezia Mestre 10:20-13:10 Cortina D'Ampezzo",
            option.summary()
        )
        // The coach is a vehicle of its own: the train to bus transfer is a change.
        assertEquals(1, option.transfers)
        assertEquals(listOf(LegMode.TRAIN, LegMode.BUS), option.legs.map { it.mode })
        assertEquals(ServiceKind.BUS, option.legs[1].serviceKind)
        assertEquals("Italo", option.legs[1].operatorName)
        // Mestre is the catalogue station of both packs (Trenitalia location id 830002589, Italo code VEM).
        assertEquals(StationId(station("Venezia Mestre").id), option.legs[0].destination)
        assertEquals(listOf("trenitalia-gtfs", "italo-gtfs"), option.legs.map { it.runId!!.value.substringBefore(':') })

        val row = JourneyResultsPresenter.row(SourcedJourneyOption(option, EmbeddedGtfsJourneyProvider.SOURCE_ID, "offline", true))
        assertEquals(listOf(JourneyLegVehicle.TRAIN, JourneyLegVehicle.ITALOBUS), row.legs.map { it.vehicle })
        assertEquals(listOf(false, true), row.legs.map { it.isBus })
        assertTrue(row.hasBusLeg)
        assertEquals(TrainCategory.OTHER, row.legs[1].trainCategory)
        assertEquals(1, row.changes)
    }

    @Test fun `Aosta to Ivrea is a direct Trenitalia bus`() = runBlocking {
        val option = options(provider().searchJourneys(request("Aosta", "Ivrea", "08:00"))).first()
        assertEquals("BUS AO042 Aosta 08:16-09:26 Ivrea", option.summary())
        assertEquals(0, option.transfers)
        assertEquals(LegMode.BUS, option.legs.single().mode)
        assertEquals("Trenitalia", option.legs.single().operatorName)
        val row = JourneyResultsPresenter.row(SourcedJourneyOption(option, EmbeddedGtfsJourneyProvider.SOURCE_ID, "offline", true))
        assertEquals(JourneyLegVehicle.BUS_TRENITALIA, row.legs.single().vehicle)
        assertEquals("journey_leg_bus_trenitalia", row.legs.single().vehicle.resourceName)
    }

    @Test fun `Milano to Potenza takes the Frecciarossa and the Trenitalia bus from Napoli`() = runBlocking {
        val option = options(provider().searchJourneys(request("Milano Centrale", "Potenza Centrale", "08:00"))).first()
        assertEquals(
            "FR 9613 Milano Centrale 08:30-13:10 Napoli Centrale | BUS PZ101 Napoli Centrale 14:00-16:00 Potenza Centrale",
            option.summary()
        )
        assertEquals(listOf(ServiceKind.HIGH_SPEED, ServiceKind.BUS), option.legs.map { it.serviceKind })
    }

    @Test fun `a long journey leaving inside the window may arrive after it`() = runBlocking {
        // Window 07:00-15:00; the last train leaves Roma at 15:25 and reaches Napoli at 16:33.
        val found = options(provider().searchJourneys(request("Merano Meran", "Napoli Centrale", "07:00")))
        assertEquals(
            "REG 17106 Merano Meran 07:46-08:26 Bolzano Bozen | FR 9721 Bolzano Bozen 08:45-11:45 Milano Centrale | " +
                "FR 9627 Milano Centrale 11:58-16:33 Napoli Centrale",
            found.first().summary()
        )
        val withoutHorizon = provider(settings = GtfsJourneyPlannerSettings(journeyHorizonMinutes = 0))
        assertTrue(options(withoutHorizon.searchJourneys(request("Merano Meran", "Napoli Centrale", "07:00"))).isEmpty())
    }

    @Test fun `Trenitalia and Italo trains share the catalogue stations`() = runBlocking {
        val found = options(provider().searchJourneys(request("Roma Termini", "Napoli Centrale", "09:00")))
        val italoLeg = found.single { option -> option.legs.single().publicNumber == "9967" }.legs.single()
        assertEquals("Italo", italoLeg.operatorName)
        assertEquals("Italo", italoLeg.lineLabel)
        assertEquals(ServiceKind.HIGH_SPEED, italoLeg.serviceKind)
        assertEquals(LocalTime.of(9, 41), italoLeg.scheduledDeparture.toLocalTime())
        assertTrue(found.any { option -> option.legs.single().publicNumber == "9627" })
    }

    @Test fun `the route search finds Trenitalia trains by station code where the names differ`() = runBlocking {
        fun adapter(withCatalogue: Boolean) = EmbeddedGtfsRouteAdapter(
            openAsset = openAsset,
            packs = listOf(trenitalia, italo),
            stationCatalog = if (withCatalogue) ({ stations }) else null
        )
        // "FIRENZE S.MARIA NOVELLA" is not a spelling of the catalogue's "Firenze S. M. Novella": only the code binds it.
        val route = SavedRoute("firenze-siena", "Firenze-Siena", station("Firenze S. M. Novella"), station("Siena"))
        val query = JourneyQuery(tuesday, LocalTime.of(8, 0))
        val byName = adapter(withCatalogue = false).loadRoute(route, Direction.OUTBOUND, query)
        assertTrue(byName is DataResult.Failure && byName.reason == RouteFailureReason.RouteNotCovered)

        val service = (adapter(withCatalogue = true).loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success).snapshot.services.single()
        assertEquals("18203", service.number)
        assertEquals("08:10", service.scheduledDeparture)
        assertEquals(TrainCategory.REGIONALE, service.trainCategory)
        assertEquals(RailOperator.TRENITALIA_REGIONALE, service.operator)
        assertEquals("Trenitalia", service.operatorDisplayName)
        assertFalse(service.scheduledBus)

        val bus = (adapter(withCatalogue = true).loadRoute(
            SavedRoute("aosta-ivrea", "Aosta-Ivrea", station("Aosta"), station("Ivrea")), Direction.OUTBOUND, query
        ) as DataResult.Success).snapshot.services.single()
        assertEquals("AO042", bus.number)
        assertTrue(bus.scheduledBus)
        assertEquals(TrainCategory.OTHER, bus.trainCategory)

        // Italo publishes "Reggio Calabria": its code RCE is the catalogue's Reggio Di Calabria Centrale.
        val reggio = (adapter(withCatalogue = true).loadRoute(
            SavedRoute("na-rc", "Napoli-Reggio", station("Napoli Centrale"), station("Reggio Di Calabria Centrale")),
            Direction.OUTBOUND, JourneyQuery(tuesday, LocalTime.of(11, 0))
        ) as DataResult.Success).snapshot.services.single()
        assertEquals("9967", reggio.number)
        assertEquals("12:00", reggio.scheduledDeparture)
        assertEquals(TrainCategory.AV_ITALO, reggio.trainCategory)
        assertEquals("Italo", reggio.operatorDisplayName)
    }
}

package it.binario.app

import it.binario.app.data.RailOperator
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.ui.AppNavigation
import it.binario.app.ui.AppScreen
import java.util.Base64
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouriteTrainsTest {
    private fun service(number: String, origin: String, departure: String, destination: String = "Roma Termini") = TrainService(
        id = "$number-$origin", number = number, operator = RailOperator.TRENITALIA_REGIONALE, trainCategory = TrainCategory.REGIONALE,
        origin = origin, destination = destination, scheduledDeparture = departure, expectedDeparture = departure,
        platform = null, delayMinutes = 0, status = ServiceStatus.ON_TIME, stops = emptyList()
    )

    @Test
    fun `trains saved by earlier versions are read and the new fields round trip`() {
        val legacy = listOf("2153" to "Regionale · Orte", "9541" to "Frecce · Milano")
            .joinToString(";") { (number, label) -> "${b64(number)},${b64(label)}" }
        val saved = listOf(
            ServiceMonitor("2153", "Regionale · Orte", "Il mio treno delle 7:42", "Roma Termini", "07:42"),
            ServiceMonitor("9541", "Frecce · Milano")
        )

        assertEquals(listOf(ServiceMonitor("2153", "Regionale · Orte"), ServiceMonitor("9541", "Frecce · Milano")), FavouriteTrainCodec.decode(legacy))
        assertEquals(saved, FavouriteTrainCodec.decode(FavouriteTrainCodec.encode(saved)))
        assertEquals(listOf(ServiceMonitor("1", "a")), FavouriteTrainCodec.decode("${b64("1")},${b64("a")};!!!,???;${b64("")},${b64("b")}"))
        assertEquals(emptyList<ServiceMonitor>(), FavouriteTrainCodec.decode(""))
    }

    @Test
    fun `saving a train keeps origin and departure to find its run again`() {
        val monitor = FavouriteTrains.monitorOf(service("2153", "Orte", "07:42"))

        assertEquals("2153", monitor.number)
        assertEquals("Orte", monitor.origin)
        assertEquals("07:42", monitor.scheduledDeparture)
        assertEquals("Regionale · Roma Termini", monitor.label)
        assertNull(monitor.displayName)
        assertEquals("", FavouriteTrains.monitorOf(service("1", "A", "--")).scheduledDeparture)
    }

    @Test
    fun `renaming trims and bounds the name and a blank name restores the label`() {
        val list = listOf(ServiceMonitor("2153", "Regionale"), ServiceMonitor("9541", "Frecce"))

        val renamed = FavouriteTrains.rename(list, "2153", "  Il mio treno delle 7:42  ")
        assertEquals("Il mio treno delle 7:42", renamed.first().displayName)
        assertEquals(list[1], renamed[1])
        assertNull(FavouriteTrains.rename(renamed, "2153", "   ").first().displayName)
        assertEquals(FavouriteTrains.MAX_NAME_LENGTH, FavouriteTrains.rename(list, "2153", "x".repeat(80)).first().customName.length)
    }

    @Test
    fun `normalised favourites drop blanks and duplicates and keep at most twelve`() {
        val many = (1..20).map { ServiceMonitor(it.toString(), "") } + ServiceMonitor("1", "dup") + ServiceMonitor(" ", "blank")

        val normalized = FavouriteTrains.normalized(many)

        assertEquals(FavouriteTrains.MAX, normalized.size)
        assertEquals((1..12).map(Int::toString), normalized.map { it.number })
    }

    @Test
    fun `the heart saves newest first, removes a saved train, and refuses a thirteenth without dropping one`() {
        val added = FavouriteTrains.toggled(emptyList(), service("2153", "Orte", "07:42"))
        assertTrue(added is FavouriteToggle.Added)
        assertEquals(listOf("2153"), added.monitors.map { it.number })
        val second = FavouriteTrains.toggled(added.monitors, service("9541", "Roma Termini", "08:00"))
        assertEquals(listOf("9541", "2153"), second.monitors.map { it.number })
        // The same number, whatever its case, is the saved train: the heart removes it.
        val removed = FavouriteTrains.toggled(second.monitors, service("2153", "Orte", "07:42"))
        assertTrue(removed is FavouriteToggle.Removed)
        assertEquals(listOf("9541"), removed.monitors.map { it.number })

        val full = (1..FavouriteTrains.MAX).map { ServiceMonitor(it.toString(), "Treno $it", customName = if (it == 12) "Il più vecchio" else "") }
        val refused = FavouriteTrains.toggled(full, service("2153", "Orte", "07:42"))
        assertTrue(refused is FavouriteToggle.Full)
        // Nothing changes: the oldest keeps its place and its name.
        assertEquals(full, refused.monitors)
        assertEquals("Il più vecchio", refused.monitors.last().displayName)
        // A full list still lets the heart remove one of its trains.
        val freed = FavouriteTrains.toggled(full, service("12", "Orte", "07:42"))
        assertTrue(freed is FavouriteToggle.Removed)
        assertEquals(FavouriteTrains.MAX - 1, freed.monitors.size)
    }

    @Test
    fun `ordering by departure puts trains without one last and by name follows the collation`() {
        val label: (String) -> String = { "Treno $it" }
        val evening = ServiceMonitor("9541", "", customName = "Ritorno", scheduledDeparture = "18:10")
        val morning = ServiceMonitor("2153", "", customName = "andata", scheduledDeparture = "07:42")
        val legacy = ServiceMonitor("777", "")
        val accented = ServiceMonitor("4120", "", customName = "Èrcole", scheduledDeparture = "12:00")
        val list = listOf(evening, legacy, accented, morning)

        assertEquals(listOf("2153", "4120", "9541", "777"), FavouriteTrainOrdering.sorted(list, FavouriteTrainOrdering.Sort.DEPARTURE, Locale.ITALIAN, label).map { it.number })
        // "andata" < "Èrcole" < "Ritorno" < "Treno 777": case and accents do not matter.
        assertEquals(listOf("2153", "4120", "9541", "777"), FavouriteTrainOrdering.sorted(list, FavouriteTrainOrdering.Sort.NAME, Locale.ITALIAN, label).map { it.number })
        assertEquals("Treno 777", FavouriteTrainOrdering.title(legacy, label))
        assertEquals("Ritorno", FavouriteTrainOrdering.title(evening, label))
    }

    @Test
    fun `the run of a favourite is the only match or the one from its origin, never a guess`() {
        val fromOrte = service("2153", "Orte", "07:42")
        val fromRoma = service("2153", "Roma Termini", "18:10")
        val withOrigin = ServiceMonitor("2153", "", origin = "ORTE")
        val legacy = ServiceMonitor("2153", "")

        assertEquals(fromOrte, FavouriteTrainMatcher.pick(listOf(fromOrte), legacy))
        assertEquals(fromOrte, FavouriteTrainMatcher.pick(listOf(fromRoma, fromOrte), withOrigin))
        assertNull(FavouriteTrainMatcher.pick(listOf(fromRoma, fromOrte), legacy))
        assertNull(FavouriteTrainMatcher.pick(emptyList(), withOrigin))
        val twiceFromOrte = listOf(fromOrte, service("2153", "Orte", "19:00"))
        assertEquals("07:42", FavouriteTrainMatcher.pick(twiceFromOrte, withOrigin.copy(scheduledDeparture = "07:42"))!!.scheduledDeparture)
        assertTrue(FavouriteTrainMatcher.isFromOrigin(fromOrte, withOrigin))
        assertTrue(!FavouriteTrainMatcher.isFromOrigin(fromRoma, withOrigin))
        assertTrue(!FavouriteTrainMatcher.isFromOrigin(fromOrte, legacy))
    }

    @Test
    fun `the local backup carries the names and older backups still import`() {
        val roma = Station("roma", "Roma Termini", "Roma")
        val orte = Station("orte", "Orte", "Orte")
        val favourites = listOf(ServiceMonitor("2153", "Regionale · Orte", "Il mio treno delle 7:42", "Roma Termini", "07:42"), ServiceMonitor("9541", "Frecce"))
        val backup = LocalDataBackup(listOf(SavedRoute("r", "Casa", roma, orte)), emptySet(), favourites, emptyList(), emptyList(), MobilityPreferences())

        val restored = (LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(backup), listOf(roma, orte)) as LocalBackupResult.Success).backup
        assertEquals(favourites, restored.monitoredServices)

        val older = """{"schemaVersion":2,"kind":"binario-local-backup","routes":[{"id":"r","name":"Casa","originId":"roma","destinationId":"orte"}],"monitoredServices":[{"number":"2153","label":"Regionale"}]}"""
        val fromOlder = (LocalDataBackupCodec.decode(older, listOf(roma, orte)) as LocalBackupResult.Success).backup
        assertEquals(listOf(ServiceMonitor("2153", "Regionale")), fromOlder.monitoredServices)
        val badTime = older.replace("\"label\":\"Regionale\"", "\"label\":\"Regionale\",\"scheduledDeparture\":\"25:99\",\"customName\":\"Treno\"")
        assertEquals(ServiceMonitor("2153", "Regionale", "Treno"), (LocalDataBackupCodec.decode(badTime, listOf(roma, orte)) as LocalBackupResult.Success).backup.monitoredServices.single())
    }

    @Test
    fun `a widget row opens the train search only for a plain train number`() {
        assertEquals(BinarioDeepLink.FavouriteTrain("2153", "Orte"), BinarioDeepLink.FavouriteTrain.of(" 2153 ", "Orte"))
        assertNull(BinarioDeepLink.FavouriteTrain.of("21 53", "Orte"))
        assertNull(BinarioDeepLink.FavouriteTrain.of(null, null))
        assertNull(BinarioDeepLink.FavouriteTrain.of("1234567890123", ""))
        assertEquals(120, BinarioDeepLink.FavouriteTrain.of("1", "x".repeat(500))!!.origin.length)
        assertTrue(AppNavigation.targetOf(BinarioDeepLink.FavouriteTrain("2153", "")).screen == AppScreen.NUMBER_RESULTS)
    }

    private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
}

class NearestStationPickerTest {
    private val roma = Station("roma", "Roma Termini", "Roma", latitude = 41.9010, longitude = 12.5016)
    private val tiburtina = Station("tiburtina", "Roma Tiburtina", "Roma", latitude = 41.9109, longitude = 12.5303)
    private val napoli = Station("napoli", "Napoli Centrale", "Napoli", latitude = 40.8530, longitude = 14.2724)
    private val noCoordinates = Station("manual:x", "Senza coordinate", "X")

    @Test
    fun `the nearest catalogue station with coordinates is chosen for a fake location`() {
        // Piazza dei Cinquecento, in front of Termini.
        assertEquals(roma, NearestStationPicker.pick(listOf(napoli, tiburtina, noCoordinates, roma), 41.9005, 12.4990)!!.station)
        // Near Tiburtina.
        assertEquals(tiburtina, NearestStationPicker.pick(listOf(napoli, roma, tiburtina), 41.9120, 12.5320)!!.station)
    }

    @Test
    fun `no station is chosen far from the catalogue or for an invalid position`() {
        // Central Paris.
        assertNull(NearestStationPicker.pick(listOf(roma, napoli), 48.8566, 2.3522))
        assertNull(NearestStationPicker.pick(listOf(roma), Double.NaN, 12.5))
        assertNull(NearestStationPicker.pick(listOf(noCoordinates), 41.9, 12.5))
        assertEquals(napoli, NearestStationPicker.pick(listOf(roma, napoli), 41.0, 14.0, maxDistanceKm = 500.0)!!.station)
    }
}

package it.binario.app.wear

import it.binario.app.UiMessageKey
import it.binario.app.data.DataConfidence
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.SnapshotWarning
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.reliability.ReliabilityFixtures
import it.binario.app.widget.ReliabilityMark
import java.io.File
import java.time.LocalDateTime
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AndroidWearSnapshotContractTest {
    private val generatedAt = 1_893_484_800_000L
    private val goldenSnapshot = RouteSnapshot(
        route = SavedRoute(
            id = "roma-termini-napoli-centrale",
            name = "Roma–Napoli",
            outwardOrigin = Station("roma-termini", "Roma Termini", "Roma"),
            outwardDestination = Station("napoli-centrale", "Napoli Centrale", "Napoli")
        ),
        direction = Direction.OUTBOUND,
        services = listOf(
            service("fr9505", "FR 9505", RailOperator.TRENITALIA_AV, TrainCategory.FRECCE, "08:10", "08:18", "7", 8, ServiceStatus.DELAYED),
            service("ic701", "IC 701", RailOperator.TRENITALIA_LONG_DISTANCE, TrainCategory.INTERCITY, "08:35", "08:35", "9", 0, ServiceStatus.ON_TIME)
        ),
        providerName = "Trenitalia",
        retrievedAtEpochMillis = generatedAt,
        sourceObservedAtEpochMillis = 1_893_484_790_000L,
        expiresAtEpochMillis = 1_893_485_700_000L,
        freshness = Freshness.FRESH,
        confidence = DataConfidence.OPERATOR_PRIMARY,
        // The data layer's Italian prose: the golden payload proves it is never forwarded.
        notice = "Dati operativi ricevuti dal telefono."
    )

    @Test
    fun `encoder Android coincide con la fixture golden v1`() {
        val actual = AndroidWearSnapshotContract.operational(goldenSnapshot, generatedAt, strings(locale = null)).normalisedNumbers()
        val expected = fixture("android-to-wear-v1.json").normalisedNumbers()

        assertEquals(expected, actual)
    }

    @Test
    fun `the watch shows a live snapshot for fifteen minutes, not for the source cache lifetime`() {
        // A live board cached for 45 s: the route snapshot expires with it, the watch's display window must not.
        val live = goldenSnapshot.copy(expiresAtEpochMillis = generatedAt + 45_000L)

        val payload = AndroidWearSnapshotContract.operational(live, generatedAt, strings(locale = null))

        assertEquals(generatedAt + 15 * 60_000L, (payload["expiresAtEpochMillis"] as Number).toLong())
        assertEquals(15 * 60_000L, (payload["ttlMillis"] as Number).toLong())
        // The observation time still travels with it, so the watch can show how old the live values are.
        assertEquals(goldenSnapshot.sourceObservedAtEpochMillis, (payload["provenanceObservedAtEpochMillis"] as Number).toLong())
    }

    @Test
    fun `a snapshot loaded long ago is shown at most fifteen minutes past its source validity`() {
        val expiredFiveMinutesAgo = goldenSnapshot.copy(expiresAtEpochMillis = generatedAt - 5 * 60_000L)
        val payload = AndroidWearSnapshotContract.operational(expiredFiveMinutesAgo, generatedAt, strings(locale = null))
        assertEquals(generatedAt + 10 * 60_000L, (payload["expiresAtEpochMillis"] as Number).toLong())

        val expiredLongAgo = goldenSnapshot.copy(expiresAtEpochMillis = generatedAt - 15 * 60_000L)
        assertTrue(runCatching { AndroidWearSnapshotContract.operational(expiredLongAgo, generatedAt, strings(locale = null)) }.isFailure)
    }

    @Test
    fun `a confirmed cancellation never leads the glance and is named in the notice`() {
        val cancelled = goldenSnapshot.services[0].copy(status = ServiceStatus.CANCELLED, expectedDeparture = "08:10", delayMinutes = 0)
        val later = service("r3", "R 3", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "09:00", "09:00", "2", 0, ServiceStatus.ON_TIME)
        val snapshot = goldenSnapshot.copy(services = listOf(cancelled, goldenSnapshot.services[1], later))

        val payload = AndroidWearSnapshotContract.operational(snapshot, generatedAt, strings(locale = null))
        val english = AndroidWearSnapshotContract.operational(snapshot, generatedAt, strings("en"))

        assertEquals(2, payload["serviceCount"])
        assertEquals("IC 701", payload["service0Number"])
        assertEquals("R 3", payload["service1Number"])
        assertEquals("Soppresso: FR 9505 delle 08:10", payload["notice"])
        assertEquals("Cancelled: FR 9505 at 08:10", english["notice"])
        // A cancellation after the two trains sent, or one the source did not confirm, changes nothing.
        val afterBoth = AndroidWearSnapshotContract.operational(
            goldenSnapshot.copy(services = goldenSnapshot.services + later.copy(status = ServiceStatus.CANCELLED)), generatedAt, strings(locale = null)
        )
        assertEquals("", afterBoth["notice"])
        val unconfirmed = AndroidWearSnapshotContract.operational(
            goldenSnapshot.copy(services = listOf(cancelled.copy(operationalStatusVerified = false), later)), generatedAt, strings(locale = null)
        )
        assertEquals("FR 9505", unconfirmed["service0Number"])
    }

    @Test
    fun `the operator is the real agency, and the generic one follows the phone language`() {
        val agency = goldenSnapshot.services[0].copy(operator = RailOperator.OTHER, operatorDisplayName = "EAV")
        val unnamed = goldenSnapshot.services[1].copy(operator = RailOperator.OTHER, operatorDisplayName = null)
        val snapshot = goldenSnapshot.copy(services = listOf(agency, unnamed))

        val italian = AndroidWearSnapshotContract.operational(snapshot, generatedAt, strings(locale = null))
        val english = AndroidWearSnapshotContract.operational(snapshot, generatedAt, strings("en"))

        assertEquals("EAV", italian["service0Operator"])
        assertEquals("EAV", english["service0Operator"])
        assertEquals("Altro operatore", italian["service1Operator"])
        assertEquals("Other operator", english["service1Operator"])
    }

    @Test
    fun `the saved route's persistent id stays on the phone`() {
        val payload = AndroidWearSnapshotContract.operational(goldenSnapshot.copy(route = goldenSnapshot.route.copy(id = "6f1c2a8e-uuid")), generatedAt, strings(locale = null))

        assertEquals("active", payload["routeId"])
        assertFalse(payload.values.contains("6f1c2a8e-uuid"))
    }

    @Test
    fun `encoder adds the optional reliability pair as in the reliability golden fixture`() {
        val reliability = { service: TrainService -> if (service.number == "FR 9505") ReliabilityMark(22, 27, 5, 22, 30) else null }
        val actual = AndroidWearSnapshotContract.operational(goldenSnapshot, generatedAt, strings(locale = null), reliability).normalisedNumbers()

        assertEquals(fixture("android-to-wear-v1-reliability.json").normalisedNumbers(), actual)
        assertTrue(AndroidWearSnapshotContract.hasReliability(actual))
        // Stripped for a watch that does not decode it, the payload is the plain golden one again.
        assertEquals(fixture("android-to-wear-v1.json").normalisedNumbers(), AndroidWearSnapshotContract.withoutReliability(actual))
        assertFalse(AndroidWearSnapshotContract.hasReliability(fixture("android-to-wear-v1.json")))
    }

    @Test
    fun `a pair below five samples or with more days on time than samples stays out`() {
        fun pairOf(mark: ReliabilityMark) = AndroidWearSnapshotContract.operational(goldenSnapshot, generatedAt, strings(locale = null)) { mark }
            .filterKeys { it.contains("Reliability") }
        assertEquals(emptyMap<String, Any>(), pairOf(ReliabilityMark(4, 4, 5, 4, 30)))
        assertEquals(emptyMap<String, Any>(), pairOf(ReliabilityMark(31, 30, 5, 30, 30)))
        assertEquals(4, pairOf(ReliabilityMark(5, 5, 15, 3, 30)).size)
    }

    @Test
    fun `reliability comes from the statistics for the direct trains of the route only`() {
        val stats = ReliabilityFixtures.stats(ReliabilityFixtures.FR_9558)
        val route = SavedRoute(
            id = "roma-milano",
            name = "Roma–Milano",
            outwardOrigin = Station("roma-termini", "Roma Termini", "Roma", railwayCode = "S08409"),
            outwardDestination = Station("milano-centrale", "Milano Centrale", "Milano", railwayCode = "S01700")
        )
        val train = service("fr9558", "9558", RailOperator.TRENITALIA_AV, TrainCategory.FRECCE, "08:00", "08:00", "7", 0, ServiceStatus.ON_TIME)
        val snapshot = goldenSnapshot.copy(route = route, services = listOf(train))

        val reliability = AndroidWearSnapshotContract.reliabilityOf(stats, snapshot)
        assertEquals(ReliabilityMark(22, 30, 5, 22, 30), reliability(train))
        assertNull(reliability(train.copy(scheduledBus = true)))
        assertNull(reliability(train.copy(transferCount = 1)))
        // The return direction boards at Milano: the statistics have no FR 9558 from there to Roma.
        assertNull(AndroidWearSnapshotContract.reliabilityOf(stats, snapshot.copy(direction = Direction.RETURN))(train))
        assertNull(AndroidWearSnapshotContract.reliabilityOf(stats, snapshot.copy(confidence = DataConfidence.DEMONSTRATION))(train))
    }

    @Test
    fun `the pair goes only to a connected watch set that all decode it`() {
        assertTrue(AndroidWearSnapshotContract.watchesDecodeReliability(setOf("watch"), setOf("watch")))
        assertTrue(AndroidWearSnapshotContract.watchesDecodeReliability(setOf("watch"), setOf("watch", "other")))
        assertFalse(AndroidWearSnapshotContract.watchesDecodeReliability(setOf("watch", "old-watch"), setOf("watch")))
        assertFalse(AndroidWearSnapshotContract.watchesDecodeReliability(emptySet(), setOf("watch")))
        assertFalse(AndroidWearSnapshotContract.watchesDecodeReliability(setOf("old-watch"), emptySet()))
    }

    @Test
    fun `the watch declares the capability the phone checks`() {
        val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val watch = File(repositoryRoot, "wear/src/main/res/values/wear.xml").readText()
        assertTrue(watch.contains("<item>${AndroidWearSnapshotContract.RELIABILITY_CAPABILITY}</item>"))
    }

    @Test
    fun `payload texts follow the phone language instead of the Italian enum labels`() {
        val english = strings("en")
        val services = goldenSnapshot.services.map { it.copy(operationalStatusVerified = false) } + goldenSnapshot.services.first()

        val payload = AndroidWearSnapshotContract.operational(goldenSnapshot, generatedAt, english)
        val scheduled = AndroidWearSnapshotContract.operational(goldenSnapshot.copy(services = services.take(2)), generatedAt, english)
        val unavailable = AndroidWearSnapshotContract.unavailable("Roma Termini → Napoli Centrale", "", generatedAt, english)

        assertEquals("Operator primary source", payload["provenanceSourceLabel"])
        assertEquals("Freccia", payload["service0Category"])
        assertEquals("Delayed", payload["service0Status"])
        assertEquals("On time", payload["service1Status"])
        assertEquals("Scheduled", scheduled["service0Status"])
        assertEquals("Real-time data not available", unavailable["notice"])
        assertEquals("Not available", unavailable["provenanceSourceLabel"])
    }

    @Test
    fun `notice carries the phone's localized advisory or first warning, never the data source's prose`() {
        val conflicting = goldenSnapshot.copy(qualityWarnings = setOf(SnapshotWarning.SOURCE_CONFLICT))
        val fallback = conflicting.copy(coverageFallback = true)
        val stale = goldenSnapshot.copy(qualityWarnings = setOf(SnapshotWarning.STALE_SOURCE, SnapshotWarning.INCOMPLETE_SERVICE))

        fun notice(snapshot: RouteSnapshot, locale: String?) = AndroidWearSnapshotContract.operational(snapshot, generatedAt, strings(locale))["notice"]

        assertEquals("", notice(goldenSnapshot, null))
        assertEquals("Fonti in conflitto per alcuni campi: verifica il canale ufficiale prima di viaggiare.", notice(conflicting, null))
        assertEquals("Orario da una fonte di copertura non ufficiale: verifica con il vettore prima di partire.", notice(fallback, null))
        assertEquals("Aggiornamento non recente", notice(stale, null))
        assertEquals("", notice(goldenSnapshot, "en"))
        assertEquals("Sources conflict on some fields: check the official channel before you travel.", notice(conflicting, "en"))
        assertEquals("Timetable from an unofficial coverage source: check with the operator before you travel.", notice(fallback, "en"))
        assertEquals("Update not recent", notice(stale, "en"))
    }

    @Test
    fun `encoder trims whitespace introduced by notice truncation`() {
        val generatedAt = 1_893_484_800_000L
        val snapshot = RouteSnapshot(
            route = SavedRoute(
                id = "roma-napoli",
                name = "Roma–Napoli",
                outwardOrigin = Station("roma", "Roma Termini", "Roma"),
                outwardDestination = Station("napoli", "Napoli Centrale", "Napoli")
            ),
            direction = Direction.OUTBOUND,
            services = emptyList(),
            providerName = "RFI",
            retrievedAtEpochMillis = generatedAt,
            sourceObservedAtEpochMillis = generatedAt,
            expiresAtEpochMillis = generatedAt + 60_000L,
            freshness = Freshness.AGING,
            confidence = DataConfidence.INFRASTRUCTURE_PRIMARY,
            coverageFallback = true
        )
        val strings = strings(locale = null).let {
            it.copy(advisoryNotices = it.advisoryNotices + (UiMessageKey.ROUTE_COVERAGE_FALLBACK to "x".repeat(179) + " parole oltre il limite"))
        }

        val notice = AndroidWearSnapshotContract.operational(snapshot, generatedAt, strings).getValue("notice") as String

        assertEquals(179, notice.length)
        assertFalse(notice.endsWith(" "))
    }

    private fun service(
        id: String,
        number: String,
        operator: RailOperator,
        category: TrainCategory,
        scheduled: String,
        expected: String,
        platform: String,
        delay: Int,
        status: ServiceStatus
    ) = TrainService(
        id = id,
        number = number,
        operator = operator,
        trainCategory = category,
        origin = "Roma Termini",
        destination = "Napoli Centrale",
        scheduledDeparture = scheduled,
        expectedDeparture = expected,
        platform = platform,
        delayMinutes = delay,
        status = status,
        stops = emptyList(),
        operationalStatusVerified = true,
        scheduledDepartureDateTime = LocalDateTime.of(2030, 1, 1, scheduled.substringBefore(':').toInt(), scheduled.substringAfter(':').toInt())
    )

    /** The texts the phone would resolve from its own string catalogue for the given locale (null = Italian default). */
    private fun strings(locale: String?): WearSnapshotStrings {
        val catalogue = catalogue(locale)
        return WearSnapshotStrings(
            confidenceLabels = DataConfidence.entries.associateWith { catalogue.getValue("data_confidence_${it.name.lowercase()}") },
            categoryLabels = TrainCategory.entries.associateWith { catalogue.getValue(categoryKeys.getValue(it)) },
            statusLabels = ServiceStatus.entries.associateWith { catalogue.getValue("service_status_${it.name.lowercase()}") },
            advisoryNotices = WearSnapshotStrings.advisoryKeys.associateWith { catalogue.getValue(it.resourceName) },
            warningLabels = SnapshotWarning.entries.associateWith { catalogue.getValue("snapshot_warning_${it.name.lowercase()}") },
            scheduledStatus = catalogue.getValue("service_status_scheduled_badge"),
            unavailableNotice = catalogue.getValue("data_status_unavailable"),
            missingValue = catalogue.getValue("wear_payload_value_unavailable"),
            cancelledNotice = catalogue.getValue("wear_notice_cancelled"),
            otherOperator = catalogue.getValue("wear_operator_other")
        )
    }

    private fun catalogue(locale: String?): Map<String, String> {
        val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val file = File(repositoryRoot, "app/src/main/res/values${locale?.let { "-$it" }.orEmpty()}/strings.xml")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val nodes = root.childNodes
        return buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                if (element.tagName == "string") put(element.getAttribute("name"), element.textContent.replace("\\'", "'"))
            }
        }
    }

    private val categoryKeys = mapOf(
        TrainCategory.REGIONALE to "train_category_regionale",
        TrainCategory.REGIONALE_VELOCE to "train_category_regionale_veloce",
        TrainCategory.INTERCITY to "train_category_intercity",
        TrainCategory.FRECCE to "train_category_frecce",
        TrainCategory.AV_ITALO to "train_category_italo_high_speed",
        TrainCategory.TRENORD_REGIO to "train_category_trenord_regional",
        TrainCategory.METROPOLITANO to "train_category_metropolitan",
        TrainCategory.OTHER to "train_category_other"
    )

    private fun fixture(name: String): Map<String, Any> {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        return json.keys().asSequence().associateWith { key -> json.get(key) }
    }

    private fun <V> Map<String, V>.normalisedNumbers(): Map<String, Any?> = mapValues { (_, value) ->
        if (value is Number) value.toLong() else value
    }
}

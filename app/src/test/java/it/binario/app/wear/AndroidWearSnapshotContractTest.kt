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
import java.io.File
import java.time.LocalDateTime
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("Live data unavailable", unavailable["notice"])
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
            missingValue = catalogue.getValue("wear_payload_value_unavailable")
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

    private fun Map<String, Any>.normalisedNumbers(): Map<String, Any> = mapValues { (_, value) ->
        if (value is Number) value.toLong() else value
    }
}

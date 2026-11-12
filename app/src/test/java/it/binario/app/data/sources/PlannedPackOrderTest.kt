package it.binario.app.data.sources

import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.sources.PlannedPackClass.COMMUNITY_FALLBACK
import it.binario.app.data.sources.PlannedPackClass.FOREIGN
import it.binario.app.data.sources.PlannedPackClass.ITALIAN_REGIONAL
import it.binario.app.data.sources.PlannedPackClass.NATIONAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlannedPackOrderTest {
    private fun classOf(id: String) = PlannedPackOrder.classOf(SourceId(id))

    @Test fun `every railway pack of the app takes its class from the catalogue`() {
        val railway = EmbeddedGtfsPacks.forBuild(personalSources = true).filterNot(EmbeddedGtfsPack::isUrban)
        // A pack lands in a class by its catalogue data: each one has an entry with its countries.
        railway.forEach { pack -> assertNotNull(pack.sourceId.value, RailSourceInventory.byId(pack.sourceId)?.countryCodes?.firstOrNull()) }
        assertEquals(
            mapOf(
                "trenitalia-gtfs" to NATIONAL,
                "italo-gtfs" to NATIONAL,
                "alto-adige-sta-gtfs" to ITALIAN_REGIONAL,
                "arst-gtfs" to ITALIAN_REGIONAL,
                "eav-gtfs" to ITALIAN_REGIONAL,
                "lombardia-trenord-gtfs" to ITALIAN_REGIONAL,
                "toscana-tft-gtfs" to ITALIAN_REGIONAL,
                "trentino-gtfs" to ITALIAN_REGIONAL,
                "fal-basilicata-puglia" to ITALIAN_REGIONAL,
                "ferrotramviaria-bari-barletta" to ITALIAN_REGIONAL,
                "fdg-gargano" to ITALIAN_REGIONAL,
                "micotra-udine-villach" to ITALIAN_REGIONAL,
                "amt-genova-casella-gtfs" to ITALIAN_REGIONAL,
                "cotral-ferro-gtfs" to ITALIAN_REGIONAL,
                "ch-otd-gtfs" to FOREIGN,
                "oebb-gtfs" to FOREIGN,
                "sncf-gtfs" to FOREIGN
            ),
            railway.associate { it.sourceId.value to PlannedPackOrder.classOf(it.sourceId) }
        )
    }

    @Test fun `the first catalogue country decides between Italian and foreign`() {
        // South Tyrol and Mi.Co.Tra. are Italian lines into Austria; ÖBB, SNCF and the Swiss dataset serve Italy too.
        assertEquals(listOf("IT", "AT"), RailSourceCatalog.byId(SourceId("alto-adige-sta-gtfs"))!!.countryCodes.toList())
        assertEquals(ITALIAN_REGIONAL, classOf("alto-adige-sta-gtfs"))
        assertEquals(listOf("AT", "IT"), RailSourceCatalog.byId(SourceId("oebb-gtfs"))!!.countryCodes.toList())
        assertEquals(FOREIGN, classOf("oebb-gtfs"))
        assertEquals(FOREIGN, classOf("delfi-gtfs"))
        assertEquals(COMMUNITY_FALLBACK, classOf("delfi-gtfs-community"))
        assertEquals(COMMUNITY_FALLBACK, classOf("european-sleeper-gtfs"))
        // A pack the catalogue does not know is taken as Italian.
        assertEquals(ITALIAN_REGIONAL, classOf("not-in-the-catalogue"))
        // The global scores, which also rank the live sources and the snapshots, stay as they were.
        assertEquals(140, RailSourceSelectionPolicy.score(RailInformationCategory.PLANNED_ITINERARY, SourceId("ch-otd-gtfs")))
        assertEquals(130, RailSourceSelectionPolicy.score(RailInformationCategory.PLANNED_ITINERARY, SourceId("trenitalia-gtfs")))
    }

    private data class Covered(val id: String, val origin: Boolean = true, val destination: Boolean = true)

    private fun order(vararg packs: Covered): List<String> =
        PlannedPackOrder.ordered(packs.toList(), { SourceId(it.id) }, Covered::origin, Covered::destination).map { it.id }

    @Test fun `national packs first, then Italian regional, foreign and community packs`() {
        assertEquals(
            listOf(
                "italo-gtfs", "trenitalia-gtfs", "lombardia-trenord-gtfs", "alto-adige-sta-gtfs", "fal-basilicata-puglia",
                "ch-otd-gtfs", "oebb-gtfs", "delfi-gtfs", "european-sleeper-gtfs"
            ),
            order(
                Covered("ch-otd-gtfs"), Covered("oebb-gtfs"), Covered("lombardia-trenord-gtfs"), Covered("italo-gtfs"),
                Covered("alto-adige-sta-gtfs"), Covered("european-sleeper-gtfs"), Covered("trenitalia-gtfs"), Covered("delfi-gtfs"),
                Covered("fal-basilicata-puglia")
            )
        )
    }

    @Test fun `the packs serving an end no national pack serves come first`() {
        // Merano: only the South Tyrol pack.
        assertEquals(
            listOf("alto-adige-sta-gtfs", "trenitalia-gtfs", "oebb-gtfs"),
            order(Covered("oebb-gtfs", origin = false), Covered("trenitalia-gtfs", origin = false), Covered("alto-adige-sta-gtfs", destination = false))
        )
        // Lugano: only the Swiss pack, before the national and the regional ones serving the other end.
        assertEquals(
            listOf("ch-otd-gtfs", "trenitalia-gtfs", "lombardia-trenord-gtfs"),
            order(Covered("trenitalia-gtfs", origin = false), Covered("lombardia-trenord-gtfs", origin = false), Covered("ch-otd-gtfs", destination = false))
        )
        // Innsbruck as destination: among the packs serving it the foreign ones come first.
        assertEquals(
            listOf("oebb-gtfs", "alto-adige-sta-gtfs", "trenitalia-gtfs"),
            order(Covered("oebb-gtfs", origin = false), Covered("trenitalia-gtfs", destination = false), Covered("alto-adige-sta-gtfs"))
        )
        // Lugano served by Trenord too: the Swiss pack first, then Trenord, then the national pack.
        assertEquals(
            listOf("ch-otd-gtfs", "lombardia-trenord-gtfs", "trenitalia-gtfs"),
            order(Covered("trenitalia-gtfs", origin = false), Covered("lombardia-trenord-gtfs"), Covered("ch-otd-gtfs", destination = false))
        )
        // A national pack serving the end, even with another, leaves the default order.
        assertEquals(
            listOf("trenitalia-gtfs", "alto-adige-sta-gtfs", "oebb-gtfs"),
            order(Covered("oebb-gtfs"), Covered("alto-adige-sta-gtfs"), Covered("trenitalia-gtfs"))
        )
    }

    @Test fun `the operator affinity orders packs within a class, never across classes`() {
        data class Service(val id: String, val operator: String?)
        val sorted = listOf(
            Service("ch-otd-gtfs", "TILO"), Service("oebb-gtfs", "ÖBB Personenverkehr"),
            Service("lombardia-trenord-gtfs", "Trenord"), Service("trenitalia-gtfs", null)
        ).sortedWith(PlannedPackOrder.comparator({ SourceId(it.id) }, { it.operator }))
        assertEquals(listOf("trenitalia-gtfs", "lombardia-trenord-gtfs", "ch-otd-gtfs", "oebb-gtfs"), sorted.map { it.id })
        val oebbTrain = listOf(Service("ch-otd-gtfs", "ÖBB"), Service("oebb-gtfs", "ÖBB"))
            .sortedWith(PlannedPackOrder.comparator({ SourceId(it.id) }, { it.operator }))
        assertEquals(listOf("oebb-gtfs", "ch-otd-gtfs"), oebbTrain.map { it.id })
    }
}

package it.binario.app.data

import it.binario.app.AppLocales
import it.binario.app.links.OfficialLinks
import it.binario.app.links.OperatorLinkKind
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** When the train detail states the compensation rule of Regulation (EU) 2021/782 art. 19. */
class DelayCompensationRuleTest {
    private fun stop(name: String, delay: Int, completed: Boolean, verified: Boolean?, routeChange: StopRouteChange = StopRouteChange.NONE) =
        TrainStop(name, "10:00", "10:00", null, delay, completed, routeChange = routeChange, operationalStatusVerified = verified)

    /** A run whose last stop (the destination shown) arrived [delay] minutes late with an actual time the source verified. */
    private fun service(
        delay: Int,
        completed: Boolean = true,
        verified: Boolean? = true,
        status: ServiceStatus = ServiceStatus.DELAYED,
        serviceVerified: Boolean = true,
        routeChange: StopRouteChange = StopRouteChange.NONE
    ) = TrainService(
        "viaggiatreno:S01700:2153:1", "2153", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "Milano Centrale", "Pavia",
        "09:00", "09:00", null, delay, status,
        listOf(stop("Milano Centrale", 0, completed = true, verified = true), stop("Pavia", delay, completed, verified, routeChange)),
        operatorDisplayName = "Trenitalia",
        operationalStatusVerified = serviceVerified
    )

    @Test fun `59 minutes is not enough, 60 is a quarter of the price and 120 a half`() {
        assertNull(DelayCompensationRule.of(service(59), isDemonstration = false))
        assertEquals(DelayCompensation("Pavia", 60, CompensationShare.QUARTER), DelayCompensationRule.of(service(60), isDemonstration = false))
        assertEquals(DelayCompensation("Pavia", 119, CompensationShare.QUARTER), DelayCompensationRule.of(service(119), isDemonstration = false))
        assertEquals(DelayCompensation("Pavia", 120, CompensationShare.HALF), DelayCompensationRule.of(service(120), isDemonstration = false))
        assertEquals(DelayCompensation("Pavia", 184, CompensationShare.HALF), DelayCompensationRule.of(service(184), isDemonstration = false))
        assertEquals(25, CompensationShare.QUARTER.percent)
        assertEquals(50, CompensationShare.HALF.percent)
    }

    @Test fun `the notice names the stop it measures, the last one shown`() {
        // After a train-number search the last stop shown is the terminus: the notice says which station it means.
        val terminus = service(75).let { it.copy(stops = it.stops + stop("Voghera", 81, completed = true, verified = true)) }
        assertEquals(DelayCompensation("Voghera", 81, CompensationShare.QUARTER), DelayCompensationRule.of(terminus, isDemonstration = false))
    }

    @Test fun `every language names the station and the delay in the notice`() {
        for (tag in AppLocales.tags) {
            val strings = strings(tag)
            for (key in listOf("compensation_notice_quarter", "compensation_notice_half")) {
                val text = String.format(Locale.ROOT, strings.getValue(key), "Roma Termini", "75 min")
                assertTrue("$tag/$key: $text", text.contains("Roma Termini") && text.contains("75 min"))
                assertFalse("$tag/$key: $text", Regex("%(\\d+\\$)?[sd]").containsMatchIn(text))
                assertTrue("$tag/$key: $text", text.contains("2021/782") && text.contains("50"))
            }
            assertTrue(tag, String.format(Locale.ROOT, strings.getValue("compensation_notice_quarter"), "Roma Termini", "75 min").contains("25"))
            assertTrue(tag, strings.getValue("compensation_notice_conditions").contains("4"))
        }
        assertEquals(
            "Arrivato a Roma Termini con 75 min di ritardo: per il regolamento UE 2021/782 potresti avere diritto a un indennizzo " +
                "del 25% del prezzo del biglietto (50% da 120 minuti).",
            String.format(Locale.ROOT, strings("it").getValue("compensation_notice_quarter"), "Roma Termini", "75 min")
        )
    }

    private fun strings(tag: String): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(repositoryRoot, "app/src/main/res/${AppLocales.folder(tag)}/strings.xml")).documentElement
        val nodes = root.getElementsByTagName("string")
        return (0 until nodes.length).associate { index -> (nodes.item(index) as Element).let { it.getAttribute("name") to it.textContent } }
    }

    private val repositoryRoot: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test fun `nothing on unverified, estimated, scheduled-only or demonstration data`() {
        // Not yet arrived: the delay is a forecast.
        assertNull(DelayCompensationRule.of(service(75, completed = false), isDemonstration = false))
        // The stop's time is not verified by the source.
        assertNull(DelayCompensationRule.of(service(75, verified = false), isDemonstration = false))
        // The stop inherits the run's state: a run without verified operational data says nothing.
        assertNull(DelayCompensationRule.of(service(75, verified = null, serviceVerified = false), isDemonstration = false))
        assertEquals(CompensationShare.QUARTER, DelayCompensationRule.of(service(75, verified = null, serviceVerified = true), isDemonstration = false)?.share)
        assertNull(DelayCompensationRule.of(service(75), isDemonstration = true))
        // A timetable run of the offline packs: never completed, never verified.
        val scheduled = service(0).copy(
            stops = listOf(stop("Milano Centrale", 0, completed = false, verified = false), stop("Pavia", 0, completed = false, verified = false)),
            operationalStatusVerified = false
        )
        assertNull(DelayCompensationRule.of(scheduled, isDemonstration = false))
        assertNull(DelayCompensationRule.of(service(75).copy(stops = emptyList()), isDemonstration = false))
    }

    @Test fun `nothing for a cancelled train or a destination it skipped`() {
        assertNull(DelayCompensationRule.of(service(90, status = ServiceStatus.CANCELLED), isDemonstration = false))
        assertNull(DelayCompensationRule.of(service(90, routeChange = StopRouteChange.SKIPPED), isDemonstration = false))
    }

    @Test fun `the notice links the operator's official refunds page when one is known`() {
        assertEquals(
            "https://www.trenitalia.com/it/informazioni/la-guida-del-viaggiatore/i-rimborsi.html",
            OfficialLinks.operatorOf(RailOperator.TRENITALIA_REGIONALE, "Trenitalia")?.url(OperatorLinkKind.REFUNDS)
        )
        assertEquals(
            "https://www.italotreno.com/en/support-contacts/claims-management",
            OfficialLinks.operatorOf(RailOperator.ITALO, "Italo")?.url(OperatorLinkKind.REFUNDS)
        )
        assertEquals(
            "https://www.trenord.it/en/assistance/services/refunds-and-indemnities/request-a-refund/",
            OfficialLinks.operatorOf(RailOperator.TRENORD, null)?.url(OperatorLinkKind.REFUNDS)
        )
        // An operator without a known page: no button.
        assertNull(OfficialLinks.operatorOf(RailOperator.OTHER, "Ferrovie Appulo Lucane")?.url(OperatorLinkKind.REFUNDS))
        OfficialLinks.ALL.forEach { operator ->
            operator.url(OperatorLinkKind.REFUNDS)?.let { assertEquals(it, true, OfficialLinks.isOfficial(it, operator.domains)) }
        }
    }
}

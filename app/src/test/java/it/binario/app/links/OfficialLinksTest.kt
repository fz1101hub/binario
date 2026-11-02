package it.binario.app.links

import it.binario.app.data.RailOperator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialLinksTest {
    @Test fun `every link is https on the operator's own domain`() {
        OfficialLinks.ALL.forEach { operator ->
            assertTrue("${operator.id} has links", operator.links.isNotEmpty())
            operator.links.forEach { link ->
                assertTrue("${operator.id} ${link.url}", link.url.startsWith("https://"))
                assertTrue("${operator.id} ${link.url} is not on ${operator.domains}", OfficialLinks.isOfficial(link.url, operator.domains))
            }
            assertEquals("${operator.id} repeats a kind", operator.links.map { it.kind }.distinct(), operator.links.map { it.kind })
        }
        assertEquals(OfficialLinks.ALL.map { it.id }.distinct(), OfficialLinks.ALL.map { it.id })
        assertTrue(OfficialLinks.isOfficial(OfficialLinks.FS_NEWS, OfficialLinks.FS_NEWS_DOMAINS))
    }

    @Test fun `the national operators offer purchase, assistance and refunds`() {
        listOf(OfficialLinks.TRENITALIA, OfficialLinks.ITALO, OfficialLinks.TRENORD).forEach { operator ->
            listOf(OperatorLinkKind.BUY, OperatorLinkKind.CUSTOMER_CARE, OperatorLinkKind.REFUNDS).forEach { kind ->
                assertTrue("${operator.id} $kind", operator.url(kind) != null)
            }
        }
    }

    @Test fun `only https hosts of the official domain pass`() {
        val domains = setOf("trenitalia.com")
        assertTrue(OfficialLinks.isOfficial("https://www.trenitalia.com/it.html", domains))
        assertTrue(OfficialLinks.isOfficial("https://trenitalia.com/", domains))
        assertFalse(OfficialLinks.isOfficial("http://www.trenitalia.com/", domains))
        assertFalse(OfficialLinks.isOfficial("https://www.trenitalia.com.example.org/", domains))
        assertFalse(OfficialLinks.isOfficial("https://faketrenitalia.com/", domains))
        assertFalse(OfficialLinks.isOfficial("https://user@www.trenitalia.com/", domains))
        assertFalse(OfficialLinks.isOfficial("https://www.trenitalia.com:8443/", domains))
        assertFalse(OfficialLinks.isOfficial("javascript:alert(1)", domains))
        assertFalse(OfficialLinks.isOfficial("not a url", domains))
    }

    @Test fun `the official site of a journey is Trenitalia or Italo, never a guess`() {
        assertEquals(OfficialLinks.ITALO, OfficialLinks.purchaseSiteOf(RailOperator.ITALO))
        assertEquals(OfficialLinks.TRENITALIA, OfficialLinks.purchaseSiteOf(RailOperator.TRENITALIA_AV))
        // The published brand wins over the legacy constant several sources use for every train.
        assertNull(OfficialLinks.purchaseSiteOf(RailOperator.TRENITALIA_REGIONALE, "Trenord"))
        assertNull(OfficialLinks.purchaseSiteOf(RailOperator.SBB))
        assertEquals(OfficialLinks.TRENITALIA, OfficialLinks.purchaseSiteOf(listOf("Trenitalia", "Trenitalia")))
        assertNull(OfficialLinks.purchaseSiteOf(listOf("Trenitalia", "Italo")))
        assertNull(OfficialLinks.purchaseSiteOf(listOf("Trenitalia", null)))
        assertEquals(OfficialLinks.ITALO, OfficialLinks.purchaseSiteOf(listOf("Italo")))
    }

    @Test fun `strike notices link the guaranteed trains of their publisher only`() {
        assertEquals(OfficialLinks.TRENITALIA.url(OperatorLinkKind.STRIKES), OfficialLinks.guaranteedTrainsOf("TRENITALIA"))
        assertEquals(OfficialLinks.TRENORD.url(OperatorLinkKind.STRIKES), OfficialLinks.guaranteedTrainsOf("TRENORD"))
        assertNull(OfficialLinks.guaranteedTrainsOf("RFI"))
    }
}

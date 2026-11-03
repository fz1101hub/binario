package it.binario.app

import it.binario.app.data.Direction
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinarioDeepLinkTest {
    @Test fun `agenda notification opens only the allowlisted destination without parameters`() {
        assertEquals(BinarioDeepLink.Agenda, BinarioDeepLinkParser.parse(BinarioDeepLinkParser.viewAction, "binario://agenda"))
        listOf("binario://agenda?journey=private", "binario://agenda/extra", "binario://agenda#fragment")
            .forEach { assertNull(BinarioDeepLinkParser.parse(BinarioDeepLinkParser.viewAction, it)) }
    }
    @Test
    fun `allows only the documented route and departures destinations`() {
        assertEquals(
            BinarioDeepLink.ActiveRoute,
            BinarioDeepLinkParser.parse(BinarioDeepLinkParser.viewAction, "binario://route/active")
        )
        assertEquals(
            BinarioDeepLink.NextDepartures,
            BinarioDeepLinkParser.parse(BinarioDeepLinkParser.viewAction, "binario://departures/next")
        )
    }

    @Test
    fun `allows train shortcut with no number so UI can select train mode`() {
        assertEquals(
            BinarioDeepLink.TrainSearch(number = null),
            BinarioDeepLinkParser.parse(BinarioDeepLinkParser.viewAction, "binario://train/search")
        )
    }

    @Test
    fun `allows a bounded alphanumeric train number`() {
        assertEquals(
            BinarioDeepLink.TrainSearch("FR9512A"),
            BinarioDeepLinkParser.parse(BinarioDeepLinkParser.viewAction, "binario://train/search?number=FR9512A")
        )
        assertEquals(
            BinarioDeepLink.TrainSearch("123456789012"),
            BinarioDeepLinkParser.parse(BinarioDeepLinkParser.viewAction, "binario://train/search?number=123456789012")
        )
    }

    @Test
    fun `rejects non allowlisted action scheme host path and query`() {
        val invalid = listOf(
            "https://route/active",
            "binario://route/active?unexpected=yes",
            "binario://train/search?number=1234567890123",
            "binario://train/search?number=9512&operator=FS",
            "binario://train/search?number=95%2012",
            "binario://train/search?number=",
            "binario://train/search/extra",
            "binario://route/inactive",
            "binario://unknown/active",
            "binario://route@evil/active",
            "binario://route:8080/active",
            "binario://route/active#fragment"
        )

        invalid.forEach { uri ->
            assertNull("Expected rejection for $uri", BinarioDeepLinkParser.parse(BinarioDeepLinkParser.viewAction, uri))
        }
        assertNull(BinarioDeepLinkParser.parse("android.intent.action.SEND", "binario://route/active"))
        assertNull(BinarioDeepLinkParser.parse(BinarioDeepLinkParser.viewAction, null))
    }

    @Test
    fun `cold start request is consumed exactly once`() {
        val dispatcher = DeepLinkRequestDispatcher()

        val coldStart = dispatcher.receive(BinarioDeepLinkParser.viewAction, "binario://route/active")

        requireNotNull(coldStart)
        assertEquals(BinarioDeepLink.ActiveRoute, coldStart.deepLink)
        assertEquals(coldStart, dispatcher.pending())
        assertTrue(dispatcher.consume(coldStart.id))
        assertNull(dispatcher.pending())
        assertFalse(dispatcher.consume(coldStart.id))
    }

    @Test
    fun `new intent replaces pending request and stale acknowledgement cannot consume it`() {
        val dispatcher = DeepLinkRequestDispatcher()
        val coldStart = requireNotNull(
            dispatcher.receive(BinarioDeepLinkParser.viewAction, "binario://route/active")
        )
        val newIntent = requireNotNull(
            dispatcher.receive(BinarioDeepLinkParser.viewAction, "binario://train/search?number=9512")
        )

        assertTrue(newIntent.id > coldStart.id)
        assertEquals(BinarioDeepLink.TrainSearch("9512"), dispatcher.pending()?.deepLink)
        assertFalse(dispatcher.consume(coldStart.id))
        assertEquals(newIntent, dispatcher.pending())
        assertTrue(dispatcher.consume(newIntent.id))
        assertNull(dispatcher.pending())
    }

    @Test
    fun `invalid new intent clears an unconsumed request`() {
        val dispatcher = DeepLinkRequestDispatcher()
        dispatcher.receive(BinarioDeepLinkParser.viewAction, "binario://route/active")

        assertNull(dispatcher.receive(BinarioDeepLinkParser.viewAction, "https://route/active"))
        assertNull(dispatcher.pending())
    }

    @Test
    fun `widget route uses the same consumable request lifecycle`() {
        val dispatcher = DeepLinkRequestDispatcher()
        val route = SavedRoute(
            id = "widget-route",
            name = "Casa",
            outwardOrigin = Station("roma", "Roma Termini", "Roma"),
            outwardDestination = Station("tivoli", "Tivoli", "Tivoli")
        )

        val request = dispatcher.receive(BinarioDeepLink.WidgetRoute(route, Direction.RETURN))

        assertEquals(BinarioDeepLink.WidgetRoute(route, Direction.RETURN), request.deepLink)
        assertEquals(request, dispatcher.pending())
        assertTrue(dispatcher.consume(request.id))
        assertNull(dispatcher.pending())
    }
}

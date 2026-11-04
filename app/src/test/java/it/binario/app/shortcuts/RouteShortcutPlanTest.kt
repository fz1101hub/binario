package it.binario.app.shortcuts

import it.binario.app.UNCONFIGURED_ROUTE_ID
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteShortcutPlanTest {
    private val roma = Station("curated:roma-termini", "Roma Termini", "Roma", railwayCode = "S08409", latitude = 41.9, longitude = 12.5)
    private val napoli = Station("curated:napoli-centrale", "Napoli Centrale", "Napoli", railwayCode = "S09218")
    private val milano = Station("curated:milano-centrale", "Milano Centrale", "Milano", aliases = listOf("Milano C.le"))

    private fun route(id: String, name: String, pinned: Boolean = false, order: Int? = null) =
        SavedRoute(id, name, roma, napoli, isPinned = pinned, manualOrder = order)

    @Test fun `up to four saved routes get a shortcut, pinned first, within the room the launcher leaves`() {
        val routes = listOf(
            route("a", "Andata"), route("b", "Barletta"), route("c", "Casa", pinned = true), route("d", "Desenzano"),
            route("e", "Empoli"), route("f", "Firenze", pinned = true)
        )
        assertEquals(listOf("c", "f", "a", "b"), RouteShortcutPlan.chosen(routes, maxPerActivity = 15, staticCount = 3).map { it.id })
        assertEquals(listOf("c", "f"), RouteShortcutPlan.chosen(routes, maxPerActivity = 5, staticCount = 3).map { it.id })
        assertEquals(emptyList<SavedRoute>(), RouteShortcutPlan.chosen(routes, maxPerActivity = 3, staticCount = 3))
    }

    @Test fun `the placeholder route and ad-hoc searches never get a shortcut`() {
        val routes = listOf(route(UNCONFIGURED_ROUTE_ID, "Nessuna"), route("search-1", "Ricerca"), route("home", "Casa"))
        assertEquals(listOf("home"), RouteShortcutPlan.chosen(routes, 15, 3).map { it.id })
    }

    @Test fun `shortcut ids carry the route id and labels fall back to the stations`() {
        assertEquals("route-abc", RouteShortcutPlan.shortcutId("abc"))
        assertEquals("abc", RouteShortcutPlan.routeId("route-abc"))
        assertNull(RouteShortcutPlan.routeId("active-route"))
        assertNull(RouteShortcutPlan.routeId("route-"))
        assertEquals("Casa", RouteShortcutPlan.shortLabel(route("x", " Casa ")))
        assertEquals("Roma Termini → Napoli Centrale", RouteShortcutPlan.shortLabel(route("x", " ")))
        assertEquals("Roma Termini → Napoli Centrale", RouteShortcutPlan.longLabel(route("x", "Casa")))
    }

    @Test fun `pinned shortcuts of deleted routes are disabled, the others kept, static ones untouched`() {
        val routes = listOf(route("a", "Andata"), route("b", "Barletta"))
        val pinned = listOf("route-a", "route-z", "active-route", "route-b")
        assertEquals(listOf("route-z"), RouteShortcutPlan.removed(pinned, routes))
        assertEquals(listOf("route-a", "route-b"), RouteShortcutPlan.kept(pinned, routes))
    }

    @Test fun `the signature changes with what the launcher shows and with the saved set`() {
        val routes = listOf(route("a", "Andata"), route("b", "Barletta"))
        val chosen = RouteShortcutPlan.chosen(routes, 15, 3)
        val renamed = listOf(route("a", "Lavoro"), route("b", "Barletta"))
        assertEquals(RouteShortcutPlan.signature(chosen, routes), RouteShortcutPlan.signature(RouteShortcutPlan.chosen(routes.reversed(), 15, 3), routes.reversed()))
        assertNotEquals(RouteShortcutPlan.signature(chosen, routes), RouteShortcutPlan.signature(RouteShortcutPlan.chosen(renamed, 15, 3), renamed))
        assertNotEquals(RouteShortcutPlan.signature(chosen, routes), RouteShortcutPlan.signature(chosen, routes + route("c", "Casa")))
    }

    @Test fun `routes round trip with their catalogue stations, unreadable entries are skipped`() {
        val routes = listOf(route("a", "Andata", pinned = true), SavedRoute("b", "Lavoro", milano, roma))
        assertEquals(routes, SavedRouteCodec.decodeAll(SavedRouteCodec.encodeAll(routes)))
        assertEquals(emptyList<SavedRoute>(), SavedRouteCodec.decodeAll(null))
        assertEquals(emptyList<SavedRoute>(), SavedRouteCodec.decodeAll("not json"))
        assertEquals(listOf(routes[1]), SavedRouteCodec.decodeAll("[{\"id\":\"x\"}," + SavedRouteCodec.encode(routes[1]) + "]"))
    }

    @Test fun `shortcuts follow the routes only once the station catalogue is read`() {
        assertFalse(RouteShortcutPlan.catalogueReady(listOf(Station("custom:1", "Casa mia", "Roma"))))
        assertTrue(RouteShortcutPlan.catalogueReady(listOf(roma)))
    }

    @Test fun `the app opens the route of a shortcut and declares the tile service`() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val activity = File(root, "app/src/main/java/it/binario/app/MainActivity.kt").readText()
        assertTrue(activity.contains("?: RouteShortcuts.deepLink(intent, applicationContext)"))
        assertTrue(activity.contains("RouteSurfacesEffects(viewModel)"))
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val service = manifest.substring(manifest.indexOf("android:name=\".tile.NextTrainTileService\""), manifest.indexOf("</service>", manifest.indexOf(".tile.NextTrainTileService")))
        assertTrue(service.contains("android:permission=\"android.permission.BIND_QUICK_SETTINGS_TILE\""))
        assertTrue(service.contains("android.service.quicksettings.action.QS_TILE"))
        assertTrue(service.contains("android:icon=\"@drawable/ic_tile_train\""))
        // Both glyphs are plain single-colour vectors, no adaptive icon without a monochrome layer.
        listOf("ic_tile_train", "ic_shortcut_route").forEach { name ->
            val drawable = File(root, "app/src/main/res/drawable/$name.xml").readText()
            assertTrue(name, drawable.contains("<vector") && drawable.split("android:fillColor").size == 2)
        }
    }
}

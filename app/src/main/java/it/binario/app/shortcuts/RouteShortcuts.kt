package it.binario.app.shortcuts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.service.quicksettings.TileService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import it.binario.app.BinarioDeepLink
import it.binario.app.MainActivity
import it.binario.app.R
import it.binario.app.TrainViewModel
import it.binario.app.UNCONFIGURED_ROUTE_ID
import it.binario.app.data.Direction
import it.binario.app.data.SavedRoute
import it.binario.app.data.SavedRouteOrdering
import it.binario.app.data.Station
import it.binario.app.tile.NextTrainTileService
import it.binario.app.tile.NextTrainTileStore
import it.binario.app.widget.StationWidgetCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Pure rules of the launcher shortcuts of the saved routes ("Roma → Napoli" in the long-press menu of the icon). */
object RouteShortcutPlan {
    /** At most this many routes, and never more than the launcher leaves after the static shortcuts. */
    const val MAX_ROUTES = 4
    const val ID_PREFIX = "route-"

    fun shortcutId(routeId: String): String = ID_PREFIX + routeId

    fun routeId(shortcutId: String): String? = shortcutId.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)?.takeIf(String::isNotBlank)

    /** A route the user saved: not the placeholder of a first start nor an ad-hoc search. */
    fun eligible(route: SavedRoute): Boolean = route.id.isNotBlank() && route.id != UNCONFIGURED_ROUTE_ID && !route.id.startsWith("search-")

    /** The routes with a dynamic shortcut: pinned first, in the saved order, at most [MAX_ROUTES] and the room left. */
    fun chosen(routes: List<SavedRoute>, maxPerActivity: Int, staticCount: Int): List<SavedRoute> {
        val room = (maxPerActivity - staticCount).coerceIn(0, MAX_ROUTES)
        return SavedRouteOrdering.ordered(routes.filter(::eligible)).take(room)
    }

    /** What the launcher shows of [chosen], to skip an update that changes nothing. */
    fun signature(chosen: List<SavedRoute>, all: List<SavedRoute>): String =
        chosen.joinToString("\n") { "${it.id}\t${shortLabel(it)}\t${longLabel(it)}" } + "\n#" + all.filter(::eligible).map { it.id }.sorted().joinToString(",")

    fun shortLabel(route: SavedRoute): String = route.name.trim().ifBlank { longLabel(route) }

    fun longLabel(route: SavedRoute): String = route.displayName(Direction.OUTBOUND)

    /** Shortcuts the user pinned to the home screen whose route is no longer saved: they are disabled, not removed. */
    fun removed(pinnedIds: Collection<String>, routes: List<SavedRoute>): List<String> {
        val saved = routes.filter(::eligible).mapTo(hashSetOf()) { it.id }
        return pinnedIds.filter { id -> routeId(id)?.let { it !in saved } == true }.sorted()
    }

    /** Pinned shortcuts whose route is saved (again): enabled, in case an earlier update disabled them. */
    fun kept(pinnedIds: Collection<String>, routes: List<SavedRoute>): List<String> {
        val saved = routes.filter(::eligible).mapTo(hashSetOf()) { it.id }
        return pinnedIds.filter { id -> routeId(id)?.let { it in saved } == true }.sorted()
    }

    /**
     * The saved routes come from the station catalogue, read in the background at every start: until it is read they
     * can be missing, and the shortcuts must not follow that empty list.
     */
    fun catalogueReady(stations: List<Station>): Boolean = stations.any { it.id.startsWith("curated:") }
}

/** One saved route as the shortcuts and the Quick Settings tile keep it: identity, name and catalogue stations. */
object SavedRouteCodec {
    fun encode(route: SavedRoute): JSONObject = JSONObject()
        .put("id", route.id)
        .put("name", route.name)
        .put("origin", StationWidgetCodec.encodeStation(route.outwardOrigin))
        .put("destination", StationWidgetCodec.encodeStation(route.outwardDestination))
        .put("pinned", route.isPinned)

    fun decode(json: JSONObject): SavedRoute? = runCatching {
        val id = json.getString("id").takeIf(String::isNotBlank) ?: return null
        val origin = StationWidgetCodec.decodeStation(json.getString("origin")) ?: return null
        val destination = StationWidgetCodec.decodeStation(json.getString("destination")) ?: return null
        SavedRoute(id, json.getString("name"), origin, destination, isPinned = json.optBoolean("pinned", false))
    }.getOrNull()

    fun encodeAll(routes: List<SavedRoute>): String = JSONArray().apply { routes.forEach { put(encode(it)) } }.toString()

    fun decodeAll(value: String?): List<SavedRoute> = runCatching {
        val array = JSONArray(value ?: return emptyList())
        (0 until minOf(array.length(), SavedRouteOrdering.MAX_ROUTES)).mapNotNull { index -> array.optJSONObject(index)?.let(::decode) }
    }.getOrDefault(emptyList())
}

/**
 * The saved routes a shortcut may open, written by the app itself: an intent only names a route by its id. The evening
 * works alert reads them too, in the background, where the station catalogue the app decodes its routes with is not read.
 */
internal class RouteShortcutStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("binario_route_shortcuts_v1", Context.MODE_PRIVATE)

    fun route(id: String): SavedRoute? = routes().firstOrNull { it.id == id }

    /** Every saved route as the app last wrote it: pinned first, in the saved order. */
    fun routes(): List<SavedRoute> = SavedRouteCodec.decodeAll(preferences.getString(KEY_ROUTES, null))

    fun save(routes: List<SavedRoute>) = preferences.edit { putString(KEY_ROUTES, SavedRouteCodec.encodeAll(routes)) }

    var signature: String?
        get() = preferences.getString(KEY_SIGNATURE, null)
        set(value) = preferences.edit { putString(KEY_SIGNATURE, value) }

    private companion object {
        const val KEY_ROUTES = "routes"
        const val KEY_SIGNATURE = "signature"
    }
}

/**
 * Dynamic launcher shortcuts for up to [RouteShortcutPlan.MAX_ROUTES] saved routes (pinned first): each opens the
 * route board of that route, outbound. Shortcuts the user pinned to the home screen stay, disabled once their route is
 * deleted. Only the route name and its id reach the launcher.
 */
object RouteShortcuts {
    const val ACTION_OPEN_ROUTE = "it.binario.app.action.OPEN_SAVED_ROUTE"
    const val EXTRA_ROUTE_ID = "it.binario.app.extra.SAVED_ROUTE_ID"

    fun openRouteIntent(context: Context, routeId: String): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_ROUTE)
        .putExtra(EXTRA_ROUTE_ID, routeId)

    fun update(context: Context, routes: List<SavedRoute>) {
        val app = context.applicationContext
        val manager = app.getSystemService(ShortcutManager::class.java) ?: return
        val store = RouteShortcutStore(app)
        store.save(routes.filter(RouteShortcutPlan::eligible))
        runCatching {
            val chosen = RouteShortcutPlan.chosen(routes, manager.maxShortcutCountPerActivity, manager.manifestShortcuts.size)
            val signature = RouteShortcutPlan.signature(chosen, routes)
            if (signature != store.signature) {
                val shortcuts = chosen.mapIndexed { rank, route ->
                    ShortcutInfo.Builder(app, RouteShortcutPlan.shortcutId(route.id))
                        .setShortLabel(RouteShortcutPlan.shortLabel(route))
                        .setLongLabel(RouteShortcutPlan.longLabel(route))
                        .setIcon(Icon.createWithResource(app, R.drawable.ic_shortcut_route))
                        .setIntent(openRouteIntent(app, route.id))
                        .setRank(rank)
                        .build()
                }
                // False while Android rate-limits the app: the next change tries again.
                if (manager.setDynamicShortcuts(shortcuts)) store.signature = signature
            }
            val pinned = manager.pinnedShortcuts.map { it.id }
            RouteShortcutPlan.removed(pinned, routes).takeIf { it.isNotEmpty() }?.let { manager.disableShortcuts(it, app.getString(R.string.shortcut_route_removed)) }
            RouteShortcutPlan.kept(pinned, routes).takeIf { it.isNotEmpty() }?.let(manager::enableShortcuts)
        }
    }

    /** The route a shortcut asks for, when it is still saved; the board opens outbound. */
    fun deepLink(intent: Intent?, context: Context): BinarioDeepLink.WidgetRoute? {
        if (intent?.action != ACTION_OPEN_ROUTE) return null
        val id = intent.getStringExtra(EXTRA_ROUTE_ID)?.takeIf(String::isNotBlank) ?: return null
        val route = RouteShortcutStore(context).route(id) ?: return null
        runCatching { context.getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(RouteShortcutPlan.shortcutId(id)) }
        return BinarioDeepLink.WidgetRoute(route, Direction.OUTBOUND)
    }
}

/**
 * Keeps the launcher shortcuts and the Quick Settings tile in step with the app: the saved routes and the active route
 * with its direction, once the station catalogue is read (or after a while if it cannot be).
 */
@Composable
internal fun RouteSurfacesEffects(viewModel: TrainViewModel) {
    val app = LocalContext.current.applicationContext
    val ready = RouteShortcutPlan.catalogueReady(viewModel.stations)
    val routes = viewModel.savedRoutes
    val active = viewModel.activeRoute.takeIf { viewModel.hasActiveRoute }
    val direction = viewModel.direction
    LaunchedEffect(routes, ready) {
        if (!ready) delay(CATALOGUE_WAIT_MILLIS)
        withContext(Dispatchers.IO) { RouteShortcuts.update(app, routes) }
    }
    LaunchedEffect(active, direction, ready) {
        if (!ready) delay(CATALOGUE_WAIT_MILLIS)
        withContext(Dispatchers.IO) { NextTrainTileStore(app).saveActiveRoute(active, direction) }
        runCatching { TileService.requestListeningState(app, ComponentName(app, NextTrainTileService::class.java)) }
    }
}

private const val CATALOGUE_WAIT_MILLIS = 10_000L

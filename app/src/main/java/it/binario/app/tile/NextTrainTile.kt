package it.binario.app.tile

import android.content.Context
import androidx.core.content.edit
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailTime
import it.binario.app.data.SavedRoute
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.shortcuts.SavedRouteCodec
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** The active route of the app as the tile shows it, in its direction. */
data class TileRoute(val route: SavedRoute, val direction: Direction) {
    /** Changes with the route, its stations or the direction: a timetable of another key is not this route's. */
    val key: String get() = listOf(route.id, route.outwardOrigin.id, route.outwardDestination.id, direction.name).joinToString("|")
    val label: String get() = route.displayName(direction)
}

/** One scheduled departure of the offline timetable. */
data class TileDeparture(val departureEpochMillis: Long, val time: String, val number: String, val category: TrainCategory)

/** The departures of the rest of a service day computed for a route, and when. */
data class TileTimetable(val routeKey: String, val serviceDate: LocalDate, val computedAtEpochMillis: Long, val departures: List<TileDeparture>)

/** What the tile shows: [label] always, [subtitle] from Android 10, [description] for TalkBack. */
data class TileDisplay(val label: String, val subtitle: String?, val description: String, val active: Boolean) {
    /** The single line of Android 8 and 9, which have no subtitle. */
    val labelWithoutSubtitle: String get() = if (active || subtitle == null) label else subtitle
}

/** The texts of the tile, resolved from the resources or the test catalogue. */
class TileStrings(
    val locale: Locale,
    /** «Prossimo treno». */
    val title: String,
    /** «Nessuna tratta attiva». */
    val noRoute: String,
    /** «Nessun altro treno oggi». */
    val noTrain: String,
    /** «%1$s · %2$s»: time and train. */
    val departure: String,
    /** «%1$s %2$s»: category and number. */
    val train: String,
    /** «Prossimo treno dall’orario programmato: %1$s alle %2$s, %3$s». */
    val description: String,
    val categoryLabel: (TrainCategory) -> String
)

/**
 * Pure rules of the Quick Settings tile: the next scheduled train of the active route, from the offline timetable the
 * app embeds (never a request from the tile), computed again only for another route or day or once it runs out.
 */
object NextTrainTilePlanner {
    const val MAX_DEPARTURES = 24
    /** A day without more trains is read again at most this often. */
    val RECHECK: Duration = Duration.ofMinutes(30)
    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    fun next(timetable: TileTimetable?, routeKey: String, now: Instant, zone: ZoneId = RailTime.defaultZone): TileDeparture? {
        if (timetable == null || timetable.routeKey != routeKey || timetable.serviceDate != now.atZone(zone).toLocalDate()) return null
        return timetable.departures.filter { it.departureEpochMillis >= now.toEpochMilli() }.minByOrNull { it.departureEpochMillis }
    }

    fun needsRefresh(timetable: TileTimetable?, routeKey: String, now: Instant, zone: ZoneId = RailTime.defaultZone): Boolean {
        if (timetable == null || timetable.routeKey != routeKey || timetable.serviceDate != now.atZone(zone).toLocalDate()) return true
        if (next(timetable, routeKey, now, zone) != null) return false
        return Duration.between(Instant.ofEpochMilli(timetable.computedAtEpochMillis), now) >= RECHECK
    }

    /** The trains of an offline route result still to leave on [now]'s day, earliest first; coaches are left out. */
    fun timetable(routeKey: String, services: List<TrainService>, now: Instant, zone: ZoneId = RailTime.defaultZone): TileTimetable {
        val today = now.atZone(zone).toLocalDate()
        val departures = services.asSequence()
            .filter { !it.scheduledBus }
            .mapNotNull { service ->
                val at = service.scheduledDepartureDateTime?.atZone(zone)
                    ?: runCatching { LocalTime.parse(service.scheduledDeparture.trim()) }.getOrNull()?.atDate(today)?.atZone(zone)
                    ?: return@mapNotNull null
                if (at.toLocalDate() != today || at.toInstant().isBefore(now)) return@mapNotNull null
                TileDeparture(at.toInstant().toEpochMilli(), at.toLocalTime().format(clock), service.number.trim(), service.trainCategory)
            }
            .distinctBy { it.number to it.departureEpochMillis }
            .sortedWith(compareBy<TileDeparture> { it.departureEpochMillis }.thenBy { it.number })
            .take(MAX_DEPARTURES)
            .toList()
        return TileTimetable(routeKey, today, now.toEpochMilli(), departures)
    }

    fun display(route: TileRoute?, timetable: TileTimetable?, now: Instant, strings: TileStrings, zone: ZoneId = RailTime.defaultZone): TileDisplay {
        if (route == null) return TileDisplay(strings.title, strings.noRoute, "${strings.title}. ${strings.noRoute}", active = false)
        val next = next(timetable, route.key, now, zone)
        if (next != null) {
            val train = String.format(strings.locale, strings.train, strings.categoryLabel(next.category), next.number)
            return TileDisplay(
                label = String.format(strings.locale, strings.departure, next.time, train),
                subtitle = route.label,
                description = String.format(strings.locale, strings.description, train, next.time, route.label),
                active = true
            )
        }
        // Still being read for this route and day: only the route; read and empty: no more trains today. A timetable
        // that hit [MAX_DEPARTURES] stopped counting, so once its trains have left it says nothing about the later ones
        // and shows the route until it is read again.
        val known = timetable != null && timetable.routeKey == route.key && timetable.serviceDate == now.atZone(zone).toLocalDate() &&
            timetable.departures.size < MAX_DEPARTURES
        val subtitle = if (known) strings.noTrain else route.label
        return TileDisplay(strings.title, subtitle, "${strings.title}. $subtitle", active = false)
    }
}

object NextTrainTileCodec {
    fun encodeRoute(route: TileRoute): String = SavedRouteCodec.encode(route.route).put("direction", route.direction.name).toString()

    fun decodeRoute(value: String?): TileRoute? = runCatching {
        val json = JSONObject(value ?: return null)
        val route = SavedRouteCodec.decode(json) ?: return null
        TileRoute(route, Direction.valueOf(json.getString("direction")))
    }.getOrNull()

    fun encodeTimetable(timetable: TileTimetable): String = JSONObject()
        .put("route", timetable.routeKey)
        .put("date", timetable.serviceDate.toString())
        .put("computed", timetable.computedAtEpochMillis)
        .put("departures", JSONArray().apply {
            timetable.departures.forEach { put(JSONArray().put(it.departureEpochMillis).put(it.time).put(it.number).put(it.category.name)) }
        })
        .toString()

    fun decodeTimetable(value: String?): TileTimetable? = runCatching {
        val json = JSONObject(value ?: return null)
        val array = json.getJSONArray("departures")
        TileTimetable(
            routeKey = json.getString("route"),
            serviceDate = LocalDate.parse(json.getString("date")),
            computedAtEpochMillis = json.getLong("computed"),
            departures = (0 until minOf(array.length(), NextTrainTilePlanner.MAX_DEPARTURES)).mapNotNull { index ->
                val item = array.optJSONArray(index) ?: return@mapNotNull null
                runCatching {
                    TileDeparture(item.getLong(0), item.getString(1), item.getString(2), TrainCategory.valueOf(item.getString(3)))
                }.getOrNull()
            }
        )
    }.getOrNull()
}

/**
 * What the tile reads without the app running: the active route the app mirrors here (identity, name, catalogue
 * stations, direction) and the scheduled departures last computed for it. Local only, never in the backup.
 */
class NextTrainTileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("binario_next_train_tile_v1", Context.MODE_PRIVATE)

    fun activeRoute(): TileRoute? = NextTrainTileCodec.decodeRoute(preferences.getString(KEY_ROUTE, null))

    /** [route] null when the app has no active route: the tile then says so and keeps no timetable. */
    fun saveActiveRoute(route: SavedRoute?, direction: Direction) {
        val encoded = route?.let { NextTrainTileCodec.encodeRoute(TileRoute(it, direction)) }
        if (encoded == preferences.getString(KEY_ROUTE, null)) return
        preferences.edit {
            if (encoded == null) remove(KEY_ROUTE) else putString(KEY_ROUTE, encoded)
            remove(KEY_TIMETABLE)
        }
    }

    fun timetable(): TileTimetable? = NextTrainTileCodec.decodeTimetable(preferences.getString(KEY_TIMETABLE, null))

    fun saveTimetable(timetable: TileTimetable) = preferences.edit { putString(KEY_TIMETABLE, NextTrainTileCodec.encodeTimetable(timetable)) }

    private companion object {
        const val KEY_ROUTE = "active_route"
        const val KEY_TIMETABLE = "timetable"
    }
}

/**
 * The offline timetable of the tile: the embedded GTFS packs read on the phone (no download, no request), for the
 * route and direction the app has active, from now to the end of the day.
 */
object NextTrainTimetable {
    private val mutex = Mutex()
    private const val TIMEOUT_MILLIS = 20_000L

    suspend fun refresh(context: Context, route: TileRoute, now: Instant = Instant.now()): TileTimetable? = mutex.withLock {
        val store = NextTrainTileStore(context)
        store.timetable()?.takeIf { !NextTrainTilePlanner.needsRefresh(it, route.key, now) }?.let { return@withLock it }
        val app = context.applicationContext
        val openAsset = { path: String -> app.assets.open(path) }
        val adapter = EmbeddedGtfsRouteAdapter(
            openAsset = openAsset,
            indexCacheCapacity = 1,
            stationCatalog = { withContext(Dispatchers.IO) { EmbeddedCuratedRailStationCatalog(openAsset).load() } }
        )
        val query = now.atZone(RailTime.defaultZone).let { JourneyQuery(it.toLocalDate(), it.toLocalTime().withSecond(0).withNano(0)) }
        val result = try {
            withTimeoutOrNull(TIMEOUT_MILLIS) { adapter.loadRoute(route.route, route.direction, query) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val services = (result as? DataResult.Success)?.snapshot?.services ?: emptyList()
        // A route the packs do not cover gives an empty day, read again after RECHECK rather than at every swipe.
        NextTrainTilePlanner.timetable(route.key, services, now).also { timetable ->
            if (store.activeRoute()?.key == route.key) store.saveTimetable(timetable)
        }
    }
}

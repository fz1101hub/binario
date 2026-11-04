package it.binario.app.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import it.binario.app.MainActivity
import it.binario.app.R
import it.binario.app.labelRes
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * «Prossimo treno» in the Quick Settings: the next scheduled train of the app's active route, from the offline
 * timetable (embedded GTFS packs; the tile never sends a request). A tap opens the app.
 */
class NextTrainTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        val store = NextTrainTileStore(this)
        val route = store.activeRoute()
        render(route, store.timetable())
        if (route != null && NextTrainTilePlanner.needsRefresh(store.timetable(), route.key, Instant.now())) {
            scope.launch {
                val timetable = NextTrainTimetable.refresh(applicationContext, route)
                render(route, timetable)
            }
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::openApp) else openApp()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(route: TileRoute?, timetable: TileTimetable?) {
        val tile = qsTile ?: return
        val display = NextTrainTilePlanner.display(route, timetable, Instant.now(), strings())
        tile.state = if (display.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.label = display.label
            tile.subtitle = display.subtitle
        } else {
            tile.label = display.labelWithoutSubtitle
        }
        tile.contentDescription = display.description
        tile.updateTile()
    }

    private fun strings(): TileStrings = TileStrings(
        locale = resources.configuration.locales[0],
        title = getString(R.string.tile_next_train_label),
        noRoute = getString(R.string.tile_next_train_no_route),
        noTrain = getString(R.string.tile_next_train_none),
        departure = getString(R.string.tile_next_train_departure),
        train = getString(R.string.service_category_number),
        description = getString(R.string.tile_next_train_description),
        categoryLabel = { getString(it.labelRes()) }
    )

    /** The app as it was left: it opens on its active route and direction, the ones the tile shows. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, REQUEST_OPEN, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            // The Intent overload is the only one before Android 14, where it still works.
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val REQUEST_OPEN = 0x711E
    }
}

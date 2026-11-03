package it.binario.app.maps

/** The MapView lifecycle callbacks, in the order MapLibre requires them. */
enum class MapViewCall { START, RESUME, PAUSE, STOP, DESTROY }

/** Lifecycle state of whatever hosts the map (activity or composition), already created. */
enum class MapHostState { CREATED, STARTED, RESUMED, DESTROYED }

/**
 * Turns host lifecycle states into the MapView calls that reach them, so `onStart`/`onResume`/`onPause`/`onStop`
 * are always balanced and `onDestroy` runs exactly once, whether the activity is destroyed or the composable
 * leaves the composition first. `onCreate` is called by whoever constructs the view.
 */
internal class MapViewLifecycleTracker {
    var state: MapHostState = MapHostState.CREATED
        private set

    fun moveTo(target: MapHostState): List<MapViewCall> {
        val current = state
        if (current == MapHostState.DESTROYED || target == current) return emptyList()
        val calls = buildList {
            if (target == MapHostState.DESTROYED) {
                if (current == MapHostState.RESUMED) add(MapViewCall.PAUSE)
                if (current != MapHostState.CREATED) add(MapViewCall.STOP)
                add(MapViewCall.DESTROY)
            } else if (target > current) {
                if (current == MapHostState.CREATED) add(MapViewCall.START)
                if (target == MapHostState.RESUMED) add(MapViewCall.RESUME)
            } else {
                if (current == MapHostState.RESUMED) add(MapViewCall.PAUSE)
                if (target == MapHostState.CREATED) add(MapViewCall.STOP)
            }
        }
        state = target
        return calls
    }

    /** Leaving the composition: pause and stop as needed, then destroy. */
    fun dispose(): List<MapViewCall> = moveTo(MapHostState.DESTROYED)

    companion object {
        /** `ComponentCallbacks2` levels from TRIM_MEMORY_BACKGROUND up: the process is cached, release map memory. */
        private const val TRIM_MEMORY_BACKGROUND = 40

        fun shouldReleaseMemory(trimLevel: Int): Boolean = trimLevel >= TRIM_MEMORY_BACKGROUND
    }
}

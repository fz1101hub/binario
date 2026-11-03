package it.binario.app.maps

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import it.binario.app.R
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.coroutines.resume

/** One point drawn on a map. Markers that are not [tappable] (the user's position) never answer a tap. */
data class OpenMapMarker(val point: GeoPoint, val style: OpenMapMarkerStyle, val tappable: Boolean = true)

/** Everything a map shows; a new value updates the drawn sources without reloading the base map. */
data class OpenMapContent(
    val markers: List<OpenMapMarker>,
    val line: List<GeoPoint> = emptyList(),
    val lineColor: Int = 0,
    val camera: OpenMapCamera?
)

private enum class OpenMapStatus { LOADING, READY, FAILED }

private const val LOG_TAG = "BinarioMaps"
private const val MIN_ZOOM = 4.0
private const val MAX_ZOOM = 17.0
private const val FIT_INITIAL_ZOOM = 8.0

/**
 * A MapLibre map with an OpenFreeMap base layer. The MapView follows the host lifecycle, is destroyed when it
 * leaves the composition and saves its camera with the activity state. A failed style load (offline, host
 * unreachable) shows a message and a retry instead of an empty box; the caller always shows the same
 * information as text next to the map.
 */
@Composable
internal fun OpenMap(
    content: OpenMapContent,
    contentDescription: String,
    onMarkerTap: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val styleUrl = OpenFreeMapStyle.styleUrl(darkTheme = colorScheme.surface.luminance() < 0.5f)
    val savedState = rememberSaveable(saver = OpenMapSavedState.Saver) { OpenMapSavedState(restored = null) }
    val loadingColor = colorScheme.surfaceVariant.toArgb()
    // Retry builds a new MapView: the renderer ignores a second load of the style URL that just failed.
    var attempt by remember { mutableIntStateOf(0) }
    Box(modifier.clip(RoundedCornerShape(12.dp))) {
        key(attempt) {
            val mapView = remember {
                val restored = savedState.restored.takeIf { attempt == 0 }
                runCatching { OpenMaps.createMapView(context, loadingColor, content.camera, restored) }
                    .onFailure { Log.w(LOG_TAG, "MapLibre map view unavailable", it) }
                    .getOrNull()
            }
            if (mapView == null) {
                // The native renderer could not start on this device: retrying cannot help.
                OpenMapStatusMessage(stringResource(R.string.map_load_failed), onRetry = null)
            } else {
                OpenMapBody(
                    mapView = mapView,
                    restored = savedState.restored != null && attempt == 0,
                    content = content,
                    contentDescription = contentDescription,
                    onMarkerTap = onMarkerTap,
                    styleUrl = styleUrl,
                    savedState = savedState,
                    onRetry = { attempt += 1 }
                )
            }
        }
    }
}

@Composable
private fun OpenMapBody(
    mapView: MapView,
    restored: Boolean,
    content: OpenMapContent,
    contentDescription: String,
    onMarkerTap: (Int) -> Unit,
    styleUrl: String,
    savedState: OpenMapSavedState,
    onRetry: () -> Unit
) {
    MapViewLifecycleBinding(mapView, savedState)
    var status by remember { mutableStateOf(OpenMapStatus.LOADING) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // A restored MapView brings back the camera the user left; fit only when the content asks for a new one.
    var appliedCamera by remember { mutableStateOf(if (restored) content.camera else null) }
    val currentContent by rememberUpdatedState(content)
    val currentOnMarkerTap by rememberUpdatedState(onMarkerTap)
    val density = LocalDensity.current
    val tapRadiusPx = with(density) { 24.dp.toPx() }
    val fitPaddingPx = with(density) { 40.dp.roundToPx() }

    DisposableEffect(mapView) {
        var disposed = false
        val failure = MapView.OnDidFailLoadingMapListener { message ->
            Log.w(LOG_TAG, "Map style failed to load: $message")
            if (!disposed) status = OpenMapStatus.FAILED
        }
        mapView.addOnDidFailLoadingMapListener(failure)
        mapView.getMapAsync { ready ->
            if (disposed || mapView.isDestroyed) return@getMapAsync
            ready.uiSettings.apply {
                isAttributionEnabled = true
                isLogoEnabled = true
                isCompassEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
            }
            map = ready
        }
        onDispose {
            disposed = true
            mapView.removeOnDidFailLoadingMapListener(failure)
        }
    }

    DisposableEffect(map) {
        val ready = map ?: return@DisposableEffect onDispose { }
        val click = MapLibreMap.OnMapClickListener { latLng ->
            val projection = ready.projection
            val tap = projection.toScreenLocation(latLng)
            val candidates = currentContent.markers.map { marker ->
                if (!marker.tappable) null
                else projection.toScreenLocation(LatLng(marker.point.latitude, marker.point.longitude)).let { ScreenPoint(it.x, it.y) }
            }
            val hit = OpenMapHitTest.nearest(ScreenPoint(tap.x, tap.y), candidates, tapRadiusPx)
            hit?.let(currentOnMarkerTap)
            hit != null
        }
        ready.addOnMapClickListener(click)
        onDispose { ready.removeOnMapClickListener(click) }
    }

    LaunchedEffect(map, styleUrl) {
        val ready = map ?: return@LaunchedEffect
        style = null
        status = OpenMapStatus.LOADING
        ready.setStyle(Style.Builder().fromUri(styleUrl)) { loaded ->
            if (mapView.isDestroyed) return@setStyle
            style = loaded
            status = OpenMapStatus.READY
        }
    }

    LaunchedEffect(style, content) {
        val loaded = style ?: return@LaunchedEffect
        runCatching { OpenMapLayers.apply(loaded, content) }
            .onFailure { Log.w(LOG_TAG, "Map overlay not applied", it) }
    }

    LaunchedEffect(map, content.camera) {
        val ready = map ?: return@LaunchedEffect
        val camera = content.camera ?: return@LaunchedEffect
        if (camera == appliedCamera) return@LaunchedEffect
        mapView.awaitSize()
        if (mapView.isDestroyed) return@LaunchedEffect
        val padding = fitPaddingPx.coerceAtMost(minOf(mapView.width, mapView.height) / 4)
        runCatching { ready.moveCamera(camera.toCameraUpdate(padding)) }
            .onFailure { Log.w(LOG_TAG, "Map camera not applied", it) }
        appliedCamera = camera
    }

    AndroidView(
        factory = {
            (mapView.parent as? ViewGroup)?.removeView(mapView)
            mapView
        },
        modifier = Modifier.fillMaxSize(),
        // Replaces MapLibre's English description on the view TalkBack focuses; the card lists the same data.
        update = { view -> view.contentDescription = contentDescription }
    )
    when (status) {
        OpenMapStatus.LOADING -> OpenMapStatusMessage(stringResource(R.string.map_loading), onRetry = null, compact = true)
        OpenMapStatus.FAILED -> OpenMapStatusMessage(stringResource(R.string.map_load_failed), onRetry = onRetry)
        OpenMapStatus.READY -> Unit
    }
}

@Composable
private fun OpenMapStatusMessage(message: String, onRetry: (() -> Unit)?, compact: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = if (compact) Alignment.TopCenter else Alignment.Center) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.map_retry)) }
            }
        }
    }
}

/** Forwards the host lifecycle and memory pressure to the MapView; destroys it when it leaves the composition. */
@Composable
private fun MapViewLifecycleBinding(mapView: MapView, savedState: OpenMapSavedState) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val tracker = remember(mapView) { MapViewLifecycleTracker() }
    DisposableEffect(mapView) {
        savedState.mapView = mapView
        val memory = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                if (!mapView.isDestroyed) mapView.onLowMemory()
            }

            override fun onTrimMemory(level: Int) {
                if (MapViewLifecycleTracker.shouldReleaseMemory(level) && !mapView.isDestroyed) mapView.onLowMemory()
            }
        }
        context.registerComponentCallbacks(memory)
        onDispose {
            context.unregisterComponentCallbacks(memory)
            savedState.mapView = null
            tracker.dispose().forEach(mapView::perform)
        }
    }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            event.toHostState()?.let { target -> tracker.moveTo(target).forEach(mapView::perform) }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

private fun Lifecycle.Event.toHostState(): MapHostState? = when (this) {
    Lifecycle.Event.ON_START, Lifecycle.Event.ON_PAUSE -> MapHostState.STARTED
    Lifecycle.Event.ON_RESUME -> MapHostState.RESUMED
    Lifecycle.Event.ON_STOP -> MapHostState.CREATED
    Lifecycle.Event.ON_DESTROY -> MapHostState.DESTROYED
    else -> null
}

private fun MapView.perform(call: MapViewCall) {
    when (call) {
        MapViewCall.START -> onStart()
        MapViewCall.RESUME -> onResume()
        MapViewCall.PAUSE -> onPause()
        MapViewCall.STOP -> onStop()
        MapViewCall.DESTROY -> onDestroy()
    }
}

/** Holds the MapView's own saved state (camera, gestures) across activity recreation. */
internal class OpenMapSavedState(val restored: Bundle?) {
    var mapView: MapView? = null

    companion object {
        val Saver: Saver<OpenMapSavedState, Bundle> = Saver(
            save = { holder ->
                holder.mapView?.takeUnless { it.isDestroyed }?.let { view -> Bundle().also(view::onSaveInstanceState) }
            },
            restore = { bundle ->
                // MapLibre reads its own Parcelables (camera position) back from this nested bundle.
                bundle.classLoader = MapView::class.java.classLoader
                OpenMapSavedState(bundle)
            }
        )
    }
}

internal object OpenMaps {
    /** MapLibre is initialised once per process, on first use: the app has no map until a card opens one. */
    fun ensureInitialized(context: Context) {
        if (!MapLibre.hasInstance()) MapLibre.getInstance(context.applicationContext)
    }

    fun createMapView(context: Context, loadingColor: Int, camera: OpenMapCamera?, savedState: Bundle?): MapView {
        ensureInitialized(context)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .foregroundLoadColor(loadingColor)
            .attributionEnabled(true)
            .logoEnabled(true)
            .compassEnabled(false)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .minZoomPreference(MIN_ZOOM)
            .maxZoomPreference(MAX_ZOOM)
        camera?.initialPosition()?.let(options::camera)
        return ScrollAwareMapView(context, options).apply { onCreate(savedState) }
    }
}

/** A drag that starts on the map moves the map, not the list around it. */
internal class ScrollAwareMapView : MapView {
    constructor(context: Context) : super(context)
    constructor(context: Context, options: MapLibreMapOptions) : super(context, options)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(event)
    }
}

/** The two GeoJSON sources Binario draws above the base map: a line and the markers. */
internal object OpenMapLayers {
    private const val LINE_SOURCE = "binario-line"
    private const val LINE_LAYER = "binario-line-layer"
    private const val MARKER_SOURCE = "binario-markers"
    private const val MARKER_LAYER = "binario-markers-layer"

    fun apply(style: Style, content: OpenMapContent) {
        if (!style.isFullyLoaded) return
        val lineJson = OpenMapGeoJson.line(content.line)
        val markerJson = OpenMapGeoJson.markers(content.markers.map { it.point to it.style })
        val lineSource = style.getSourceAs<GeoJsonSource>(LINE_SOURCE)
        if (lineSource == null) {
            style.addSource(GeoJsonSource(LINE_SOURCE, lineJson))
            style.addLayer(
                LineLayer(LINE_LAYER, LINE_SOURCE).withProperties(
                    PropertyFactory.lineColor(content.lineColor),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        } else {
            lineSource.setGeoJson(lineJson)
        }
        val markerSource = style.getSourceAs<GeoJsonSource>(MARKER_SOURCE)
        if (markerSource == null) {
            style.addSource(GeoJsonSource(MARKER_SOURCE, markerJson))
            style.addLayer(
                CircleLayer(MARKER_LAYER, MARKER_SOURCE).withProperties(
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.circleRadius(Expression.toNumber(Expression.get("radius"))),
                    PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("strokeColor"))),
                    PropertyFactory.circleStrokeWidth(Expression.toNumber(Expression.get("strokeWidth"))),
                    PropertyFactory.circleOpacity(Expression.toNumber(Expression.get("opacity")))
                )
            )
        } else {
            markerSource.setGeoJson(markerJson)
        }
    }
}

private fun OpenMapCamera.initialPosition(): CameraPosition = when (this) {
    is OpenMapCamera.Center -> CameraPosition.Builder()
        .target(LatLng(point.latitude, point.longitude))
        .zoom(zoom)
        .build()
    is OpenMapCamera.Fit -> CameraPosition.Builder()
        .target(LatLng((bounds.north + bounds.south) / 2, (bounds.east + bounds.west) / 2))
        .zoom(FIT_INITIAL_ZOOM)
        .build()
}

private fun OpenMapCamera.toCameraUpdate(paddingPx: Int): CameraUpdate = when (this) {
    is OpenMapCamera.Center -> CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), zoom)
    is OpenMapCamera.Fit -> CameraUpdateFactory.newLatLngBounds(
        LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west),
        paddingPx
    )
}

/** Fitting bounds needs the view's size: wait for the first layout that gives it one. */
private suspend fun View.awaitSize() {
    if (width > 0 && height > 0) return
    suspendCancellableCoroutine { continuation ->
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                if (view.width > 0 && view.height > 0) {
                    view.removeOnLayoutChangeListener(this)
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        addOnLayoutChangeListener(listener)
        continuation.invokeOnCancellation { removeOnLayoutChangeListener(listener) }
    }
}

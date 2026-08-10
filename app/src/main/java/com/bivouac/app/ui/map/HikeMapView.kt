package com.bivouac.app.ui.map

import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bivouac.app.R
import com.bivouac.app.data.gpx.GeoMath
import com.bivouac.app.data.gpx.TrackGeometry
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.TrackPoint
import kotlin.math.sqrt
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polyline

private const val DEFAULT_CENTER_LAT = 46.6
private const val DEFAULT_CENTER_LON = 2.5
private const val DEFAULT_ZOOM = 5.5
private const val MIN_ZOOM = 3.0
private const val MAX_ZOOM = 19.0

// Fraction of the shorter map-view side kept as empty margin around the track on auto-fit,
// so the track never touches the screen edges.
private const val FIT_MARGIN_RATIO = 0.05

// Two GPX points closer than this are considered the same physical spot, i.e. a loop hike.
private const val LOOP_THRESHOLD_METERS = 50.0

private const val SINGLE_POINT_SPAN_DEGREES = 0.01

// How close (in dp) a tap needs to land next to the track line to register as "add a point here".
private const val TRACK_TAP_TOLERANCE_DP = 24f

/**
 * Displays a [HikeTrack] on an offline-capable OSM map (osmdroid): the track as a dashed blue
 * line with a white casing for contrast, start/finish pin markers, bivouac markers, and an
 * automatic fit to the track's extent the first time a given track is drawn. Standard
 * pinch-zoom/pan gestures are enabled.
 *
 * Tapping near the track adds a bivouac point there; dragging an existing bivouac marker snaps
 * it to the nearest track point in real time. Removing a point is handled from the segments
 * table instead (a "long press without moving" gesture is unreliable on a real touchscreen).
 */
@Composable
fun HikeMapView(
    track: HikeTrack?,
    bivouacPoints: List<BivouacPoint>,
    selectedLayer: MapLayer,
    recenterSignal: Int,
    // How much of the map's own height, from the top, isn't covered by the sheet right now.
    // Int.MAX_VALUE means "unknown/uncovered" — fitting then behaves exactly as before.
    visibleHeightPx: Int,
    onTrackTapped: (trackPointIndex: Int) -> Unit,
    onBivouacMoved: (id: String, trackPointIndex: Int) -> Unit,
    onBivouacDragPreview: (id: String, trackPointIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(selectedLayer.tileSource)
            setMultiTouchControls(true)
            setMinZoomLevel(MIN_ZOOM)
            setMaxZoomLevel(MAX_ZOOM)
            controller.setZoom(DEFAULT_ZOOM)
            controller.setCenter(GeoPoint(DEFAULT_CENTER_LAT, DEFAULT_CENTER_LON))
        }
    }

    // Only re-fit the camera when the track itself changes (a new import) or the user taps the
    // recenter button, not on every bivouac point edit, which would be a jarring reset while the
    // user is placing points.
    val lastFittedTrack = remember { mutableStateOf<HikeTrack?>(null) }
    val lastRecenterSignal = remember { mutableStateOf(recenterSignal) }
    val lastLayer = remember { mutableStateOf(selectedLayer) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        // osmdroid's MapView doesn't always re-layout its internal drawing surface promptly
        // when Compose shrinks it (e.g. sibling text growing taller after a track loads),
        // which left stale tile content painted outside its allocated bounds. Force clipping
        // at the Compose layer so the map can never bleed over neighboring content.
        modifier = modifier.fillMaxSize().clipToBounds(),
        factory = { mapView },
        update = { view ->
            if (selectedLayer != lastLayer.value) {
                view.setTileSource(selectedLayer.tileSource)
                lastLayer.value = selectedLayer
            }
            val shouldFit = track !== lastFittedTrack.value || recenterSignal != lastRecenterSignal.value
            renderTrack(view, track, bivouacPoints, shouldFit, visibleHeightPx, onTrackTapped, onBivouacMoved, onBivouacDragPreview)
            lastFittedTrack.value = track
            lastRecenterSignal.value = recenterSignal
        },
    )
}

private fun renderTrack(
    mapView: MapView,
    track: HikeTrack?,
    bivouacPoints: List<BivouacPoint>,
    shouldFit: Boolean,
    visibleHeightPx: Int,
    onTrackTapped: (Int) -> Unit,
    onBivouacMoved: (String, Int) -> Unit,
    onBivouacDragPreview: (String, Int) -> Unit,
) {
    mapView.overlays.clear()

    if (track == null || track.points.isEmpty()) {
        mapView.invalidate()
        return
    }

    val context = mapView.context
    val density = context.resources.displayMetrics.density
    val points = track.points
    val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }

    val outline = Polyline(mapView).apply {
        setPoints(geoPoints)
        paint.apply {
            color = ContextCompat.getColor(context, R.color.track_line_outline)
            style = Paint.Style.STROKE
            strokeWidth = 10f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            pathEffect = null
        }
    }
    val dashedTrack = Polyline(mapView).apply {
        setPoints(geoPoints)
        paint.apply {
            color = ContextCompat.getColor(context, R.color.track_line)
            style = Paint.Style.STROKE
            strokeWidth = 4f * density
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.ROUND
            pathEffect = DashPathEffect(floatArrayOf(14f * density, 10f * density), 0f)
        }
    }

    mapView.overlays.add(outline)
    mapView.overlays.add(dashedTrack)
    mapView.overlays.add(trackTapOverlay(mapView, points, geoPoints, density, onTrackTapped))

    bivouacPoints.forEach { bivouac ->
        mapView.overlays.add(bivouacMarker(mapView, points, geoPoints, bivouac, onBivouacMoved, onBivouacDragPreview))
    }

    mapView.overlays.addAll(endpointMarkers(mapView, points))

    if (shouldFit) {
        fitToTrack(mapView, geoPoints, visibleHeightPx)
    }
    mapView.invalidate()
}

private fun trackTapOverlay(
    mapView: MapView,
    points: List<TrackPoint>,
    geoPoints: List<GeoPoint>,
    density: Float,
    onTrackTapped: (Int) -> Unit,
): MapEventsOverlay {
    val toleranceEnd = TRACK_TAP_TOLERANCE_DP * density
    return MapEventsOverlay(object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
            val nearestIndex = TrackGeometry.nearestPointIndex(points, p.latitude, p.longitude)
            val tapPx = mapView.projection.toPixels(p, null)
            val nearestPx = mapView.projection.toPixels(geoPoints[nearestIndex], null)
            val dx = (tapPx.x - nearestPx.x).toDouble()
            val dy = (tapPx.y - nearestPx.y).toDouble()
            if (sqrt(dx * dx + dy * dy) <= toleranceEnd) {
                onTrackTapped(nearestIndex)
                return true
            }
            return false
        }

        override fun longPressHelper(p: GeoPoint): Boolean = false
    })
}

private fun bivouacMarker(
    mapView: MapView,
    points: List<TrackPoint>,
    geoPoints: List<GeoPoint>,
    bivouac: BivouacPoint,
    onBivouacMoved: (String, Int) -> Unit,
    onBivouacDragPreview: (String, Int) -> Unit,
): Marker {
    val marker = Marker(mapView)
    marker.position = geoPoints[bivouac.trackPointIndex]
    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    marker.icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_marker_bivouac)
    marker.title = "Bivouac"
    marker.isDraggable = true

    var lastPreviewIndex = bivouac.trackPointIndex
    marker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
        override fun onMarkerDragStart(marker: Marker) = Unit

        override fun onMarkerDrag(marker: Marker) {
            val current = marker.position
            val nearestIndex = TrackGeometry.nearestPointIndex(points, current.latitude, current.longitude)
            marker.position = geoPoints[nearestIndex]
            mapView.invalidate()
            // Only push a preview update to the (Compose-driven) table when the snapped point
            // actually changes, not on every raw touch-move frame.
            if (nearestIndex != lastPreviewIndex) {
                lastPreviewIndex = nearestIndex
                onBivouacDragPreview(bivouac.id, nearestIndex)
            }
        }

        override fun onMarkerDragEnd(marker: Marker) {
            val nearestIndex = TrackGeometry.nearestPointIndex(points, marker.position.latitude, marker.position.longitude)
            onBivouacMoved(bivouac.id, nearestIndex)
        }
    })
    return marker
}

private fun endpointMarkers(mapView: MapView, points: List<TrackPoint>): List<Marker> {
    val first = points.first()
    val last = points.last()
    val isLoop = GeoMath.haversineMeters(
        first.latitude, first.longitude,
        last.latitude, last.longitude,
    ) < LOOP_THRESHOLD_METERS

    return if (isLoop) {
        listOf(marker(mapView, first, R.drawable.ic_marker_start_finish, "Départ / Arrivée"))
    } else {
        listOf(
            marker(mapView, first, R.drawable.ic_marker_start, "Départ"),
            marker(mapView, last, R.drawable.ic_marker_finish, "Arrivée"),
        )
    }
}

private fun marker(mapView: MapView, point: TrackPoint, iconRes: Int, label: String): Marker =
    Marker(mapView).apply {
        position = GeoPoint(point.latitude, point.longitude)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        icon = ContextCompat.getDrawable(mapView.context, iconRes)
        title = label
    }

private fun fitToTrack(mapView: MapView, points: List<GeoPoint>, visibleHeightPx: Int) {
    val boundingBox = if (points.size == 1) {
        val p = points.first()
        BoundingBox(
            p.latitude + SINGLE_POINT_SPAN_DEGREES,
            p.longitude + SINGLE_POINT_SPAN_DEGREES,
            p.latitude - SINGLE_POINT_SPAN_DEGREES,
            p.longitude - SINGLE_POINT_SPAN_DEGREES,
        )
    } else {
        BoundingBox.fromGeoPoints(points)
    }

    fun applyFit() {
        val borderPx = (minOf(mapView.width, mapView.height) * FIT_MARGIN_RATIO).toInt()
        mapView.zoomToBoundingBox(boundingBox, false, borderPx)

        // zoomToBoundingBox only fits within the full view, with no way to bias the fit towards
        // a sub-rectangle — osmdroid has no asymmetric-border variant. So instead: let it fit and
        // center normally, then re-center on whichever point is currently at the middle of the
        // portion the sheet doesn't cover, shifting the fitted track up into view.
        val hiddenHeightPx = mapView.height - visibleHeightPx.coerceAtMost(mapView.height)
        if (hiddenHeightPx > 0) {
            val shiftPx = hiddenHeightPx / 2
            val recenterOn = mapView.projection.fromPixels(mapView.width / 2, mapView.height / 2 + shiftPx)
            mapView.controller.setCenter(GeoPoint(recenterOn.latitude, recenterOn.longitude))
        }
    }

    if (mapView.width > 0 && mapView.height > 0) {
        applyFit()
    } else {
        mapView.addOnFirstLayoutListener { _, _, _, _, _ -> applyFit() }
    }
}

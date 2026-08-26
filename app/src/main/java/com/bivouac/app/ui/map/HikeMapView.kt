package com.bivouac.app.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Point
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bivouac.app.R
import com.bivouac.app.data.gpx.TrackGeometry
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.DayJunctions
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.ui.components.formatGroupedInt
import com.bivouac.app.ui.components.formatKm1
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.infowindow.InfoWindow

// Already roughly France's geographic centroid — the empty-state view previously looked
// "centered on all of Europe" only because of the wide default zoom below, not the center point.
private const val DEFAULT_CENTER_LAT = 46.6
private const val DEFAULT_CENTER_LON = 2.5
// Tighter framing for a French locale; a generic wider view otherwise. A real per-country default
// isn't worth building before the app is actually localized — cf. i18n backlog item — a simple
// FR/non-FR split covers the only locale that matters today.
private const val DEFAULT_ZOOM_FRANCE = 6.3
private const val DEFAULT_ZOOM_WORLD = 5.5
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

// Direction arrows along loop tracks (BIV-46): two on short loops, up to four on longer ones.
// Equal fractions of cumulative distance keep them visually regular whatever the GPX sampling.
private const val ARROW_TARGET_SPACING_METERS = 2500.0
private const val ARROW_MIN_COUNT = 2
private const val ARROW_MAX_COUNT = 4
// How far to look before/after an arrow's position when computing its bearing — wide enough that
// small local zigzags (switchbacks, GPS jitter) don't flip an arrow against the trace's actual
// macro direction of travel at that point.
private const val ARROW_BEARING_WINDOW_METERS = 150.0

// Cursor bubble (BIV-52): lifted above the pin by roughly its rendered height, so the bubble
// doesn't sit on top of (and block dragging) the marker it's describing.
private const val CURSOR_MARKER_HEIGHT_DP = 40f
private const val CURSOR_HIT_RADIUS_DP = 28f

// Journal-only (BIV-48): one track among several shown together in the multi-trace overview.
data class ColoredTrack(val id: String, val track: HikeTrack, val color: Color)

// Compose updates HikeMapView whenever the Journal cursor index changes. While osmdroid owns an
// active drag, rebuilding all overlays would replace the marker under the finger and interrupt
// the gesture. This tiny bridge lets renderTrack leave the live overlay tree untouched until the
// finger is released.
private class CursorDragState(var isDragging: Boolean = false)

// osmdroid's CopyrightOverlay draws its notice with a single Canvas.drawText call, which never
// wraps — fine for Mapnik's short "© OpenStreetMap contributors" but runs off the right edge of
// the screen with OpenTopo's much longer bilingual notice (BIV-56). This mirrors
// CopyrightOverlay's behavior (re-reads the active tile source's notice every frame, so layer
// switches update it automatically; same top-left anchor via xOffset/yOffset) but lays the text
// out with StaticLayout so it wraps to the map's width instead of overflowing it.
private class WrappingCopyrightOverlay(context: Context) : Overlay() {
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * context.resources.displayMetrics.density
    }
    var xOffset = 0
    var yOffset = 0

    fun setOffset(xOffset: Int, yOffset: Int) {
        this.xOffset = xOffset
        this.yOffset = yOffset
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val tileSource = mapView.tileProvider.tileSource
        val notice = tileSource?.copyrightNotice
        if (notice.isNullOrEmpty()) return
        // Esri's satellite imagery is dark/busy edge-to-edge — black text (fine on the other two,
        // paler layers) all but disappears on it. White reads reliably on aerial photography.
        textPaint.color = if (tileSource?.name() == MapLayer.SATELLITE.tileSource.name()) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.BLACK
        }
        val maxWidth = mapView.width - xOffset * 2
        if (maxWidth <= 0) return
        val layout = StaticLayout.Builder
            .obtain(notice, 0, notice.length, textPaint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        canvas.save()
        canvas.translate(xOffset.toFloat(), yOffset.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }
}

/**
 * Displays a [HikeTrack] on an offline-capable OSM map (osmdroid): the track as a solid blue
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
    // Journal-only (BIV-52): a single "point du parcours" cursor, distinct from bivouacs — no
    // preview/commit split like bivouacs get, every intermediate drag position is already final
    // since nothing about the cursor is ever persisted.
    // Journal (constat E) : les bivouacs d'une trace importée sont des faits passés, déduits des
    // coupures entre fichiers. Affichés pour situer les nuits, jamais déplaçables.
    bivouacsReadOnly: Boolean = false,
    // Journal : dernier point de chaque jour qui s'achève, sur une sortie de plusieurs fichiers.
    // Vide en Planification, où la trace est d'un seul tenant.
    dayBoundaryIndices: List<Int> = emptyList(),
    cursorIndex: Int? = null,
    onCursorChanged: (trackPointIndex: Int) -> Unit = {},
    // Journal-only (BIV-48): when non-empty, overrides single-track rendering entirely — a
    // contemplative multi-trace overview, no tap/drag interactions, no bivouacs/arrows/cursor.
    multiTracks: List<ColoredTrack> = emptyList(),
    highlightedTrackId: String? = null,
    onTraceTapped: (id: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(selectedLayer.tileSource)
            setMultiTouchControls(true)
            setMinZoomLevel(MIN_ZOOM)
            setMaxZoomLevel(MAX_ZOOM)
            val defaultZoom = if (Locale.getDefault().country == "FR") DEFAULT_ZOOM_FRANCE else DEFAULT_ZOOM_WORLD
            controller.setZoom(defaultZoom)
            controller.setCenter(GeoPoint(DEFAULT_CENTER_LAT, DEFAULT_CENTER_LON))
        }
    }
    val cursorInfoWindow = remember(mapView) { CursorInfoWindow(mapView) }
    val cursorDragState = remember(mapView) { CursorDragState() }
    // Esri's free tile access requires on-screen attribution (BIV-56). WrappingCopyrightOverlay
    // reads the active tile source's copyright notice on every draw, so it tracks layer switches
    // (Standard, Randonnée, Satellite) automatically without any extra plumbing here, and wraps
    // it to the map's width so OpenTopo's long notice doesn't run off-screen. Anchored top-left:
    // the bottom is a moving target (the detail sheet's peek height varies, drags open further),
    // while the top-left corner is free of persistent chrome on every screen HikeMapView is used
    // from (top-right only carries the layer/recenter controls).
    val copyrightOverlay = remember(mapView) { WrappingCopyrightOverlay(context) }

    // Only re-fit the camera when the track itself changes (a new import) or the user taps the
    // recenter button, not on every bivouac point edit, which would be a jarring reset while the
    // user is placing points.
    val lastFittedTrack = remember { mutableStateOf<HikeTrack?>(null) }
    val lastFittedMultiTracks = remember { mutableStateOf<List<ColoredTrack>>(emptyList()) }
    val lastRecenterSignal = remember { mutableStateOf(recenterSignal) }
    val lastLayer = remember { mutableStateOf(selectedLayer) }
    // Set whenever a fit just happened with visibleHeightPx still unknown (drawer not yet
    // measured) — true until exactly one corrective re-fit runs once the real height is known, so
    // opening a trace never leaves the map framed for the wrong, pre-measurement viewport. Never
    // triggers again afterwards, so it never fights a user's own pan/zoom.
    val pendingHeightCorrection = remember { mutableStateOf(false) }

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

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            // osmdroid's MapView doesn't always re-layout its internal drawing surface promptly
            // when Compose shrinks it (e.g. sibling text growing taller after a track loads),
            // which left stale tile content painted outside its allocated bounds. Force clipping
            // at the Compose layer so the map can never bleed over neighboring content.
            modifier = Modifier.fillMaxSize().clipToBounds(),
            factory = { mapView },
            update = { view ->
                if (selectedLayer != lastLayer.value) {
                    view.setTileSource(selectedLayer.tileSource)
                    lastLayer.value = selectedLayer
                }
                val trackChanged = if (multiTracks.isNotEmpty()) {
                    multiTracks !== lastFittedMultiTracks.value
                } else {
                    track !== lastFittedTrack.value
                }
                val recenterRequested = recenterSignal != lastRecenterSignal.value
                val heightJustBecameKnown = pendingHeightCorrection.value && visibleHeightPx != Int.MAX_VALUE
                val shouldFit = trackChanged || recenterRequested || heightJustBecameKnown
                renderTrack(
                    view, track, bivouacPoints, bivouacsReadOnly, dayBoundaryIndices, shouldFit, visibleHeightPx,
                    onTrackTapped, onBivouacMoved, onBivouacDragPreview,
                    cursorIndex, onCursorChanged, cursorInfoWindow, cursorDragState,
                    multiTracks, highlightedTrackId, onTraceTapped, copyrightOverlay,
                )
                pendingHeightCorrection.value = when {
                    trackChanged || recenterRequested -> visibleHeightPx == Int.MAX_VALUE
                    heightJustBecameKnown -> false
                    else -> pendingHeightCorrection.value
                }
                lastFittedTrack.value = track
                lastFittedMultiTracks.value = multiTracks
                lastRecenterSignal.value = recenterSignal
            },
        )
        // Esri requires a clickable link on "Esri" in its attribution (BIV-63). CopyrightOverlay/
        // WrappingCopyrightOverlay draw on a plain Canvas with no touch target, so rather than
        // hand-computing a tap hitbox against StaticLayout's line metrics, this Satellite-only
        // notice is rendered as a real Compose text on top of the map instead — the canvas overlay
        // steps aside for this one layer (see the isSatelliteLayer check in renderTrack) and this
        // takes over its notice entirely, wrap included.
        if (selectedLayer == MapLayer.SATELLITE) {
            EsriAttributionLink(Modifier.align(Alignment.TopStart))
        }
    }
}

@Composable
private fun EsriAttributionLink(modifier: Modifier = Modifier) {
    val notice = MapLayer.SATELLITE.tileSource.copyrightNotice ?: return
    val linkStart = notice.indexOf("Esri")
    if (linkStart < 0) return
    val linkEnd = linkStart + "Esri".length
    val annotated = buildAnnotatedString {
        append(notice.substring(0, linkStart))
        withLink(
            LinkAnnotation.Url(
                url = "https://www.esri.com",
                styles = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline)),
            ),
        ) {
            append(notice.substring(linkStart, linkEnd))
        }
        append(notice.substring(linkEnd))
    }
    // Same white-on-dark-imagery reasoning as WrappingCopyrightOverlay's Satellite branch, and the
    // same 8dp margin/status-bar clearance, so this reads as a drop-in replacement for that overlay
    // rather than a visually distinct addition.
    Text(
        text = annotated,
        color = Color.White,
        fontSize = 10.sp,
        style = LocalTextStyle.current,
        modifier = modifier
            .statusBarsPadding()
            .padding(start = 8.dp, top = 8.dp, end = 8.dp),
    )
}

private fun renderTrack(
    mapView: MapView,
    track: HikeTrack?,
    bivouacPoints: List<BivouacPoint>,
    bivouacsReadOnly: Boolean,
    dayBoundaryIndices: List<Int>,
    shouldFit: Boolean,
    visibleHeightPx: Int,
    onTrackTapped: (Int) -> Unit,
    onBivouacMoved: (String, Int) -> Unit,
    onBivouacDragPreview: (String, Int) -> Unit,
    cursorIndex: Int?,
    onCursorChanged: (Int) -> Unit,
    cursorInfoWindow: CursorInfoWindow,
    cursorDragState: CursorDragState,
    multiTracks: List<ColoredTrack>,
    highlightedTrackId: String?,
    onTraceTapped: (String) -> Unit,
    copyrightOverlay: WrappingCopyrightOverlay,
) {
    // onCursorChanged deliberately updates Compose on every snapped point so the elevation
    // profile follows live. Do not let that recomposition destroy osmdroid's current drag.
    if (cursorDragState.isDragging) return

    mapView.overlays.clear()
    // Esri's Satellite notice needs a clickable "Esri" link (BIV-63); EsriAttributionLink (a real
    // Compose Text laid over the map) renders that layer's notice entirely instead, so the
    // canvas-drawn overlay — which has no touch target — steps aside only for it.
    val isSatelliteLayer = mapView.tileProvider.tileSource?.name() == MapLayer.SATELLITE.tileSource.name()
    if (!isSatelliteLayer) {
        mapView.overlays.add(copyrightOverlay)
    }
    // The MapView draws edge-to-edge behind the status bar, so an uncorrected top-aligned
    // overlay would sit right under the clock/battery icons. ViewCompat mirrors the same
    // statusBarsPadding() the Compose-side layer controls already use for this (top-right, same
    // screens) so both stay clear of the status bar the same way.
    val density = mapView.context.resources.displayMetrics.density
    val margin = (8 * density).toInt()
    val statusBarInsetPx = ViewCompat.getRootWindowInsets(mapView)
        ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
    copyrightOverlay.setOffset(margin, statusBarInsetPx + margin)
    cursorInfoWindow.close()

    if (multiTracks.isNotEmpty()) {
        renderMultiTracks(mapView, multiTracks, highlightedTrackId, shouldFit, visibleHeightPx, onTraceTapped)
        mapView.invalidate()
        return
    }

    if (track == null || track.points.isEmpty()) {
        mapView.invalidate()
        return
    }

    val context = mapView.context
    val points = track.points
    val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }

    // Without a listener, Polyline falls back to osmdroid's default onClick behavior — opening an
    // empty default info window — on any tap that lands on the line but misses trackTapOverlay's
    // (tighter, nearest-vertex) tolerance. trackTapOverlay is added after these and so gets first
    // refusal on every tap; this only ever fires as its fallback, and consuming it here (instead
    // of leaving it unhandled) is strictly better than popping an empty bubble.
    val suppressDefaultInfoWindow = Polyline.OnClickListener { _, _, _ -> true }

    fun strokedPolyline(pts: List<GeoPoint>, colorRes: Int, widthDp: Float, dashed: Boolean) =
        Polyline(mapView).apply {
            setPoints(pts)
            paint.apply {
                color = ContextCompat.getColor(context, colorRes)
                style = Paint.Style.STROKE
                strokeWidth = widthDp * density
                strokeCap = if (dashed) Paint.Cap.BUTT else Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                pathEffect = if (dashed) {
                    DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)
                } else {
                    null
                }
            }
            setOnClickListener(suppressDefaultInfoWindow)
        }

    // Une nuit où l'enregistrement a été coupé loin du camp laisse deux points distants que rien
    // ne relie réellement. Le tracé s'interrompt donc entre eux, et un pointillé prend le relais :
    // un trait plein s'y lirait comme un trajet parcouru, alors qu'il n'a jamais été enregistré.
    val gaps = DayJunctions.recordingGaps(points, dayBoundaryIndices).sorted()
    val continuousRuns = buildList {
        var runStart = 0
        for (gapEnd in gaps) {
            add(geoPoints.subList(runStart, gapEnd + 1))
            runStart = gapEnd + 1
        }
        add(geoPoints.subList(runStart, geoPoints.size))
    }.filter { it.size >= 2 }

    continuousRuns.forEach { run ->
        mapView.overlays.add(strokedPolyline(run, R.color.track_line_outline, 10f, dashed = false))
    }
    continuousRuns.forEach { run ->
        mapView.overlays.add(strokedPolyline(run, R.color.track_line, 4f, dashed = false))
    }
    gaps.forEach { gapEnd ->
        val bridge = listOf(geoPoints[gapEnd], geoPoints[gapEnd + 1])
        mapView.overlays.add(strokedPolyline(bridge, R.color.track_line, 3f, dashed = true))
    }
    mapView.overlays.add(trackTapOverlay(mapView, points, geoPoints, density, onTrackTapped))

    bivouacPoints.forEach { bivouac ->
        mapView.overlays.add(
            bivouacMarker(
                mapView, points, geoPoints, bivouac, onBivouacMoved, onBivouacDragPreview,
                draggable = !bivouacsReadOnly,
            ),
        )
    }

    mapView.overlays.addAll(endpointMarkers(mapView, points))
    mapView.overlays.addAll(directionArrowMarkers(mapView, points, geoPoints))

    if (cursorIndex != null && cursorIndex in points.indices) {
        mapView.overlays.add(
            cursorMarker(
                mapView, points, geoPoints, cursorIndex, density, onCursorChanged,
                cursorInfoWindow, cursorDragState,
            ),
        )
        val bubbleText = cursorBubbleText(points, cursorIndex)
        val bubblePosition = geoPoints[cursorIndex]
        // A tap can be dispatched to an overlay that existed before this recomposition and open
        // its default InfoWindow after renderTrack returns. Re-open ours on the next UI frame so
        // it deterministically wins and no empty osmdroid speech bubble remains on screen.
        mapView.postDelayed({
            InfoWindow.closeAllInfoWindowsOn(mapView)
            cursorInfoWindow.open(bubbleText, bubblePosition, 0, cursorBubbleOffsetY(density))
        }, 100L)
    } else {
        cursorInfoWindow.close()
    }

    if (shouldFit) {
        fitToTrack(mapView, geoPoints, visibleHeightPx)
    }
    mapView.invalidate()
}

// BIV-48: a contemplative overview, but still legible — each trace keeps the single-track
// "color on white casing" treatment, plus direction arrows on loops and start/finish pins.
// No bivouacs (Journal traces
// don't carry bivouac data yet — cf. BIV-41) and no tap-to-cursor. Tapping a trace on the map
// highlights it exactly like tapping its legend entry does.
private fun renderMultiTracks(
    mapView: MapView,
    tracks: List<ColoredTrack>,
    highlightedTrackId: String?,
    shouldFit: Boolean,
    visibleHeightPx: Int,
    onTraceTapped: (String) -> Unit,
) {
    val context = mapView.context
    val density = context.resources.displayMetrics.density
    val allGeoPoints = mutableListOf<GeoPoint>()

    tracks.forEach { colored ->
        val geoPoints = colored.track.points.map { GeoPoint(it.latitude, it.longitude) }
        if (geoPoints.isEmpty()) return@forEach
        allGeoPoints.addAll(geoPoints)
        val isHighlighted = highlightedTrackId == colored.id
        val isDimmed = highlightedTrackId != null && !isHighlighted
        val alphaValue = if (isDimmed) 90 else 255
        val clickListener = Polyline.OnClickListener { _, _, _ -> onTraceTapped(colored.id); true }

        val outline = Polyline(mapView).apply {
            setPoints(geoPoints)
            paint.apply {
                color = ContextCompat.getColor(context, R.color.track_line_outline)
                alpha = alphaValue
                style = Paint.Style.STROKE
                strokeWidth = (if (isHighlighted) 11f else 9f) * density
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            setOnClickListener(clickListener)
        }
        val colorLine = Polyline(mapView).apply {
            setPoints(geoPoints)
            paint.apply {
                color = colored.color.toArgb()
                alpha = alphaValue
                style = Paint.Style.STROKE
                strokeWidth = (if (isHighlighted) 6f else 4f) * density
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            setOnClickListener(clickListener)
        }
        mapView.overlays.add(outline)
        mapView.overlays.add(colorLine)
        mapView.overlays.addAll(endpointMarkers(mapView, colored.track.points))
        mapView.overlays.addAll(directionArrowMarkers(mapView, colored.track.points, geoPoints, colored.color.toArgb()))
    }

    if (shouldFit && allGeoPoints.isNotEmpty()) {
        fitToTrack(mapView, allGeoPoints, visibleHeightPx)
    }
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
    // Journal : la nuit est un fait passé, déduit de la coupure entre deux fichiers importés. La
    // déplacer n'aurait aucun sens, et la trace du Journal est de toute façon immuable.
    draggable: Boolean = true,
): Marker {
    val marker = Marker(mapView)
    marker.position = geoPoints[bivouac.trackPointIndex]
    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    marker.icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_marker_bivouac)
    // A short tap otherwise falls through to osmdroid's default (empty-looking, title-only) info
    // window — the bivouac's actual details already live in the segments table, not on the map.
    // Same treatment as the endpoint markers and the cursor marker just below.
    marker.setInfoWindow(null)
    marker.setOnMarkerClickListener { _, _ -> false }
    marker.isDraggable = draggable
    if (!draggable) return marker

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

// Same drag-and-snap gabarit as a bivouac marker, but nothing here is ever persisted — every
// snapped position during a drag is immediately reported as final via onCursorChanged (no
// preview/commit split), and re-opens the info bubble on each index change so it tracks the
// marker without waiting for the next full recomposition.
private fun cursorMarker(
    mapView: MapView,
    points: List<TrackPoint>,
    geoPoints: List<GeoPoint>,
    cursorIndex: Int,
    density: Float,
    onCursorChanged: (Int) -> Unit,
    cursorInfoWindow: CursorInfoWindow,
    cursorDragState: CursorDragState,
): Marker {
    val marker = CursorDragMarker(mapView)
    marker.position = geoPoints[cursorIndex]
    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    marker.icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_marker_cursor)
    marker.isDraggable = true
    marker.setInfoWindow(null)
    // The cursor's dedicated CursorInfoWindow is opened explicitly with distance/altitude.
    // Consuming marker taps prevents osmdroid from opening its large empty default bubble on
    // the same ACTION_UP that initially placed the cursor through the track tap overlay.
    marker.setOnMarkerClickListener { _, _ -> true }

    var lastIndex = cursorIndex
    marker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
        override fun onMarkerDragStart(marker: Marker) {
            cursorDragState.isDragging = true
        }

        override fun onMarkerDrag(marker: Marker) {
            val current = marker.position
            val nearestIndex = TrackGeometry.nearestPointIndex(points, current.latitude, current.longitude)
            marker.position = geoPoints[nearestIndex]
            if (nearestIndex != lastIndex) {
                lastIndex = nearestIndex
                cursorInfoWindow.open(cursorBubbleText(points, nearestIndex), geoPoints[nearestIndex], 0, cursorBubbleOffsetY(density))
                onCursorChanged(nearestIndex)
            }
            mapView.invalidate()
        }

        override fun onMarkerDragEnd(marker: Marker) {
            cursorDragState.isDragging = false
            val nearestIndex = TrackGeometry.nearestPointIndex(
                points, marker.position.latitude, marker.position.longitude,
            )
            marker.position = geoPoints[nearestIndex]
            // Re-emit the snapped final value even if the last MOVE already reported it. This
            // gives Compose one deterministic post-drag update after a burst of MOVE callbacks.
            onCursorChanged(nearestIndex)
            mapView.invalidate()
        }
    })
    return marker
}

private fun cursorBubbleText(points: List<TrackPoint>, index: Int): String {
    val distanceKm = TrackGeometry.cumulativeDistancesMeters(points)[index] / 1000.0
    val altitude = points[index].elevationMeters?.roundToInt()
    val distanceText = "${formatKm1(distanceKm)} km"
    return if (altitude != null) "$distanceText · ${formatGroupedInt(altitude)} m" else distanceText
}

// Negative: lifts the bubble's anchor above the marker's own geo point by roughly the pin's
// rendered height, so the bubble sits above the pin instead of covering it (and blocking drags).
private fun cursorBubbleOffsetY(density: Float): Int = -(CURSOR_MARKER_HEIGHT_DP * density).toInt()

// Minimal InfoWindow (osmdroid's marker-anchored bubble mechanism — it repositions itself on
// every pan/zoom, so the bubble tracks the marker without any Compose-side involvement) showing
// just a distance/altitude readout. Reused across the whole HikeMapView lifetime rather than
// recreated per render, since InfoWindow owns a real child View added to the MapView.
private class CursorInfoWindow(mapView: MapView) : InfoWindow(R.layout.map_cursor_bubble, mapView) {
    override fun onOpen(item: Any?) {
        (item as? String)?.let { text -> mView.findViewById<TextView>(R.id.cursor_bubble_text).text = text }
    }

    override fun onClose() = Unit
}

// The visible pin stays the same 40 dp gabarit as a bivouac, while a modest invisible 56 dp
// touch target makes long-press-and-drag dependable. Only touches beginning in this circle are
// consumed; pan and pinch gestures elsewhere remain MapView's responsibility.
private class CursorDragMarker(private val owner: MapView) : Marker(owner) {
    override fun hitTest(event: MotionEvent, mapView: MapView): Boolean {
        if (super.hitTest(event, mapView)) return true
        val center = owner.projection.toPixels(position, Point())
        val radius = CURSOR_HIT_RADIUS_DP * owner.resources.displayMetrics.density
        val dx = event.x - center.x
        val dy = event.y - center.y
        return dx * dx + dy * dy <= radius * radius
    }
}

private fun endpointMarkers(mapView: MapView, points: List<TrackPoint>): List<Marker> {
    val first = points.first()
    val last = points.last()
    val isLoop = TrackGeometry.isLoop(points, LOOP_THRESHOLD_METERS)

    return if (isLoop) {
        listOf(marker(mapView, first, R.drawable.ic_marker_start_finish))
    } else {
        listOf(
            marker(mapView, first, R.drawable.ic_marker_start),
            marker(mapView, last, R.drawable.ic_marker_finish),
        )
    }
}

// Chevron markers only belong on loops, where the shared start/finish marker leaves direction
// ambiguous. [tintColor]
// recolors the whole icon (losing its white outline in exchange) for multi-trace mode, where each
// trace's arrows need to match its own line color rather than the single-track default blue.
private fun directionArrowMarkers(
    mapView: MapView,
    points: List<TrackPoint>,
    geoPoints: List<GeoPoint>,
    tintColor: Int? = null,
): List<Marker> {
    if (points.size < 3 || !TrackGeometry.isLoop(points, LOOP_THRESHOLD_METERS)) return emptyList()
    val cumulative = TrackGeometry.cumulativeDistancesMeters(points)
    val totalDistance = cumulative.last()
    if (totalDistance <= 0) return emptyList()

    val count = (totalDistance / ARROW_TARGET_SPACING_METERS).roundToInt().coerceIn(ARROW_MIN_COUNT, ARROW_MAX_COUNT)
    return (1..count).mapNotNull { i ->
        val targetDistance = totalDistance * i / (count + 1)
        val index = nearestIndexForDistance(cumulative, targetDistance)
        val screenRotation = projectedTangentRotation(mapView, geoPoints, cumulative, index)
            ?: return@mapNotNull null
        Marker(mapView).apply {
            position = geoPoints[index]
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_track_arrow)?.mutate()?.also { drawable ->
                if (tintColor != null) DrawableCompat.setTint(drawable, tintColor)
            }
            // Projection pixels already include the current map orientation. A flat marker uses
            // a screen-space rotation; osmdroid applies the negative of the supplied bearing.
            setFlat(true)
            rotation = screenRotation
            // Purely decorative — consume taps instead of popping an empty default info window.
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> false }
        }
    }
}

// Looks a fixed distance before/after [index] (rather than at its immediate neighbors) so a
// switchback or GPS jitter right at that point doesn't flip the arrow against the trace's actual
// direction of travel over the surrounding stretch.
private fun projectedTangentRotation(
    mapView: MapView,
    geoPoints: List<GeoPoint>,
    cumulative: DoubleArray,
    index: Int,
): Float? {
    val center = cumulative[index]
    val beforeIndex = nearestIndexForDistance(cumulative, (center - ARROW_BEARING_WINDOW_METERS).coerceAtLeast(0.0))
    val afterIndex = nearestIndexForDistance(cumulative, (center + ARROW_BEARING_WINDOW_METERS).coerceAtMost(cumulative.last()))
    if (beforeIndex == afterIndex) return null
    val beforePx = mapView.projection.toPixels(geoPoints[beforeIndex], Point())
    val afterPx = mapView.projection.toPixels(geoPoints[afterIndex], Point())
    val dx = (afterPx.x - beforePx.x).toDouble()
    val dy = (afterPx.y - beforePx.y).toDouble()
    if (dx * dx + dy * dy < 4.0) return null
    val clockwiseFromUp = Math.toDegrees(atan2(dx, -dy))
    return (-clockwiseFromUp).toFloat()
}

private fun nearestIndexForDistance(cumulative: DoubleArray, targetDistance: Double): Int {
    var lo = 0
    var hi = cumulative.lastIndex
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (cumulative[mid] < targetDistance) lo = mid + 1 else hi = mid
    }
    if (lo > 0 && abs(cumulative[lo - 1] - targetDistance) <= abs(cumulative[lo] - targetDistance)) return lo - 1
    return lo
}

private fun marker(mapView: MapView, point: TrackPoint, iconRes: Int): Marker =
    Marker(mapView).apply {
        position = GeoPoint(point.latitude, point.longitude)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        icon = ContextCompat.getDrawable(mapView.context, iconRes)
        // Endpoint pins are labels, not controls. Let taps near them be handled by the track
        // cursor overlay without opening osmdroid's oversized default speech bubble.
        setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> false }
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
        // center normally, then correct in two steps below.
        val hiddenHeightPx = mapView.height - visibleHeightPx.coerceAtMost(mapView.height)

        // Step 1: the fit above sized the track to the FULL view height, which is taller than
        // what's actually visible once the sheet's cover is excluded — left uncorrected, the top
        // of the track ends up pushed past the top edge of the screen once shifted into view
        // below (only noticeable when the sheet covers a large share of the height, e.g.
        // landscape, not portrait where the covered share is small). Zoom out by exactly the
        // ratio needed so the track's rendered height matches the available height instead.
        val availableHeightPx = visibleHeightPx.coerceAtMost(mapView.height) - 2 * borderPx
        if (availableHeightPx > 0) {
            val boxTopPx = mapView.projection.toPixels(GeoPoint(boundingBox.latNorth, boundingBox.centerLongitude), null).y
            val boxBottomPx = mapView.projection.toPixels(GeoPoint(boundingBox.latSouth, boundingBox.centerLongitude), null).y
            val boxHeightPx = boxBottomPx - boxTopPx
            if (boxHeightPx > availableHeightPx) {
                val zoomDelta = log2(availableHeightPx.toDouble() / boxHeightPx)
                mapView.controller.setZoom(mapView.zoomLevelDouble + zoomDelta)
            }
        }

        // Step 2: re-center on whichever point is currently at the middle of the portion the
        // sheet doesn't cover, shifting the (now correctly sized) fitted track up into view.
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

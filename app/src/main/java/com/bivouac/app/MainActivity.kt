package com.bivouac.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bivouac.app.data.gpx.GpxExporter
import com.bivouac.app.data.gpx.TrackStats
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.Segment
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.weather.MeteoblueLink
import com.bivouac.app.gpximport.GpxImportUiState
import com.bivouac.app.gpximport.GpxImportViewModel
import com.bivouac.app.ui.components.ElevationProfile
import com.bivouac.app.ui.map.HikeMapView
import com.bivouac.app.ui.map.MapControls
import com.bivouac.app.ui.map.MapLayer
import com.bivouac.app.ui.theme.BivouacTheme
import java.util.Locale
import kotlin.math.roundToInt

private val PEEK_HEIGHT_EMPTY = 150.dp

private val DistanceIconColor = Color(0xFF3C7A5D)
private val DurationIconColor = Color(0xFF6FA8CC)
private val GainIconColor = Color(0xFFD98E48)
private val LossIconColor = Color(0xFFD4B94E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val incomingGpxUri = intent.extractGpxUri()
        setContent {
            BivouacTheme {
                GpxImportScreen(modifier = Modifier.fillMaxSize(), incomingGpxUri = incomingGpxUri)
            }
        }
    }
}

/**
 * Uri d'un fichier GPX reçu depuis une autre application, via ouverture directe (VIEW) ou
 * partage (SEND) — cf. les intent-filters déclarés dans le manifeste.
 */
@Suppress("DEPRECATION")
private fun Intent.extractGpxUri(): Uri? = when (action) {
    Intent.ACTION_VIEW -> data
    Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM)
    }
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpxImportScreen(
    modifier: Modifier = Modifier,
    incomingGpxUri: Uri? = null,
    viewModel: GpxImportViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val bivouacPoints by viewModel.bivouacPoints.collectAsStateWithLifecycle()
    val effectiveBivouacPoints by viewModel.effectiveBivouacPoints.collectAsStateWithLifecycle()
    val segments by viewModel.segments.collectAsStateWithLifecycle()
    val loadedTrack = (uiState as? GpxImportUiState.Loaded)?.track

    val selectedLayer by viewModel.selectedLayer.collectAsStateWithLifecycle()
    var recenterSignal by remember { mutableIntStateOf(0) }

    // The loaded-state peek content (title/stats/curve) is measured rather than given a fixed dp
    // height: a static guess drifts out of sync the moment that content changes (as 240dp did the
    // moment the elevation profile was added) and either wastes space or creates a dead zone
    // above the sheet's declared touch bounds.
    var loadedPeekHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val loadedPeekHeight = with(density) { loadedPeekHeightPx.toDp() }

    // Recentering should fit the track into whatever the sheet doesn't currently cover, not the
    // full (partly hidden) map view — osmdroid has no asymmetric-fit API, so this is done by
    // measuring both the map's and the sheet's actual on-screen position and re-centering
    // manually afterwards (see fitToTrack). Float.MAX_VALUE sentinel = not measured yet / no sheet
    // overlap known, meaning "behave as before".
    var mapBoxTopPx by remember { mutableFloatStateOf(0f) }
    var sheetTopPx by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val visibleMapHeightPx = (sheetTopPx - mapBoxTopPx).let { if (it.isFinite() && it > 0) it.toInt() else Int.MAX_VALUE }

    // A rotation destroys and recreates the Activity, so onCreate re-evaluates the incoming
    // intent's URI and this effect fires again with the same (non-null) value — only guarding on
    // Idle stops that replay from re-importing (and wiping bivouac points) on every rotation. An
    // explicit incoming GPX (opened from another app) always wins over whatever was saved from
    // the previous session; otherwise, restore that previous trace so a restart doesn't lose it.
    LaunchedEffect(incomingGpxUri) {
        if (uiState is GpxImportUiState.Idle) {
            if (incomingGpxUri != null) {
                viewModel.importGpx(context.contentResolver, incomingGpxUri)
            } else {
                viewModel.restoreLastTrack()
            }
        }
    }

    val pickGpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importGpx(context.contentResolver, it) }
    }

    // Cap the peek height to a share of the available screen height so the sheet can't swallow
    // the map in landscape, where total height is much smaller than the measured content needs.
    val maxPeekHeight = LocalConfiguration.current.screenHeightDp.dp * 0.5f

    BottomSheetScaffold(
        modifier = modifier,
        sheetPeekHeight = if (loadedTrack != null) {
            loadedPeekHeight.coerceIn(PEEK_HEIGHT_EMPTY, maxPeekHeight)
        } else {
            PEEK_HEIGHT_EMPTY.coerceAtMost(maxPeekHeight)
        },
        sheetContent = {
            TrackSheetContent(
                uiState = uiState,
                bivouacPoints = bivouacPoints,
                elevationMarkerPoints = effectiveBivouacPoints,
                segments = segments,
                onOpenClick = { pickGpxLauncher.launch(arrayOf("*/*")) },
                onCloseClick = viewModel::clear,
                onRemovePoint = viewModel::removeBivouacPoint,
                onExportSegment = { index, segment ->
                    val baseName = loadedTrack?.name ?: "Trace"
                    val dayName = "$baseName - Jour ${index + 1}"
                    context.startActivity(GpxExporter.openIntent(context, segment.points, dayName))
                },
                onWeatherClick = { point ->
                    val url = MeteoblueLink.forCoordinates(point.latitude, point.longitude)
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                onPeekHeightMeasured = { loadedPeekHeightPx = it },
                onSheetTopMeasured = { sheetTopPx = it },
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { mapBoxTopPx = it.positionInRoot().y },
        ) {
            HikeMapView(
                track = loadedTrack,
                bivouacPoints = bivouacPoints,
                selectedLayer = selectedLayer,
                recenterSignal = recenterSignal,
                visibleHeightPx = visibleMapHeightPx,
                onTrackTapped = viewModel::addBivouacPoint,
                onBivouacMoved = viewModel::moveBivouacPoint,
                onBivouacDragPreview = viewModel::previewBivouacDrag,
                modifier = Modifier.fillMaxSize(),
            )
            MapControls(
                selectedLayer = selectedLayer,
                onLayerSelected = viewModel::setSelectedLayer,
                recenterEnabled = loadedTrack != null,
                onRecenterClick = { recenterSignal++ },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun TrackSheetContent(
    uiState: GpxImportUiState,
    bivouacPoints: List<BivouacPoint>,
    elevationMarkerPoints: List<BivouacPoint>,
    segments: List<Segment>,
    onOpenClick: () -> Unit,
    onCloseClick: () -> Unit,
    onRemovePoint: (String) -> Unit,
    onExportSegment: (index: Int, segment: Segment) -> Unit,
    onWeatherClick: (TrackPoint) -> Unit,
    onPeekHeightMeasured: (Int) -> Unit,
    onSheetTopMeasured: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The sheet's own drag handles collapse/expand; once fully expanded, a long segments
            // list (many bivouac points) still needs to scroll within that fixed height — nothing
            // did that before, so the content beyond the screen's bottom was just unreachable.
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 40.dp)
            .onGloballyPositioned { onSheetTopMeasured(it.positionInRoot().y) },
    ) {
        when (uiState) {
            is GpxImportUiState.Idle -> {
                Button(onClick = onOpenClick, modifier = Modifier.fillMaxWidth()) {
                    Text("Ouvrir une trace")
                }
            }
            is GpxImportUiState.Loading -> {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is GpxImportUiState.Error -> {
                Text(text = uiState.message, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenClick, modifier = Modifier.fillMaxWidth()) {
                    Text("Ouvrir une trace")
                }
            }
            is GpxImportUiState.Loaded -> {
                // Only this part is always visible in the collapsed sheet — measured so the
                // peek height can track it exactly, independently of the segments list below,
                // which only renders (and only needs to be reachable) once expanded.
                Column(modifier = Modifier.onGloballyPositioned { onPeekHeightMeasured(it.size.height) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = uiState.track.name ?: "Trace sans nom",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                        )
                        IconButton(onClick = onCloseClick) {
                            Icon(Icons.Default.Close, contentDescription = "Fermer la trace")
                        }
                    }
                    if (bivouacPoints.isNotEmpty()) {
                        Text(
                            text = "Total",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatsRows(uiState.stats, muted = bivouacPoints.isNotEmpty())

                    ElevationProfile(
                        points = uiState.track.points,
                        bivouacPoints = elevationMarkerPoints,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                }

                if (bivouacPoints.isNotEmpty()) {
                    SegmentsList(
                        track = uiState.track,
                        segments = segments,
                        bivouacPoints = bivouacPoints,
                        onRemovePoint = onRemovePoint,
                        onExportSegment = onExportSegment,
                        onWeatherClick = onWeatherClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsRows(stats: TrackStats, muted: Boolean = false) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val distanceColor = if (muted) neutral else DistanceIconColor
    val durationColor = if (muted) neutral else DurationIconColor
    val gainColor = if (muted) neutral else GainIconColor
    val lossColor = if (muted) neutral else LossIconColor

    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        InfoText(String.format(Locale.FRANCE, "%.1f km", stats.distanceMeters / 1000), Icons.Filled.Route, distanceColor)
        InfoText(formatDuration(stats.estimatedDurationMinutes), Icons.Filled.Schedule, durationColor)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        InfoText("D+ ${stats.elevationGainMeters.toInt()} m", Icons.AutoMirrored.Filled.TrendingUp, gainColor)
        InfoText("D- ${stats.elevationLossMeters.toInt()} m", Icons.AutoMirrored.Filled.TrendingDown, lossColor)
    }
}

@Composable
private fun SegmentsList(
    track: HikeTrack,
    segments: List<Segment>,
    bivouacPoints: List<BivouacPoint>,
    onRemovePoint: (String) -> Unit,
    onExportSegment: (index: Int, segment: Segment) -> Unit,
    onWeatherClick: (TrackPoint) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        segments.forEachIndexed { index, segment ->
            HorizontalDivider()
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Jour ${index + 1}", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { onExportSegment(index, segment) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = "Télécharger ce segment en GPX",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                StatsRows(segment.stats)
            }

            if (index < bivouacPoints.size) {
                HorizontalDivider()
                val bivouac = bivouacPoints[index]
                val trackPoint = track.points[bivouac.trackPointIndex]
                BivouacRow(
                    trackPoint = trackPoint,
                    onWeatherClick = { onWeatherClick(trackPoint) },
                    onRemove = { onRemovePoint(bivouac.id) },
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun BivouacRow(trackPoint: TrackPoint, onWeatherClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_bivouac_badge),
            contentDescription = "Point de bivouac",
            modifier = Modifier.size(24.dp),
        )
        val elevation = trackPoint.elevationMeters
        if (elevation != null) {
            InfoText(
                text = "${elevation.roundToInt()} m",
                icon = Icons.Default.Terrain,
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        ComposedWeatherIconButton(onClick = onWeatherClick)
        IconButton(onClick = onRemove, modifier = Modifier.padding(start = 6.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Supprimer ce point de bivouac",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// No native "partly cloudy" glyph in the Material icon set used, so the classic sun-behind-cloud
// pictogram is composed from the two separate icons instead.
@Composable
private fun ComposedWeatherIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(modifier = Modifier.size(22.dp)) {
            Icon(
                Icons.Default.Cloud,
                contentDescription = "Météo au point de bivouac",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .align(Alignment.BottomStart),
            )
            Icon(
                Icons.Default.WbSunny,
                contentDescription = null,
                tint = GainIconColor,
                modifier = Modifier
                    .size(12.dp)
                    .align(Alignment.TopEnd),
            )
        }
    }
}

@Composable
private fun InfoText(text: String, icon: ImageVector, iconTint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = iconTint)
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatDuration(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return "${hours}h${minutes.toString().padStart(2, '0')}"
}

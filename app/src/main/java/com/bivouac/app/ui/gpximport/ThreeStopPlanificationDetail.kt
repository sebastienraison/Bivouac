package com.bivouac.app.ui.gpximport

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.bivouac.app.R
import com.bivouac.app.data.gpx.TrackStats
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.Segment
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.ui.components.ElevationProfile
import com.bivouac.app.ui.components.GainIconColor
import com.bivouac.app.ui.components.InfoText
import com.bivouac.app.ui.components.StatsRows
import com.bivouac.app.ui.journal.JournalDetailStop
import com.bivouac.app.ui.journal.settleJournalDetailStop
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Planification's take on Journal's three-stop drawer (BIV-54): same drag-to-resize mechanic and
 * stop set (Synthèse/Profil/Détails), reused via [JournalDetailStop] and [settleJournalDetailStop]
 * rather than reinvented, but with its own content and its own DETAIL sizing — unlike Journal's,
 * which always reaches fullHeightPx, Planification stays an active context (the map behind the
 * sheet must stay usable for placing/dragging bivouacs), so DETAIL only grows to fullHeightPx when
 * the segments table actually needs it: every stop's height is the real measured size of its own
 * content, capped — never forced — at fullHeightPx (see segmentsMaxHeightPx below for how the
 * segments list itself gets bounded and made to scroll once it would otherwise exceed that cap).
 * Kept as a separate composable from ThreeStopJournalDetail instead of a shared abstraction: the
 * two screens' content differs enough (tags/notes editing lives only in Journal; here it's segments
 * + bivouac points) that forcing one generic component would cost more in indirection than the
 * ~150 lines of shared drag/anchor/nested-scroll plumbing it would save.
 */
@Composable
internal fun ThreeStopPlanificationDetail(
    track: HikeTrack,
    stats: TrackStats,
    bivouacPoints: List<BivouacPoint>,
    elevationMarkerPoints: List<BivouacPoint>,
    segments: List<Segment>,
    dirty: Boolean,
    isBanked: Boolean,
    onCloseClick: () -> Unit,
    onSaveClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDuplicateClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onRemovePoint: (String) -> Unit,
    onExportSegment: (index: Int, segment: Segment) -> Unit,
    onWeatherClick: (TrackPoint) -> Unit,
    onSheetTopMeasured: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()

        val fullHeightPx = with(density) { maxHeight.toPx() }
        var measuredSummaryHeightPx by remember { mutableIntStateOf(0) }
        var measuredProfileAdditionPx by remember { mutableIntStateOf(0) }
        var measuredSegmentsAdditionPx by remember { mutableIntStateOf(0) }
        val fallbackSummaryHeightPx = with(density) { 150.dp.toPx() }
        val navigationBarHeightPx = WindowInsets.navigationBars.getBottom(density).toFloat()
        // Every stop below is sized off what its own content actually measures, capped only by
        // fullHeightPx as an absolute safety net — never by an arbitrary fraction, which is what
        // was clipping the profile curve and the segments table on some real devices (BIV-57
        // phone recette): a taller-than-expected header (larger system font, different insets)
        // left less room than the fixed 55%/60% caps assumed, cutting off content below them.
        val summaryHeightPx = (if (measuredSummaryHeightPx > 0) {
            measuredSummaryHeightPx.toFloat() + navigationBarHeightPx + with(density) { 8.dp.toPx() }
        } else {
            fallbackSummaryHeightPx + navigationBarHeightPx
        }).coerceAtMost(fullHeightPx)
        val profileHeightPx = (summaryHeightPx + measuredProfileAdditionPx)
            .coerceIn(summaryHeightPx, fullHeightPx)
        // How much room is left for the segments list before DETAIL would have to exceed the
        // screen — the segments Column below is capped to exactly this via heightIn(max), so it
        // naturally scrolls instead of pushing DETAIL past fullHeightPx on long, multi-day traces.
        val segmentsMaxHeightPx = (fullHeightPx - profileHeightPx).coerceAtLeast(0f)
        val detailHeightPx = (profileHeightPx + measuredSegmentsAdditionPx)
            .coerceIn(profileHeightPx, fullHeightPx)
        val anchors = remember(fullHeightPx, summaryHeightPx, profileHeightPx, detailHeightPx) {
            mapOf(
                JournalDetailStop.DETAIL to fullHeightPx - detailHeightPx,
                JournalDetailStop.PROFILE to fullHeightPx - profileHeightPx,
                JournalDetailStop.SUMMARY to fullHeightPx - summaryHeightPx,
            )
        }
        var stop by remember { mutableStateOf(JournalDetailStop.PROFILE) }
        val offset = remember { Animatable(anchors.getValue(JournalDetailStop.PROFILE)) }

        // Height of the summary block and of the profile addition are both unknown before their
        // first real layout pass (0 / a rough fallback), so the very first anchors this composable
        // sees undershoot the real PROFILE stop. Re-snapping to the *current* stop (not forcing
        // PROFILE) whenever anchors change also absorbs later, smaller content-driven shifts — the
        // "Total" label appearing on the first bivouac point, for instance — without yanking the
        // drawer back to PROFILE while the user is actively working the DETAIL stop.
        LaunchedEffect(anchors) {
            offset.snapTo(anchors.getValue(stop))
        }

        fun animateTo(target: JournalDetailStop, initialVelocity: Float = 0f) {
            stop = target
            scope.launch {
                offset.animateTo(
                    targetValue = anchors.getValue(target),
                    animationSpec = spring(),
                    initialVelocity = initialVelocity,
                )
            }
        }

        val dragModifier = Modifier.draggable(
            state = rememberDraggableState { delta ->
                scope.launch {
                    offset.snapTo(
                        (offset.value + delta).coerceIn(
                            anchors.getValue(JournalDetailStop.DETAIL),
                            anchors.getValue(JournalDetailStop.SUMMARY),
                        ),
                    )
                }
            },
            orientation = Orientation.Vertical,
            onDragStopped = { velocity ->
                animateTo(settleJournalDetailStop(offset.value, velocity, anchors), velocity)
            },
        )
        val detailScrollState = rememberScrollState()
        val detailNestedScroll = remember(anchors) {
            object : NestedScrollConnection {
                private var handedToSheet = false

                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val atBoundary = (available.y > 0f && detailScrollState.value == 0) ||
                        (available.y < 0f && detailScrollState.value == detailScrollState.maxValue)
                    if (source != NestedScrollSource.UserInput || !atBoundary) return Offset.Zero
                    handedToSheet = true
                    scope.launch {
                        offset.snapTo(
                            (offset.value + available.y).coerceIn(
                                anchors.getValue(JournalDetailStop.DETAIL),
                                anchors.getValue(JournalDetailStop.SUMMARY),
                            ),
                        )
                    }
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!handedToSheet) return Velocity.Zero
                    handedToSheet = false
                    animateTo(settleJournalDetailStop(offset.value, available.y, anchors), available.y)
                    return available
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (handedToSheet) {
                        handedToSheet = false
                        animateTo(settleJournalDetailStop(offset.value, available.y, anchors), available.y)
                    }
                    return Velocity.Zero
                }
            }
        }
        val statusBarHeightPx = WindowInsets.statusBars.getTop(density).toFloat()
        val detailTravelPx = anchors.getValue(JournalDetailStop.PROFILE) -
            anchors.getValue(JournalDetailStop.DETAIL)
        val detailExpansion = if (detailTravelPx <= 0f) 1f else {
            ((anchors.getValue(JournalDetailStop.PROFILE) - offset.value) / detailTravelPx)
                .coerceIn(0f, 1f)
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { detailHeightPx.toDp() })
                .offset { IntOffset(0, offset.value.roundToInt()) }
                .onGloballyPositioned { onSheetTopMeasured(it.positionInRoot().y.toInt()) },
            shape = if (offset.value <= 1f) RectangleShape else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 3.dp,
            shadowElevation = 10.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.height(with(density) { (statusBarHeightPx * detailExpansion).toDp() }),
                )
                Column(
                    modifier = dragModifier
                        .padding(
                            start = 20.dp,
                            end = 20.dp,
                            bottom = with(density) { navigationBarHeightPx.toDp() } + 8.dp,
                        )
                        .onGloballyPositioned { measuredSummaryHeightPx = it.size.height },
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 10.dp, bottom = 4.dp)
                            .size(width = 44.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant)
                            .graphicsLayer { alpha = 0.45f }
                            .semantics { contentDescription = "Poignée du tiroir" }
                            .clickable {
                                animateTo(
                                    when (stop) {
                                        JournalDetailStop.SUMMARY -> JournalDetailStop.PROFILE
                                        JournalDetailStop.PROFILE -> JournalDetailStop.DETAIL
                                        JournalDetailStop.DETAIL -> JournalDetailStop.SUMMARY
                                    },
                                )
                            },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = track.name ?: "Trace sans nom",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        TrackActionsRow(
                            dirty = dirty,
                            isBanked = isBanked,
                            onSaveClick = onSaveClick,
                            onRenameClick = onRenameClick,
                            onDuplicateClick = onDuplicateClick,
                            onDeleteClick = onDeleteClick,
                            onCloseClick = onCloseClick,
                        )
                    }
                    if (bivouacPoints.isNotEmpty()) {
                        Text(
                            text = "Total",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatsRows(stats, muted = bivouacPoints.isNotEmpty())
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        JournalDetailStop.entries.forEach { candidate ->
                            TextButton(onClick = { animateTo(candidate) }) {
                                Text(
                                    when (candidate) {
                                        JournalDetailStop.SUMMARY -> "Synthèse"
                                        JournalDetailStop.PROFILE -> "Profil"
                                        JournalDetailStop.DETAIL -> "Détails"
                                    },
                                    color = if (candidate == stop) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }

                ElevationProfile(
                    points = track.points,
                    bivouacPoints = elevationMarkerPoints,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .padding(top = 10.dp, bottom = 2.dp)
                        .onGloballyPositioned { measuredProfileAdditionPx = it.size.height },
                )

                if (bivouacPoints.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = with(density) { segmentsMaxHeightPx.toDp() })
                            .nestedScroll(detailNestedScroll)
                            .verticalScroll(detailScrollState)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp)
                            .onGloballyPositioned { measuredSegmentsAdditionPx = it.size.height },
                    ) {
                        SegmentsList(
                            track = track,
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
}

// Trailing icons on the title row of an open trace. Order matters and is deliberate: save, then
// the overflow menu (duplicate/delete), then close last — see CONCEPTION notes. The overflow menu
// never holds save or close, both stay standalone; reused identically on each home screen list row
// (minus save, nothing to save from there) to keep the convention consistent across the app.
@Composable
private fun TrackActionsRow(
    dirty: Boolean,
    isBanked: Boolean,
    onSaveClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDuplicateClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row {
        IconButton(onClick = onSaveClick) {
            Icon(
                Icons.Default.Save,
                contentDescription = if (dirty) "Enregistrer (modifications non sauvegardées)" else "Enregistrer",
                // Orange (GainIconColor) rather than the error/red role: an unsaved change isn't
                // a critical error, just a state — red is reserved for destructive actions
                // (delete), matching Material 3's role guidance.
                tint = if (dirty) GainIconColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menu")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Renommer") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    enabled = isBanked,
                    onClick = { menuExpanded = false; onRenameClick() },
                )
                DropdownMenuItem(
                    text = { Text("Dupliquer") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    onClick = { menuExpanded = false; onDuplicateClick() },
                )
                DropdownMenuItem(
                    text = { Text("Supprimer") },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    enabled = isBanked,
                    onClick = { menuExpanded = false; onDeleteClick() },
                )
            }
        }
        IconButton(onClick = onCloseClick) {
            Icon(Icons.Default.Close, contentDescription = "Fermer la trace")
        }
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

package com.bivouac.app.ui.journal

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.db.SystemTag
import com.bivouac.app.data.gpx.TrackStats
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.journal.JournalUiState
import com.bivouac.app.journal.JournalViewModel
import com.bivouac.app.ui.components.ElevationProfile
import com.bivouac.app.ui.components.GainIconColor
import com.bivouac.app.ui.components.StatsRows
import com.bivouac.app.ui.map.ColoredTrack
import com.bivouac.app.ui.map.HikeMapView
import com.bivouac.app.ui.map.MapControls
import com.bivouac.app.ui.nav.AppSection
import com.bivouac.app.ui.nav.SectionMenuButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val PEEK_HEIGHT_EMPTY = 150.dp

internal enum class JournalDetailStop { SUMMARY, PROFILE, DETAIL }

internal fun settleJournalDetailStop(
    currentOffset: Float,
    velocity: Float,
    anchors: Map<JournalDetailStop, Float>,
): JournalDetailStop {
    if (abs(velocity) >= 900f) {
        val ordered = anchors.entries.sortedBy { it.value }
        val nearestIndex = ordered.indices.minBy { abs(ordered[it].value - currentOffset) }
        return if (velocity < 0f) {
            ordered[(nearestIndex - 1).coerceAtLeast(0)].key
        } else {
            ordered[(nearestIndex + 1).coerceAtMost(ordered.lastIndex)].key
        }
    }
    return anchors.minBy { abs(it.value - currentOffset) }.key
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun JournalScreen(
    modifier: Modifier = Modifier,
    currentSection: AppSection,
    onSectionSelected: (AppSection) -> Unit,
    // BIV-16: opened from Réglages ("Choisir les traces") instead of normally, to pick the
    // Sélection calibration's tracks — pre-checks the current choice and swaps the usual
    // "afficher sur la carte" action for a confirm/cancel pair that returns to Réglages.
    calibrationSelectionMode: Boolean = false,
    onCalibrationSelectionDone: () -> Unit = {},
    viewModel: JournalViewModel = viewModel(),
) {
    val context = LocalContext.current
    val calibrationSelectionActive by viewModel.calibrationSelectionActive.collectAsStateWithLifecycle()

    LaunchedEffect(calibrationSelectionMode) {
        if (calibrationSelectionMode) viewModel.enterCalibrationSelectionMode()
    }
    val filteredTracks by viewModel.filteredTracks.collectAsStateWithLifecycle()
    val tagsByTrackId by viewModel.tagsByTrackId.collectAsStateWithLifecycle()
    val selectedFilterTags by viewModel.selectedFilterTags.collectAsStateWithLifecycle()
    val currentTags by viewModel.currentTags.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedLayer by viewModel.selectedLayer.collectAsStateWithLifecycle()
    val importError by viewModel.importError.collectAsStateWithLifecycle()
    val nonFreeFeaturesDisabled by viewModel.nonFreeFeaturesDisabled.collectAsStateWithLifecycle()
    val probableDuplicate by viewModel.probableDuplicate.collectAsStateWithLifecycle()
    val deleteTarget by viewModel.deleteTarget.collectAsStateWithLifecycle()
    val selectionModeActive by viewModel.selectionModeActive.collectAsStateWithLifecycle()
    val selectedTrackIds by viewModel.selectedTrackIds.collectAsStateWithLifecycle()

    var recenterSignal by remember { mutableIntStateOf(0) }
    var mapBoxTopPx by remember { mutableIntStateOf(0) }
    val detail = uiState as? JournalUiState.Detail
    val multiTrack = uiState as? JournalUiState.MultiTrack
    // Overview list, multi-track view and single-track detail each report their own sheet's top
    // through onSheetTopMeasured into this single shared value — keyed on which of the three is
    // currently showing (and, for detail, which track) so switching between them starts the
    // height fresh at "unknown" instead of leaking the previous sheet's already-measured
    // position into the newly opened one's very first fit (that stale value being smaller than
    // the real one was making tracks open zoomed out far more than the visible area warranted).
    val sheetIdentity = when {
        detail != null -> "detail:${detail.entry.id}"
        multiTrack != null -> "multi"
        else -> "overview"
    }
    var sheetTopPx by remember(sheetIdentity) { mutableIntStateOf(Int.MAX_VALUE) }
    val visibleMapHeightPx = (sheetTopPx - mapBoxTopPx).let { if (it > 0) it else Int.MAX_VALUE }
    val pickGpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.importTrack(context.contentResolver, it) }
    }

    val coloredTracks = remember(multiTrack) { multiTrack?.entries?.let { assignTrackColors(it) }.orEmpty() }
    var highlightedTrackId by remember(multiTrack) { mutableStateOf<String?>(null) }
    val onToggleHighlight = { id: String -> highlightedTrackId = if (highlightedTrackId == id) null else id }
    var renameDialogVisible by remember { mutableStateOf(false) }
    var cursorIndex by remember(detail?.entry?.id) { mutableStateOf<Int?>(null) }
    if (detail == null) {
        BottomSheetScaffold(
            modifier = modifier,
            sheetPeekHeight = PEEK_HEIGHT_EMPTY.coerceAtMost(LocalConfiguration.current.screenHeightDp.dp * 0.5f),
            sheetContent = {
                if (multiTrack != null) {
                    JournalMultiTrackContent(
                        entries = multiTrack.entries,
                        coloredTracks = coloredTracks,
                        highlightedTrackId = highlightedTrackId,
                        onHighlightToggle = onToggleHighlight,
                        onCloseClick = viewModel::closeMultiTrack,
                        onSheetTopMeasured = { sheetTopPx = it },
                    )
                } else {
                    JournalListContent(
                        tracks = filteredTracks,
                        tagsByTrackId = tagsByTrackId,
                        selectedFilterTags = selectedFilterTags,
                        onToggleFilterTag = viewModel::toggleFilterTag,
                        onImportClick = { pickGpxLauncher.launch(arrayOf("*/*")) },
                        onTrackClick = viewModel::openTrack,
                        selectionModeActive = selectionModeActive,
                        selectedTrackIds = selectedTrackIds,
                        onEnterSelectionMode = viewModel::enterSelectionMode,
                        onExitSelectionMode = {
                            viewModel.exitSelectionMode()
                            if (calibrationSelectionActive) onCalibrationSelectionDone()
                        },
                        onToggleSelection = viewModel::toggleTrackSelection,
                        onToggleYearSelection = viewModel::toggleYearSelection,
                        onShowOnMap = viewModel::showOnMap,
                        calibrationSelectionActive = calibrationSelectionActive,
                        onConfirmCalibrationSelection = {
                            viewModel.confirmCalibrationSelection()
                            onCalibrationSelectionDone()
                        },
                        onSheetTopMeasured = { sheetTopPx = it },
                    )
                }
            },
        ) {
            JournalMap(
                track = null,
                selectedLayer = selectedLayer,
                recenterSignal = recenterSignal,
                visibleMapHeightPx = visibleMapHeightPx,
                currentSection = currentSection,
                onSectionSelected = onSectionSelected,
                onLayerSelected = viewModel::setSelectedLayer,
                onRecenter = { recenterSignal++ },
                onMapTopMeasured = { mapBoxTopPx = it },
                cursorIndex = null,
                onCursorChanged = {},
                multiTracks = coloredTracks,
                highlightedTrackId = highlightedTrackId,
                onTraceTapped = onToggleHighlight,
                nonFreeFeaturesDisabled = nonFreeFeaturesDisabled,
            )
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            JournalMap(
                track = detail.track,
                selectedLayer = selectedLayer,
                recenterSignal = recenterSignal,
                visibleMapHeightPx = visibleMapHeightPx,
                currentSection = currentSection,
                onSectionSelected = onSectionSelected,
                onLayerSelected = viewModel::setSelectedLayer,
                onRecenter = { recenterSignal++ },
                onMapTopMeasured = { mapBoxTopPx = it },
                cursorIndex = cursorIndex,
                onCursorChanged = { cursorIndex = it },
                nonFreeFeaturesDisabled = nonFreeFeaturesDisabled,
            )
            ThreeStopJournalDetail(
                entry = detail.entry,
                track = detail.track,
                onCloseClick = viewModel::closeTrack,
                onDeleteClick = viewModel::requestDelete,
                onSheetTopMeasured = { sheetTopPx = it },
                cursorIndex = cursorIndex,
                onCursorDragged = { cursorIndex = it },
                currentTags = currentTags,
                tagsByTrackId = tagsByTrackId,
                onSaveDetails = viewModel::saveDetails,
                onRenameClick = { renameDialogVisible = true },
            )
        }
    }

    if (renameDialogVisible && detail != null) {
        var name by remember(detail.entry.id) { mutableStateOf(detail.entry.name) }
        AlertDialog(
            onDismissRequest = { renameDialogVisible = false },
            title = { Text("Renommer la trace") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameCurrentTrack(name)
                    renameDialogVisible = false
                }) { Text("Enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogVisible = false }) { Text("Annuler") }
            },
        )
    }

    if (importError != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissImportError,
            title = { Text("Import impossible") },
            text = { Text(importError ?: "") },
            confirmButton = {
                TextButton(onClick = viewModel::dismissImportError) { Text("OK") }
            },
        )
    }

    probableDuplicate?.let { existing ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicateWarning,
            title = { Text("Trace peut-être déjà présente") },
            text = {
                Text(
                    "« ${existing.name} » (${formatStartedAtWithTime(existing.startedAt)}) a une date et une " +
                        "distance très proches. Importer quand même ?",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImportAnyway) { Text("Importer quand même") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDuplicateWarning) { Text("Annuler") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirmation,
            title = { Text("Supprimer cette trace ?") },
            text = { Text("« ${target.name} » sera définitivement supprimée du journal. Cette action est irréversible.") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteConfirmation) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun JournalMap(
    track: HikeTrack?,
    selectedLayer: com.bivouac.app.ui.map.MapLayer,
    recenterSignal: Int,
    visibleMapHeightPx: Int,
    currentSection: AppSection,
    onSectionSelected: (AppSection) -> Unit,
    onLayerSelected: (com.bivouac.app.ui.map.MapLayer) -> Unit,
    onRecenter: () -> Unit,
    onMapTopMeasured: (Int) -> Unit,
    cursorIndex: Int?,
    onCursorChanged: (Int) -> Unit,
    multiTracks: List<ColoredTrack> = emptyList(),
    highlightedTrackId: String? = null,
    onTraceTapped: (String) -> Unit = {},
    nonFreeFeaturesDisabled: Boolean = false,
) {
    Box(
        modifier = Modifier.fillMaxSize().onGloballyPositioned {
            onMapTopMeasured(it.positionInRoot().y.toInt())
        },
    ) {
        HikeMapView(
            track = track,
            bivouacPoints = emptyList(),
            selectedLayer = selectedLayer,
            recenterSignal = recenterSignal,
            visibleHeightPx = visibleMapHeightPx,
            onTrackTapped = onCursorChanged,
            onBivouacMoved = { _, _ -> },
            onBivouacDragPreview = { _, _ -> },
            cursorIndex = cursorIndex,
            onCursorChanged = onCursorChanged,
            multiTracks = multiTracks,
            highlightedTrackId = highlightedTrackId,
            onTraceTapped = onTraceTapped,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionMenuButton(current = currentSection, onSelect = onSectionSelected)
            MapControls(
                selectedLayer = selectedLayer,
                onLayerSelected = onLayerSelected,
                recenterEnabled = track != null || multiTracks.isNotEmpty(),
                onRecenterClick = onRecenter,
                nonFreeFeaturesDisabled = nonFreeFeaturesDisabled,
            )
        }
    }
}

@Composable
private fun JournalListContent(
    tracks: List<LoggedTrackEntity>,
    tagsByTrackId: Map<String, List<String>>,
    selectedFilterTags: Set<String>,
    onToggleFilterTag: (String) -> Unit,
    onImportClick: () -> Unit,
    onTrackClick: (LoggedTrackEntity) -> Unit,
    selectionModeActive: Boolean,
    selectedTrackIds: Set<String>,
    onEnterSelectionMode: (String) -> Unit,
    onExitSelectionMode: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onToggleYearSelection: (List<String>) -> Unit,
    onShowOnMap: () -> Unit,
    calibrationSelectionActive: Boolean = false,
    onConfirmCalibrationSelection: () -> Unit = {},
    onSheetTopMeasured: (Int) -> Unit,
) {
    val groups = remember(tracks) { groupByYear(tracks) }
    // null (not an empty set) means "no manual choice yet" — only then does the most recent year
    // default to expanded. Keying remember on `groups` would look tempting but resets this to the
    // default on every recomposition where the list content changes (e.g. a new import), silently
    // discarding whatever the user had toggled.
    var manuallyExpandedYears by remember { mutableStateOf<Set<Int>?>(null) }
    val expandedYears = manuallyExpandedYears ?: setOfNotNull(groups.firstOrNull()?.year)

    // System tags always offered (even before ever used) so the filter is discoverable; free tags
    // only once at least one track actually has them.
    val allFilterTags = remember(tagsByTrackId) {
        (SystemTag.entries.map { it.value } + tagsByTrackId.values.flatten()).distinct()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 40.dp)
            .onGloballyPositioned { onSheetTopMeasured(it.positionInRoot().y.toInt()) },
    ) {
        Button(onClick = onImportClick, modifier = Modifier.fillMaxWidth()) {
            Text("Importer une trace")
        }
        if (allFilterTags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                allFilterTags.forEach { tag ->
                    FilterChip(
                        selected = tag in selectedFilterTags,
                        onClick = { onToggleFilterTag(tag) },
                        label = { Text(tagLabel(tag)) },
                    )
                }
            }
        }
        if (tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (selectedFilterTags.isEmpty()) {
                        "Aucune trace dans le journal pour l'instant."
                    } else {
                        "Aucune trace ne correspond à ce filtre."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = if (calibrationSelectionActive) onConfirmCalibrationSelection else onShowOnMap,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    when {
                        calibrationSelectionActive -> "Confirmer la sélection (${selectedTrackIds.size})"
                        selectedTrackIds.isEmpty() -> "Tout afficher sur la carte"
                        else -> "Afficher la sélection (${selectedTrackIds.size})"
                    },
                )
            }
            if (selectionModeActive) {
                IconButton(onClick = onExitSelectionMode) {
                    Icon(Icons.Default.Close, contentDescription = "Annuler la sélection")
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        groups.forEach { group ->
            HorizontalDivider()
            YearHeader(
                group = group,
                expanded = group.year in expandedYears,
                selectionModeActive = selectionModeActive,
                selectionState = when {
                    group.entries.none { it.id in selectedTrackIds } -> ToggleableState.Off
                    group.entries.all { it.id in selectedTrackIds } -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                },
                onToggle = {
                    manuallyExpandedYears = if (group.year in expandedYears) {
                        expandedYears - group.year
                    } else {
                        expandedYears + group.year
                    }
                },
                onToggleSelection = { onToggleYearSelection(group.entries.map { it.id }) },
            )
            if (group.year in expandedYears) {
                group.entries.forEach { entry ->
                    HorizontalDivider()
                    JournalTrackRow(
                        entry = entry,
                        selectionModeActive = selectionModeActive,
                        selected = entry.id in selectedTrackIds,
                        onClick = { onTrackClick(entry) },
                        onToggleSelection = { onToggleSelection(entry.id) },
                        onLongClick = {
                            if (selectionModeActive) onToggleSelection(entry.id) else onEnterSelectionMode(entry.id)
                        },
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

private data class YearGroup(
    val year: Int,
    val entries: List<LoggedTrackEntity>,
    val totalStats: TrackStats,
)

private fun groupByYear(tracks: List<LoggedTrackEntity>): List<YearGroup> {
    val zone = ZoneId.systemDefault()
    return tracks
        .groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).year }
        .toSortedMap(compareByDescending { it })
        .map { (year, entries) ->
            YearGroup(
                year = year,
                entries = entries,
                totalStats = TrackStats(
                    distanceMeters = entries.sumOf { it.distanceMeters },
                    elevationGainMeters = entries.sumOf { it.elevationGainMeters },
                    elevationLossMeters = entries.sumOf { it.elevationLossMeters },
                    pointCount = 0,
                    estimatedDurationMinutes = entries.sumOf { it.estimatedDurationMinutes },
                ),
            )
        }
}

// Muted, like Planification's own "Total" row once a trace has several segments (StatsRows with
// muted = true there too) — color is reserved for a single hike's own stats, not a roll-up of
// several, so the two read as visually distinct levels rather than blurring together.
@Composable
private fun YearHeader(
    group: YearGroup,
    expanded: Boolean,
    selectionModeActive: Boolean,
    selectionState: ToggleableState,
    onToggle: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 20.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // A dedicated checkbox rather than making the whole header row selectable (unlike
            // individual entries below) — the header already owns the expand/collapse tap zone.
            // Tri-state: some-but-not-all of the year's entries selected shows as indeterminate,
            // rather than snapping to either extreme.
            if (selectionModeActive) {
                TriStateCheckbox(
                    state = selectionState,
                    onClick = onToggleSelection,
                    modifier = Modifier.size(24.dp),
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${group.year} · ${group.entries.size} rando${if (group.entries.size > 1) "s" else ""}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Column(modifier = Modifier.padding(start = 24.dp, top = 2.dp)) {
            StatsRows(group.totalStats, muted = true)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JournalTrackRow(
    entry: LoggedTrackEntity,
    selectionModeActive: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selectionModeActive) onToggleSelection() else onClick() },
                onLongClick = onLongClick,
            )
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (selectionModeActive) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelection() }, modifier = Modifier.padding(top = 2.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = formatStartedAt(entry.startedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            StatsRows(entry.toTrackStats())
        }
    }
}

// BIV-48: rotates per *selection* (first picked = first color), not hashed like tag colors —
// a trace has no fixed identity color here, it just gets "the next one" among whatever else is
// shown alongside it this time. Deliberately a separate palette from tags (see tagColor below):
// tag colors must stay stable across views, these don't need to and shouldn't imply one.
private val MultiTracePalette = listOf(
    Color(0xFF4A7FBF), Color(0xFFC2588E), Color(0xFF5A8F3C), Color(0xFFB8860B),
    Color(0xFF8A6A4B), Color(0xFF00838F), Color(0xFF7E57C2), Color(0xFFD84315),
)

// Matches marker_tail_neutral (colors.xml) — same "no per-item meaning" neutral used once there
// are too many traces for a legend to stay readable.
private val MultiTraceNeutralColor = Color(0xFF616161)

private fun assignTrackColors(entries: List<Pair<LoggedTrackEntity, HikeTrack>>): List<ColoredTrack> =
    if (entries.size <= MultiTracePalette.size) {
        entries.mapIndexed { i, (entry, track) -> ColoredTrack(entry.id, track, MultiTracePalette[i]) }
    } else {
        entries.map { (entry, track) -> ColoredTrack(entry.id, track, MultiTraceNeutralColor) }
    }

@Composable
private fun JournalMultiTrackContent(
    entries: List<Pair<LoggedTrackEntity, HikeTrack>>,
    coloredTracks: List<ColoredTrack>,
    highlightedTrackId: String?,
    onHighlightToggle: (String) -> Unit,
    onCloseClick: () -> Unit,
    onSheetTopMeasured: (Int) -> Unit,
) {
    val showLegend = entries.size <= MultiTracePalette.size
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 40.dp)
            .onGloballyPositioned { onSheetTopMeasured(it.positionInRoot().y.toInt()) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${entries.size} trace${if (entries.size > 1) "s" else ""} affichée${if (entries.size > 1) "s" else ""}",
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = onCloseClick) {
                Icon(Icons.Default.Close, contentDescription = "Fermer")
            }
        }
        if (showLegend) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                entries.forEachIndexed { i, (entry, _) ->
                    val color = coloredTracks[i].color
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onHighlightToggle(entry.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(color, RoundedCornerShape(50)),
                        )
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (highlightedTrackId == entry.id) color else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        } else {
            Text(
                text = "Trop de traces pour une légende détaillée — affichage uniforme.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// internal rather than private: exercised directly by BivouacDatabaseMigrationTest's sibling,
// ThreeStopJournalDetailDirtyIndicatorTest (androidTest), to test the isDirty save-icon states
// without driving the whole Journal screen through a real ViewModel.
@Composable
internal fun ThreeStopJournalDetail(
    entry: LoggedTrackEntity,
    track: HikeTrack,
    onCloseClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onSheetTopMeasured: (Int) -> Unit,
    cursorIndex: Int?,
    onCursorDragged: (Int) -> Unit,
    currentTags: List<String>,
    tagsByTrackId: Map<String, List<String>>,
    onSaveDetails: (Set<String>, String) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        var isEditing by remember(entry.id) { mutableStateOf(false) }
        var draftTags by remember(entry.id) { mutableStateOf(currentTags.toSet()) }
        var draftNote by remember(entry.id) { mutableStateOf(entry.note) }
        var newTagText by remember(entry.id) { mutableStateOf("") }
        var pendingExit by remember(entry.id) { mutableStateOf<(() -> Unit)?>(null) }
        val knownFreeTags = remember(tagsByTrackId) {
            tagsByTrackId.values.flatten()
                .filterNot { tag -> SystemTag.entries.any { it.value == tag } }
                .distinct()
        }
        val isDirty = draftTags != currentTags.toSet() || draftNote != entry.note

        fun beginEditing() {
            draftTags = currentTags.toSet()
            draftNote = entry.note
            isEditing = true
        }

        fun requestExit(exit: () -> Unit) {
            if (isEditing && isDirty) pendingExit = exit else {
                isEditing = false
                exit()
            }
        }

        fun saveAndStopEditing() {
            if (isDirty) onSaveDetails(draftTags, draftNote)
            isEditing = false
        }

        fun toggleSystemDraftTag(systemTag: SystemTag) {
            val exclusiveWith = when (systemTag) {
                SystemTag.SOLO -> SystemTag.GROUPE
                SystemTag.GROUPE -> SystemTag.SOLO
                SystemTag.EXTREME -> null
            }
            val activating = systemTag.value !in draftTags
            draftTags = when {
                activating && exclusiveWith != null -> (draftTags - exclusiveWith.value) + systemTag.value
                activating -> draftTags + systemTag.value
                else -> draftTags - systemTag.value
            }
        }

        BackHandler { requestExit(onCloseClick) }
        val fullHeightPx = with(density) { maxHeight.toPx() }
        var measuredSummaryHeightPx by remember(entry.id) { mutableIntStateOf(0) }
        val fallbackSummaryHeightPx = with(density) { 150.dp.toPx() }
        val navigationBarHeightPx = WindowInsets.navigationBars.getBottom(density).toFloat()
        val summaryHeightPx = (if (measuredSummaryHeightPx > 0) {
            measuredSummaryHeightPx.toFloat() + navigationBarHeightPx + with(density) { 8.dp.toPx() }
        } else {
            fallbackSummaryHeightPx + navigationBarHeightPx
        }).coerceAtMost(fullHeightPx * 0.46f)
        val profileHeightPx = maxOf(
            summaryHeightPx + with(density) { 102.dp.toPx() },
            fullHeightPx * 0.39f,
        )
            .coerceIn(summaryHeightPx, fullHeightPx * 0.67f)
        val detailHeightPx = fullHeightPx
        val anchors = remember(fullHeightPx, summaryHeightPx, profileHeightPx, detailHeightPx) {
            mapOf(
                JournalDetailStop.DETAIL to fullHeightPx - detailHeightPx,
                JournalDetailStop.PROFILE to fullHeightPx - profileHeightPx,
                JournalDetailStop.SUMMARY to fullHeightPx - summaryHeightPx,
            )
        }
        var stop by remember(entry.id) { mutableStateOf(JournalDetailStop.PROFILE) }
        val offset = remember(entry.id) { Animatable(anchors.getValue(JournalDetailStop.PROFILE)) }

        LaunchedEffect(anchors, entry.id) {
            offset.snapTo(anchors.getValue(JournalDetailStop.PROFILE))
            stop = JournalDetailStop.PROFILE
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
        val detailNestedScroll = remember(entry.id, anchors) {
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
            shape = if (offset.value <= 1f) {
                RectangleShape
            } else {
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            },
            tonalElevation = 3.dp,
            shadowElevation = 10.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(
                    modifier = Modifier.height(
                        with(density) { (statusBarHeightPx * detailExpansion).toDp() },
                    ),
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
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(text = entry.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = formatStartedAt(entry.startedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        JournalDetailMenu(onRenameClick = onRenameClick, onDeleteClick = onDeleteClick)
                        IconButton(onClick = { requestExit(onCloseClick) }) {
                            Icon(Icons.Default.Close, contentDescription = "Fermer")
                        }
                    }
                    StatsRows(entry.toTrackStats())
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
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
                    bivouacPoints = emptyList(),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    cursorIndex = cursorIndex,
                    onCursorDragged = onCursorDragged,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .nestedScroll(detailNestedScroll)
                        .verticalScroll(detailScrollState)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Tags", style = MaterialTheme.typography.titleSmall)
                        if (isEditing) {
                            IconButton(onClick = { saveAndStopEditing() }) {
                                Icon(
                                    Icons.Default.Save,
                                    contentDescription = if (isDirty) {
                                        "Enregistrer (modifications non sauvegardées)"
                                    } else {
                                        "Enregistrer"
                                    },
                                    tint = if (isDirty) GainIconColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            IconButton(onClick = { beginEditing() }) {
                                Icon(Icons.Default.Edit, contentDescription = "Modifier")
                            }
                        }
                    }
                    if (isEditing) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SystemTag.entries.forEach { systemTag ->
                                val color = tagColor(systemTag.value)
                                FilterChip(
                                    selected = systemTag.value in draftTags,
                                    onClick = { toggleSystemDraftTag(systemTag) },
                                    label = { Text(systemTag.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = color.copy(alpha = 0.14f),
                                        selectedLabelColor = color,
                                    ),
                                )
                            }
                        }
                        val freeTags = draftTags.filterNot { tag -> SystemTag.entries.any { it.value == tag } }
                        if (freeTags.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                freeTags.forEach { tag ->
                                    FilterChip(
                                        selected = true,
                                        onClick = { draftTags = draftTags - tag },
                                        label = { Text(tag) },
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextField(
                                value = newTagText,
                                onValueChange = { newTagText = it },
                                placeholder = { Text("Ajouter un tag") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    val trimmed = newTagText.trim()
                                    if (trimmed.isNotEmpty()) draftTags = draftTags + trimmed
                                    newTagText = ""
                                },
                                enabled = newTagText.isNotBlank(),
                            ) { Text("Ajouter") }
                        }
                        val suggestedTags = knownFreeTags.filterNot { it in draftTags }
                        if (suggestedTags.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                suggestedTags.forEach { tag ->
                                    FilterChip(
                                        selected = false,
                                        onClick = { draftTags = draftTags + tag },
                                        label = { Text(tag) },
                                    )
                                }
                            }
                        }
                    } else if (currentTags.isEmpty()) {
                        NotebookEmptyHint()
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            currentTags.forEach { tag -> ReadOnlyTagChip(tagLabel(tag), tagColor(tag)) }
                        }
                    }
                    Text("Notes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    if (isEditing) {
                        TextField(
                            value = draftNote,
                            onValueChange = { draftNote = it },
                            visualTransformation = BulletVisualTransformation,
                            placeholder = { Text("Quelques mots sur cette rando…") },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (entry.note.isBlank()) {
                        NotebookEmptyHint()
                    } else {
                        Text(withBullets(entry.note), style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                    Text("Photos", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Emplacement réservé — bientôt disponible",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    Text("Autres contenus", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Cet espace accueillera les futurs enrichissements du Journal.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(36.dp))
                }
            }
        }

        pendingExit?.let { exit ->
            AlertDialog(
                onDismissRequest = { pendingExit = null },
                title = { Text("Modifications non enregistrées") },
                text = { Text("Les tags et la note ont des modifications non enregistrées.") },
                confirmButton = {
                    TextButton(onClick = {
                        saveAndStopEditing()
                        pendingExit = null
                        exit()
                    }) { Text("Enregistrer") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { pendingExit = null }) { Text("Annuler") }
                        TextButton(onClick = {
                            isEditing = false
                            pendingExit = null
                            exit()
                        }) { Text("Ne pas enregistrer", color = MaterialTheme.colorScheme.error) }
                    }
                },
            )
        }
    }
}

private val SoloColor = Color(0xFF7C6FCC)
private val GroupeColor = Color(0xFF4FA8A0)
private val ExtremeColor = Color(0xFFC0392B)
private val FreeTagPalette = listOf(
    Color(0xFF4A7FBF), Color(0xFFB8860B), Color(0xFFC2588E), Color(0xFF5A8F3C), Color(0xFF8A6A4B),
)

private fun tagColor(tag: String): Color = when (tag) {
    SystemTag.SOLO.value -> SoloColor
    SystemTag.GROUPE.value -> GroupeColor
    SystemTag.EXTREME.value -> ExtremeColor
    else -> FreeTagPalette[(tag.hashCode() and 0x7fffffff) % FreeTagPalette.size]
}

private fun tagLabel(value: String): String = SystemTag.entries.find { it.value == value }?.label ?: value

@Composable
private fun ReadOnlyTagChip(label: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .border(1.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun NotebookEmptyHint() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
        Text(
            "Appuie sur l'icône pour ajouter des détails.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun withBullets(text: String): String = text.lines().joinToString("\n") { line ->
    val trimmed = line.trimStart(' ', '\t')
    if (trimmed.startsWith("-")) " • ${trimmed.removePrefix("-").trimStart(' ', '\t')}" else line
}

@Composable
private fun JournalDetailMenu(onRenameClick: () -> Unit, onDeleteClick: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Renommer") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = { expanded = false; onRenameClick() },
            )
            DropdownMenuItem(
                text = { Text("Dupliquer vers la planification") },
                enabled = false,
                onClick = { expanded = false },
            )
            DropdownMenuItem(
                text = { Text("Supprimer") },
                leadingIcon = {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { expanded = false; onDeleteClick() },
            )
        }
    }
}

private fun formatStartedAt(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

private fun formatStartedAtWithTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH:mm", Locale.FRANCE)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

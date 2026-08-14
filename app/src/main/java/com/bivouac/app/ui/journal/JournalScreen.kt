package com.bivouac.app.ui.journal

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.gpx.TrackStats
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.journal.JournalUiState
import com.bivouac.app.journal.JournalViewModel
import com.bivouac.app.ui.components.ElevationProfile
import com.bivouac.app.ui.components.StatsRows
import com.bivouac.app.ui.map.HikeMapView
import com.bivouac.app.ui.map.MapControls
import com.bivouac.app.ui.nav.AppSection
import com.bivouac.app.ui.nav.SectionMenuButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PEEK_HEIGHT_EMPTY = 150.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    modifier: Modifier = Modifier,
    currentSection: AppSection,
    onSectionSelected: (AppSection) -> Unit,
    viewModel: JournalViewModel = viewModel(),
) {
    val context = LocalContext.current
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedLayer by viewModel.selectedLayer.collectAsStateWithLifecycle()
    val importError by viewModel.importError.collectAsStateWithLifecycle()
    val probableDuplicate by viewModel.probableDuplicate.collectAsStateWithLifecycle()
    val deleteTarget by viewModel.deleteTarget.collectAsStateWithLifecycle()

    var recenterSignal by remember { mutableIntStateOf(0) }
    var loadedPeekHeightPx by remember { mutableIntStateOf(0) }
    var mapBoxTopPx by remember { mutableIntStateOf(0) }
    var sheetTopPx by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val visibleMapHeightPx = (sheetTopPx - mapBoxTopPx).let { if (it > 0) it else Int.MAX_VALUE }
    val density = LocalDensity.current
    val loadedPeekHeight = with(density) { loadedPeekHeightPx.toDp() }

    val pickGpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.importTrack(context.contentResolver, it) }
    }

    val detail = uiState as? JournalUiState.Detail
    val maxPeekHeight = LocalConfiguration.current.screenHeightDp.dp * 0.5f

    BottomSheetScaffold(
        modifier = modifier,
        sheetPeekHeight = if (detail != null) {
            loadedPeekHeight.coerceIn(PEEK_HEIGHT_EMPTY, maxPeekHeight)
        } else {
            PEEK_HEIGHT_EMPTY.coerceAtMost(maxPeekHeight)
        },
        sheetContent = {
            if (detail != null) {
                JournalDetailContent(
                    entry = detail.entry,
                    track = detail.track,
                    onCloseClick = viewModel::closeTrack,
                    onDeleteClick = viewModel::requestDelete,
                    onPeekHeightMeasured = { loadedPeekHeightPx = it },
                    onSheetTopMeasured = { sheetTopPx = it },
                )
            } else {
                JournalListContent(
                    tracks = tracks,
                    onImportClick = { pickGpxLauncher.launch(arrayOf("*/*")) },
                    onTrackClick = viewModel::openTrack,
                    onSheetTopMeasured = { sheetTopPx = it },
                )
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { mapBoxTopPx = it.positionInRoot().y.toInt() },
        ) {
            HikeMapView(
                track = detail?.track,
                bivouacPoints = emptyList(),
                selectedLayer = selectedLayer,
                recenterSignal = recenterSignal,
                visibleHeightPx = visibleMapHeightPx,
                onTrackTapped = {},
                onBivouacMoved = { _, _ -> },
                onBivouacDragPreview = { _, _ -> },
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionMenuButton(current = currentSection, onSelect = onSectionSelected)
                MapControls(
                    selectedLayer = selectedLayer,
                    onLayerSelected = viewModel::setSelectedLayer,
                    recenterEnabled = detail != null,
                    onRecenterClick = { recenterSignal++ },
                )
            }
        }
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
private fun JournalListContent(
    tracks: List<LoggedTrackEntity>,
    onImportClick: () -> Unit,
    onTrackClick: (LoggedTrackEntity) -> Unit,
    onSheetTopMeasured: (Int) -> Unit,
) {
    val groups = remember(tracks) { groupByYear(tracks) }
    // null (not an empty set) means "no manual choice yet" — only then does the most recent year
    // default to expanded. Keying remember on `groups` would look tempting but resets this to the
    // default on every recomposition where the list content changes (e.g. a new import), silently
    // discarding whatever the user had toggled.
    var manuallyExpandedYears by remember { mutableStateOf<Set<Int>?>(null) }
    val expandedYears = manuallyExpandedYears ?: setOfNotNull(groups.firstOrNull()?.year)

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
        if (tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "Aucune trace dans le journal pour l'instant.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        Spacer(modifier = Modifier.height(16.dp))
        groups.forEach { group ->
            HorizontalDivider()
            YearHeader(
                group = group,
                expanded = group.year in expandedYears,
                onToggle = {
                    manuallyExpandedYears = if (group.year in expandedYears) {
                        expandedYears - group.year
                    } else {
                        expandedYears + group.year
                    }
                },
            )
            if (group.year in expandedYears) {
                group.entries.forEach { entry ->
                    HorizontalDivider()
                    JournalTrackRow(entry, onClick = { onTrackClick(entry) })
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
private fun YearHeader(group: YearGroup, expanded: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 20.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

@Composable
private fun JournalTrackRow(entry: LoggedTrackEntity, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
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

@Composable
private fun JournalDetailContent(
    entry: LoggedTrackEntity,
    track: HikeTrack,
    onCloseClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onPeekHeightMeasured: (Int) -> Unit,
    onSheetTopMeasured: (Int) -> Unit,
) {
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
        Column(modifier = Modifier.onGloballyPositioned { onPeekHeightMeasured(it.size.height) }) {
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
                JournalDetailMenu(onDeleteClick = onDeleteClick)
                IconButton(onClick = onCloseClick) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer")
                }
            }
            StatsRows(entry.toTrackStats())

            ElevationProfile(
                points = track.points,
                bivouacPoints = emptyList(),
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )
        }

        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text("Détails (photos, notes) — bientôt disponible")
        }
    }
}

@Composable
private fun JournalDetailMenu(onDeleteClick: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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

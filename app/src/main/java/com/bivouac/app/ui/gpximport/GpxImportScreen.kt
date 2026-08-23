package com.bivouac.app.ui.gpximport

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bivouac.app.R
import com.bivouac.app.data.db.BankedTrackEntity
import com.bivouac.app.data.gpx.GpxExporter
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.TrackStatsCalculator
import com.bivouac.app.data.weather.MeteoblueLink
import com.bivouac.app.gpximport.CloseConfirmationReason
import com.bivouac.app.gpximport.GpxImportUiState
import com.bivouac.app.gpximport.GpxImportViewModel
import com.bivouac.app.journal.DuplicatePlanRequest
import com.bivouac.app.ui.components.StatsRows
import com.bivouac.app.ui.map.HikeMapView
import com.bivouac.app.ui.map.MapControls
import com.bivouac.app.ui.nav.AppSection
import com.bivouac.app.ui.nav.SectionMenuButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PEEK_HEIGHT_EMPTY = 150.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpxImportScreen(
    modifier: Modifier = Modifier,
    incomingGpxUri: Uri? = null,
    // RIC-104 : un fichier reçu de l'extérieur attend encore le choix d'univers (dialogue affiché
    // par MainActivity) — le repli « restaurer la dernière trace » doit patienter jusque-là, sans
    // quoi il se déclenche avant que incomingGpxUri n'ait eu la chance d'être renseigné.
    hasPendingExternalChoice: Boolean = false,
    currentSection: AppSection,
    onSectionSelected: (AppSection) -> Unit,
    // RIC-40 : posé par « Dupliquer vers la planification » côté Journal, consommé une seule fois
    // ici (voir la boîte aux lettres dans MainActivity).
    pendingDuplicate: DuplicatePlanRequest? = null,
    onPendingDuplicateConsumed: () -> Unit = {},
    viewModel: GpxImportViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val bivouacPoints by viewModel.bivouacPoints.collectAsStateWithLifecycle()
    val effectiveBivouacPoints by viewModel.effectiveBivouacPoints.collectAsStateWithLifecycle()
    val segments by viewModel.segments.collectAsStateWithLifecycle()

    val dirty by viewModel.dirty.collectAsStateWithLifecycle()
    val currentBankedId by viewModel.currentBankedId.collectAsStateWithLifecycle()
    val bankedTraces by viewModel.bankedTraces.collectAsStateWithLifecycle()
    val nameDialogRequest by viewModel.nameDialogRequest.collectAsStateWithLifecycle()
    val closeConfirmationReason by viewModel.closeConfirmationReason.collectAsStateWithLifecycle()
    val deleteTarget by viewModel.deleteTarget.collectAsStateWithLifecycle()

    val selectedLayer by viewModel.selectedLayer.collectAsStateWithLifecycle()
    val nonFreeFeaturesDisabled by viewModel.nonFreeFeaturesDisabled.collectAsStateWithLifecycle()
    val activeCalibration by viewModel.activeCalibration.collectAsStateWithLifecycle()
    var recenterSignal by remember { mutableIntStateOf(0) }

    // Recentering should fit the track into whatever the sheet doesn't currently cover, not the
    // full (partly hidden) map view — osmdroid has no asymmetric-fit API, so this is done by
    // measuring both the map's and the sheet's actual on-screen position and re-centering
    // manually afterwards (see fitToTrack). Float.MAX_VALUE sentinel = not measured yet / no sheet
    // overlap known, meaning "behave as before".
    var mapBoxTopPx by remember { mutableFloatStateOf(0f) }
    var sheetTopPx by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val visibleMapHeightPx = (sheetTopPx - mapBoxTopPx).let { if (it.isFinite() && it > 0) it.toInt() else Int.MAX_VALUE }

    // RIC-40 : se déclenche une fois par demande entrante (identité de la requête en clé, remise à
    // null par l'appelant juste après) — c'est openDuplicateFromLoggedTrack qui décide s'il peut
    // charger tout de suite ou s'il doit d'abord passer par la confirmation de fermeture.
    LaunchedEffect(pendingDuplicate) {
        val request = pendingDuplicate ?: return@LaunchedEffect
        viewModel.openDuplicateFromLoggedTrack(request.track, request.bivouacPoints, request.suggestedName)
        onPendingDuplicateConsumed()
    }

    // A rotation destroys and recreates the Activity, so onCreate re-evaluates the incoming
    // intent's URI and this effect fires again with the same (non-null) value — only guarding on
    // Idle stops that replay from re-importing (and wiping bivouac points) on every rotation. An
    // explicit incoming GPX (opened from another app) always wins over whatever was saved from
    // the previous session; otherwise, restore that previous trace so a restart doesn't lose it.
    //
    // Une duplication en attente (RIC-40) court-circuite les deux : arriver ici avec une trace du
    // Journal à dupliquer est un choix explicite, il ne doit pas se faire écraser par la trace de
    // la session précédente que l'effet ci-dessus restaurerait en parallèle.
    LaunchedEffect(incomingGpxUri, hasPendingExternalChoice) {
        if (uiState is GpxImportUiState.Idle && pendingDuplicate == null && !hasPendingExternalChoice) {
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

    val loaded = uiState as? GpxImportUiState.Loaded
    if (loaded == null) {
        BottomSheetScaffold(
            modifier = modifier,
            sheetPeekHeight = PEEK_HEIGHT_EMPTY.coerceAtMost(maxPeekHeight),
            sheetContent = {
                TrackSheetContent(
                    uiState = uiState,
                    bankedTraces = bankedTraces,
                    activeCalibration = activeCalibration,
                    onOpenClick = { pickGpxLauncher.launch(arrayOf("*/*")) },
                    onOpenBankedClick = viewModel::openFromBank,
                    onRenameBankedClick = viewModel::requestRenameFromList,
                    onDeleteBankedClick = viewModel::requestDeleteFromList,
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
                    track = null,
                    bivouacPoints = emptyList(),
                    selectedLayer = selectedLayer,
                    recenterSignal = recenterSignal,
                    visibleHeightPx = visibleMapHeightPx,
                    onTrackTapped = viewModel::addBivouacPoint,
                    onBivouacMoved = viewModel::moveBivouacPoint,
                    onBivouacDragPreview = viewModel::previewBivouacDrag,
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
                        recenterEnabled = false,
                        onRecenterClick = { recenterSignal++ },
                        nonFreeFeaturesDisabled = nonFreeFeaturesDisabled,
                    )
                }
            }
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { mapBoxTopPx = it.positionInRoot().y },
        ) {
            HikeMapView(
                track = loaded.track,
                bivouacPoints = bivouacPoints,
                selectedLayer = selectedLayer,
                recenterSignal = recenterSignal,
                visibleHeightPx = visibleMapHeightPx,
                onTrackTapped = viewModel::addBivouacPoint,
                onBivouacMoved = viewModel::moveBivouacPoint,
                onBivouacDragPreview = viewModel::previewBivouacDrag,
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
                    recenterEnabled = true,
                    onRecenterClick = { recenterSignal++ },
                    nonFreeFeaturesDisabled = nonFreeFeaturesDisabled,
                )
            }
            ThreeStopPlanificationDetail(
                track = loaded.track,
                // loaded.stats is a snapshot from whenever the trace was opened/imported — distance
                // and elevation don't change, but the duration needs to track the *current*
                // calibration for a trace that's still open while it's changed in Réglages (BIV-16
                // recette: this was already handled for segments below, missed here).
                stats = TrackStatsCalculator.recomputeDuration(loaded.stats, activeCalibration),
                bivouacPoints = bivouacPoints,
                elevationMarkerPoints = effectiveBivouacPoints,
                segments = segments,
                dirty = dirty,
                isBanked = currentBankedId != null,
                onCloseClick = viewModel::requestClose,
                onSaveClick = viewModel::requestSave,
                onRenameClick = viewModel::requestRename,
                onDuplicateClick = viewModel::requestDuplicate,
                onDeleteClick = viewModel::requestDelete,
                onRemovePoint = viewModel::removeBivouacPoint,
                onExportSegment = { index, segment ->
                    val baseName = loaded.track.name ?: "Trace"
                    val dayName = "$baseName - Jour ${index + 1}"
                    context.startActivity(GpxExporter.openIntent(context, segment.points, dayName))
                },
                onWeatherClick = { point ->
                    val url = MeteoblueLink.forCoordinates(point.latitude, point.longitude)
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                nonFreeFeaturesDisabled = nonFreeFeaturesDisabled,
                onSheetTopMeasured = { sheetTopPx = it.toFloat() },
            )
        }
    }

    closeConfirmationReason?.let { reason ->
        val (title, message) = when (reason) {
            CloseConfirmationReason.DIRTY ->
                "Trace modifiée" to "Cette trace a des modifications non enregistrées."
            CloseConfirmationReason.NEVER_SAVED ->
                "Trace non enregistrée" to "Attention, cette trace n'a pas encore été enregistrée dans Bivouac."
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissCloseConfirmation,
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::saveAndClose) { Text("Enregistrer") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::dismissCloseConfirmation) { Text("Annuler") }
                    TextButton(onClick = viewModel::discardAndClose) {
                        Text("Ne pas enregistrer", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }

    nameDialogRequest?.let { request ->
        var name by remember(request) { mutableStateOf(request.suggestedName) }
        AlertDialog(
            onDismissRequest = viewModel::dismissNameDialog,
            title = { Text("Nommer la trace") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmNameDialog(name) }) { Text("Enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissNameDialog) { Text("Annuler") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirmation,
            title = { Text("Supprimer cette trace ?") },
            text = { Text("« ${target.name} » sera définitivement supprimée. Cette action est irréversible.") },
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

// Only Idle/Loading/Error: the Loaded state has its own three-stop drawer (BIV-57), see
// ThreeStopPlanificationDetail — GpxImportScreen switches away from this BottomSheetScaffold
// entirely once a track is loaded, so this sheet never needs to represent that state.
@Composable
private fun TrackSheetContent(
    uiState: GpxImportUiState,
    bankedTraces: List<BankedTrackEntity>,
    activeCalibration: SpeedCalibration,
    onOpenClick: () -> Unit,
    onOpenBankedClick: (String) -> Unit,
    onRenameBankedClick: (id: String, name: String) -> Unit,
    onDeleteBankedClick: (id: String, name: String) -> Unit,
    onSheetTopMeasured: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 40.dp)
            .onGloballyPositioned { onSheetTopMeasured(it.positionInRoot().y) },
    ) {
        when (uiState) {
            is GpxImportUiState.Idle -> {
                // RIC-105 : même prestance que le CTA du tout premier lancement du Journal tant
                // que la banque est vide (seule action possible ici) ; une fois qu'elle ne l'est
                // plus, le bouton rétrécit vers un extended FAB en flux — pas flottant, ce tiroir
                // a déjà sa liste juste en dessous, un vrai FAB ferait doublon.
                if (bankedTraces.isEmpty()) {
                    Button(onClick = onOpenClick, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ouvrir une trace")
                    }
                } else {
                    ExtendedFloatingActionButton(
                        onClick = onOpenClick,
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("Ouvrir une trace") },
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                bankedTraces.forEach { entry ->
                    HorizontalDivider()
                    BankedTrackRow(
                        entry = entry,
                        activeCalibration = activeCalibration,
                        onClick = { onOpenBankedClick(entry.id) },
                        onRename = { onRenameBankedClick(entry.id, entry.name) },
                        onDelete = { onDeleteBankedClick(entry.id, entry.name) },
                    )
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
            is GpxImportUiState.Loaded -> Unit
        }
    }
}

@Composable
private fun BankedTrackRow(
    entry: BankedTrackEntity,
    activeCalibration: SpeedCalibration,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val bivouacCount = entry.bivouacTrackPointIndices.split(",").count { it.isNotBlank() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)) {
                Text(text = entry.name, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = formatSavedAt(entry.savedAt) + if (bivouacCount > 0) " · $bivouacCount" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (bivouacCount > 0) {
                        Image(
                            painter = painterResource(R.drawable.ic_bivouac_badge),
                            contentDescription = "point${if (bivouacCount != 1) "s" else ""} de bivouac",
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu", modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Renommer") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuExpanded = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Supprimer") },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        }
        StatsRows(TrackStatsCalculator.recomputeDuration(entry.toTrackStats(), activeCalibration))
    }
}

// "aujourd'hui à 14:32" when saved today (the realistic case for several saves the same day —
// disambiguates them without cluttering older entries with a time nobody needs), otherwise just
// the date ("3 août").
private fun formatSavedAt(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val instant = Instant.ofEpochMilli(epochMillis)
    return if (instant.atZone(zone).toLocalDate() == LocalDate.now(zone)) {
        "aujourd'hui à " + DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(instant)
    } else {
        DateTimeFormatter.ofPattern("d MMMM", Locale.FRANCE).withZone(zone).format(instant)
    }
}

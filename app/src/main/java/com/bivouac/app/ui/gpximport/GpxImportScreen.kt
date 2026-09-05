package com.bivouac.app.ui.gpximport

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Route
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
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
import com.bivouac.app.ui.components.FullScreenEmptyState
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
import kotlinx.coroutines.flow.first

private val PEEK_HEIGHT_EMPTY = 150.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpxImportScreen(
    modifier: Modifier = Modifier,
    incomingGpxUri: Uri? = null,
    // RIC-104 : un fichier reçu de l'extérieur attend encore le choix d'univers (dialogue affiché
    // par MainActivity) : le repli « restaurer la dernière trace » doit patienter jusque-là, sans
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val bivouacPoints by viewModel.bivouacPoints.collectAsStateWithLifecycle()
    val effectiveBivouacPoints by viewModel.effectiveBivouacPoints.collectAsStateWithLifecycle()
    val segments by viewModel.segments.collectAsStateWithLifecycle()

    val dirty by viewModel.dirty.collectAsStateWithLifecycle()
    val currentBankedId by viewModel.currentBankedId.collectAsStateWithLifecycle()
    val bankedTraces by viewModel.bankedTraces.collectAsStateWithLifecycle()
    val bankedTracesLoaded by viewModel.bankedTracesLoaded.collectAsStateWithLifecycle()
    val nameDialogRequest by viewModel.nameDialogRequest.collectAsStateWithLifecycle()
    val closeConfirmationReason by viewModel.closeConfirmationReason.collectAsStateWithLifecycle()
    val deleteTarget by viewModel.deleteTarget.collectAsStateWithLifecycle()
    val bankOpenError by viewModel.bankOpenError.collectAsStateWithLifecycle()

    val selectedLayer by viewModel.selectedLayer.collectAsStateWithLifecycle()
    val nonFreeFeaturesDisabled by viewModel.nonFreeFeaturesDisabled.collectAsStateWithLifecycle()
    val activeCalibration by viewModel.activeCalibration.collectAsStateWithLifecycle()
    var recenterSignal by remember { mutableIntStateOf(0) }

    // Recentering should fit the track into whatever the sheet doesn't currently cover, not the
    // full (partly hidden) map view: osmdroid has no asymmetric-fit API, so this is done by
    // measuring both the map's and the sheet's actual on-screen position and re-centering
    // manually afterwards (see fitToTrack). Float.MAX_VALUE sentinel = not measured yet / no sheet
    // overlap known, meaning "behave as before".
    var mapBoxTopPx by remember { mutableFloatStateOf(0f) }
    // RIC-96 : ce screen bascule entre deux tiroirs de nature différente selon l'état : le tiroir
    // "liste" (TrackSheetContent, Idle/Loading/Error, peek height fixe et petit) et le tiroir de
    // détail (ThreeStopPlanificationDetail, Loaded, nettement plus haut), sans jamais démonter
    // l'un pour l'autre au même endroit : ce sont deux branches if/else distinctes de ce composable,
    // donc deux instances de HikeMapView différentes, chacune avec son propre mécanisme de
    // correction de fit (pendingHeightCorrection). Tant que sheetTopPx reste un seul remember
    // partagé entre les deux, la valeur mesurée par l'ancien tiroir survit à la bascule : au premier
    // fit du nouveau tiroir, visibleMapHeightPx n'est pas la sentinelle Int.MAX_VALUE : c'est une
    // valeur obsolète, plus grande que la vraie hauteur visible une fois le tiroir de détail
    // effectivement mesuré. Le mécanisme correctif de HikeMapView ne s'arme donc jamais, et le fit
    // initial cadre la trace en sous-estimant l'occultation réelle du tiroir. Clé de remember sur
    // "un tiroir Loaded est-il affiché" : elle change exactement quand l'affichage bascule de
    // branche, ce qui réinitialise sheetTopPx à la sentinelle au bon moment et laisse
    // pendingHeightCorrection s'armer normalement pour le fit correctif une fois la vraie hauteur
    // connue.
    var sheetTopPx by remember(uiState is GpxImportUiState.Loaded) { mutableFloatStateOf(Float.MAX_VALUE) }
    val visibleMapHeightPx = (sheetTopPx - mapBoxTopPx).let { if (it.isFinite() && it > 0) it.toInt() else Int.MAX_VALUE }

    // RIC-40 : se déclenche une fois par demande entrante (identité de la requête en clé, remise à
    // null par l'appelant juste après) : c'est openDuplicateFromLoggedTrack qui décide s'il peut
    // charger tout de suite ou s'il doit d'abord passer par la confirmation de fermeture.
    //
    // RIC-131 : la requête arrive dans le même geste que la navigation NavHost qui affiche cet
    // écran (voir MainActivity.onDuplicateToPlanification) : poser le dialogue de nom pendant que
    // la transition de destination est encore en cours lui fait recevoir un onDismissRequest
    // spontané, il disparaît sans que l'utilisateur ait cliqué. Attendre RESUMED avant d'appeler
    // openDuplicateFromLoggedTrack (qui pose ce dialogue) évite la course.
    LaunchedEffect(pendingDuplicate) {
        val request = pendingDuplicate ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        viewModel.openDuplicateFromLoggedTrack(request.track, request.bivouacPoints, request.suggestedName)
        onPendingDuplicateConsumed()
    }

    // A rotation destroys and recreates the Activity, so onCreate re-evaluates the incoming
    // intent's URI and this effect fires again with the same (non-null) value: only guarding on
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
    val onOpenClick = { pickGpxLauncher.launch(arrayOf("*/*")) }

    // Cap the peek height to a share of the available screen height so the sheet can't swallow
    // the map in landscape, where total height is much smaller than the measured content needs.
    val maxPeekHeight = LocalConfiguration.current.screenHeightDp.dp * 0.5f

    // Hissés ici (et non plus créés dans TrackSheetContent) : le FAB flottant qui recouvre
    // maintenant le tiroir a besoin de lire ces deux états pour savoir s'il doit être étendu ou
    // replié. Les deux sont nécessaires : sheetScrollState seul ne suffit pas, parce que
    // BottomSheetScaffold consomme d'abord tout le geste de défilement pour tirer le tiroir de
    // son repli vers son plein déploiement (nested scroll) : tant que le tiroir n'a pas fini de
    // se déployer, le Column interne reste à scrollState.value == 0, quel que soit l'ampleur du
    // geste. C'est ce qui donnait l'impression que le FAB ne se repliait qu'« au bout du tiroir » :
    // en pratique il attendait que le tiroir ait fini de se tirer avant même de commencer à
    // recevoir le défilement. bottomSheetState.targetValue capte ce premier temps, réactif dès le
    // début du geste de tirage et pas seulement une fois le tiroir au repos.
    val sheetScrollState = rememberScrollState()
    val bottomSheetScaffoldState = rememberBottomSheetScaffoldState()

    val loaded = uiState as? GpxImportUiState.Loaded
    // RIC-105 (revu) : la banque vide n'a plus de carte du tout, plein écran dédié : même
    // traitement que le tout premier lancement du Journal, confirmé en revue. La carte ne
    // redevient pertinente qu'à partir du moment où il y a quelque chose à y montrer ou à y
    // préparer.
    //
    // bankedTracesLoaded : sans lui, ce plein écran flashait au tout premier lancement : le temps
    // que la lecture Room de la banque ET restoreLastTrack aboutissent, uiState valait encore Idle
    // et bankedTraces encore emptyList() par construction, alors qu'une session précédente était
    // bel et bien sur le point d'être restaurée (voir GpxImportViewModel.bankedTracesLoaded).
    if (uiState is GpxImportUiState.Idle && bankedTraces.isEmpty() && bankedTracesLoaded) {
        Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            FullScreenEmptyState(
                icon = Icons.Default.Route,
                title = "Aucune trace en préparation",
                subtitle = "Ouvre une trace pour commencer à placer tes points de bivouac.",
                buttonText = "Ouvrir une trace",
                onButtonClick = onOpenClick,
                modifier = Modifier.fillMaxSize(),
            )
            // La carte est le seul endroit où ce bouton flottait jusqu'ici (voir les deux autres
            // branches ci-dessous), sans elle, il lui fallait un nouveau point d'ancrage. Reste
            // en haut à droite, comme partout ailleurs dans Planification : pas de barre de titre
            // introduite pour ce seul état, ça n'aurait fait diverger que lui du reste de l'écran.
            SectionMenuButton(
                current = currentSection,
                onSelect = onSectionSelected,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
            )
        }
    } else if (loaded == null) {
        Box(modifier = modifier.fillMaxSize()) {
            BottomSheetScaffold(
                modifier = Modifier.fillMaxSize(),
                scaffoldState = bottomSheetScaffoldState,
                sheetPeekHeight = PEEK_HEIGHT_EMPTY.coerceAtMost(maxPeekHeight),
                sheetContent = {
                    TrackSheetContent(
                        uiState = uiState,
                        bankedTraces = bankedTraces,
                        activeCalibration = activeCalibration,
                        scrollState = sheetScrollState,
                        onOpenClick = onOpenClick,
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
            // Ancre fixe, identique à celle du Journal : posé sur le tiroir plutôt que suspendu
            // au-dessus (composé après le BottomSheetScaffold, donc dessiné par-dessus lui, y
            // compris par-dessus son contenu). Une seule trace bankée : le padding bas du tiroir
            // absorbe le FAB sans toucher la ligne. À partir de deux, le FAB en recouvre
            // naturellement le haut, jusqu'au premier défilement, même compromis que le Journal
            // fait déjà avec sa propre liste, pas un cas particulier à coder ici.
            if (uiState is GpxImportUiState.Idle && bankedTraces.isNotEmpty()) {
                val expanded by remember {
                    derivedStateOf {
                        sheetScrollState.value == 0 &&
                            bottomSheetScaffoldState.bottomSheetState.targetValue == SheetValue.PartiallyExpanded
                    }
                }
                ExtendedFloatingActionButton(
                    onClick = onOpenClick,
                    expanded = expanded,
                    icon = {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = if (expanded) null else "Ouvrir une trace",
                        )
                    },
                    text = { Text("Ouvrir une trace") },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(16.dp),
                )
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
                // RIC-126 : une trace multi-jours dupliquée depuis le Journal (RIC-40) place un
                // bivouac à chaque jonction de jour d'origine : sans ça, un jour dont
                // l'enregistrement s'est arrêté loin du camp fait mentir le tracé (trait continu
                // plutôt que pointillé) comme RIC-120 l'a déjà corrigé côté Journal. Le seuil de
                // 50 m dans DayJunctions.recordingGaps filtre naturellement les bivouacs posés à la
                // main au milieu d'un tracé continu.
                dayBoundaryIndices = bivouacPoints.map { it.trackPointIndex },
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
                // loaded.stats is a snapshot from whenever the trace was opened/imported: distance
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
                onExportTrack = {
                    context.startActivity(GpxExporter.openIntent(context, loaded.track.points, loaded.track.name ?: "Trace"))
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

    // RIC-127 (suite) : popup plutôt qu'écran plein : voir la kdoc de bankOpenError.
    bankOpenError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissBankOpenError,
            title = { Text("Ouverture impossible") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissBankOpenError) { Text("OK") } },
        )
    }
}

// Only Idle/Loading/Error: the Loaded state has its own three-stop drawer (BIV-57), see
// ThreeStopPlanificationDetail: GpxImportScreen switches away from this BottomSheetScaffold
// entirely once a track is loaded, so this sheet never needs to represent that state.
@Composable
private fun TrackSheetContent(
    uiState: GpxImportUiState,
    bankedTraces: List<BankedTrackEntity>,
    activeCalibration: SpeedCalibration,
    scrollState: ScrollState,
    onOpenClick: () -> Unit,
    onOpenBankedClick: (String) -> Unit,
    onRenameBankedClick: (id: String, name: String) -> Unit,
    onDeleteBankedClick: (id: String, name: String) -> Unit,
    onSheetTopMeasured: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 40.dp)
            .onGloballyPositioned { onSheetTopMeasured(it.positionInRoot().y) },
    ) {
        when (uiState) {
            // La banque vide est traitée en amont (GpxImportScreen), plein écran sans carte : ce
            // tiroir n'est jamais composé dans ce cas, bankedTraces est donc garanti non vide
            // ici. Le bouton d'ouverture, lui, a quitté ce flux : il flotte maintenant par-dessus
            // le tiroir (voir GpxImportScreen), ancré au même endroit que celui du Journal.
            is GpxImportUiState.Idle -> {
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
                // Même libellé que la FAB "Ouvrir une trace" de l'état Idle (au-dessus de ce
                // tiroir côté GpxImportScreen) : un Button nu, sans icône, tranchait avec elle.
                Button(onClick = onOpenClick, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
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

// "aujourd'hui à 14:32" when saved today (the realistic case for several saves the same day:
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

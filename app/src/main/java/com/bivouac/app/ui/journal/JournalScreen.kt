package com.bivouac.app.ui.journal

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bivouac.app.R
import com.bivouac.app.bilan.JournalOpenRequest
import com.bivouac.app.data.db.DuplicateMatch
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.db.SystemTag
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.SpeedCalibrationCalculator
import com.bivouac.app.data.gpx.TrackStats
import com.bivouac.app.data.gpx.TrackStatsCalculator
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.DayJunctions
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.Segment
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.model.TrekDatesFormatter
import com.bivouac.app.journal.DuplicatePlanRequest
import com.bivouac.app.journal.ImportProgress
import com.bivouac.app.journal.JournalDayInfo
import com.bivouac.app.journal.JournalUiState
import com.bivouac.app.journal.JournalViewModel
import com.bivouac.app.journal.SeparateImportReport
import com.bivouac.app.ui.components.ChoiceOptionCard
import com.bivouac.app.ui.components.DrawerStop
import com.bivouac.app.ui.components.FullScreenEmptyState
import com.bivouac.app.ui.components.ElevationProfile
import com.bivouac.app.ui.components.GainIconColor
import com.bivouac.app.ui.components.InfoText
import com.bivouac.app.ui.components.StatsRows
import com.bivouac.app.ui.components.TotalsCapsule
import com.bivouac.app.ui.components.ThreeStopDrawerHandle
import com.bivouac.app.ui.components.ThreeStopDrawerStopRow
import com.bivouac.app.ui.components.rememberThreeStopDrawerState
import com.bivouac.app.ui.map.ColoredTrack
import com.bivouac.app.ui.map.HikeMapView
import com.bivouac.app.ui.map.MapControls
import com.bivouac.app.ui.nav.AppScreenHeader
import com.bivouac.app.ui.nav.AppSection
import com.bivouac.app.ui.nav.SectionMenuButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val PEEK_HEIGHT_EMPTY = 150.dp

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
    // RIC-40 : reçoit une demande prête à charger, construite depuis la trace ouverte en vue
    // détail — c'est l'appelant (MainActivity) qui prend en charge le passage vers la
    // Planification, seul endroit où les ViewModels des deux écrans sont atteignables ensemble.
    onDuplicateToPlanification: (DuplicatePlanRequest) -> Unit = {},
    // RIC-104 : même boîte aux lettres que onDuplicateToPlanification ci-dessus, pour les fichiers
    // reçus de l'extérieur une fois « Journal » choisi dans le dialogue d'univers — MainActivity
    // ne peut pas appeler ce ViewModel directement, lui non plus.
    pendingImportUris: List<Uri>? = null,
    onPendingImportUrisConsumed: () -> Unit = {},
    // RIC-19 : même boîte aux lettres que pendingImportUris ci-dessus, pour un clic sur un record
    // du Bilan (autre écran, autre ViewModel) — voir JournalOpenRequest et MainActivity.
    pendingOpenRequest: JournalOpenRequest? = null,
    onPendingOpenRequestConsumed: () -> Unit = {},
    viewModel: JournalViewModel = viewModel(),
) {
    val calibrationSelectionActive by viewModel.calibrationSelectionActive.collectAsStateWithLifecycle()

    LaunchedEffect(calibrationSelectionMode) {
        if (calibrationSelectionMode) viewModel.enterCalibrationSelectionMode()
    }

    LaunchedEffect(pendingImportUris) {
        val uris = pendingImportUris ?: return@LaunchedEffect
        viewModel.importTracks(uris)
        onPendingImportUrisConsumed()
    }

    LaunchedEffect(pendingOpenRequest) {
        val request = pendingOpenRequest ?: return@LaunchedEffect
        viewModel.openTrackById(request.trackId, request.dayIndex)
        onPendingOpenRequestConsumed()
    }
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val tracksLoaded by viewModel.tracksLoaded.collectAsStateWithLifecycle()
    val filteredTracks by viewModel.filteredTracks.collectAsStateWithLifecycle()
    val tagsByTrackId by viewModel.tagsByTrackId.collectAsStateWithLifecycle()
    val dayInfoByTrackId by viewModel.dayInfoByTrackId.collectAsStateWithLifecycle()
    val selectedFilterTags by viewModel.selectedFilterTags.collectAsStateWithLifecycle()
    val currentTags by viewModel.currentTags.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedLayer by viewModel.selectedLayer.collectAsStateWithLifecycle()
    val importError by viewModel.importError.collectAsStateWithLifecycle()
    val nonFreeFeaturesDisabled by viewModel.nonFreeFeaturesDisabled.collectAsStateWithLifecycle()
    val activeCalibration by viewModel.activeCalibration.collectAsStateWithLifecycle()
    val duplicateWarning by viewModel.duplicateWarning.collectAsStateWithLifecycle()
    val multiFileImportChoice by viewModel.multiFileImportChoice.collectAsStateWithLifecycle()
    val separateImportReport by viewModel.separateImportReport.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val deleteTarget by viewModel.deleteTarget.collectAsStateWithLifecycle()
    val selectionModeActive by viewModel.selectionModeActive.collectAsStateWithLifecycle()
    val selectedTrackIds by viewModel.selectedTrackIds.collectAsStateWithLifecycle()

    var recenterSignal by remember { mutableIntStateOf(0) }
    var mapBoxTopPx by remember { mutableIntStateOf(0) }
    // RIC-102 : hissés ici plutôt que dans JournalPopulatedList, qui se démonte et se remonte à
    // chaque ouverture/fermeture d'une rando (branche « detail != null » ci-dessous) — cette
    // racine, elle, reste composée tant qu'on ne quitte pas l'onglet Journal, donc ces deux états
    // survivent à l'aller-retour vers une trace que RIC-102 rapportait perdu.
    var journalListExpandedYears by remember { mutableStateOf<Set<Int>?>(null) }
    val journalListScrollState = rememberScrollState()
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
    // RIC-41 : sélection multiple autorisée. Ce qu'un lot de plusieurs fichiers signifie (un trek
    // en plusieurs jours ou plusieurs sorties) n'est pas deviné ici, c'est le dialogue de choix
    // plus bas qui tranche — voir JournalViewModel.importTracks.
    val pickGpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        viewModel.importTracks(uris)
    }

    val coloredTracks = remember(multiTrack) { multiTrack?.entries?.let { assignTrackColors(it) }.orEmpty() }
    var highlightedTrackId by remember(multiTrack) { mutableStateOf<String?>(null) }
    val onToggleHighlight = { id: String -> highlightedTrackId = if (highlightedTrackId == id) null else id }
    var renameDialogVisible by remember { mutableStateOf(false) }
    // RIC-19 : seedé depuis detail.initialCursorIndex plutôt que toujours null — une ouverture
    // venue d'un record du Bilan sur un jour précis arrive avec son curseur déjà positionné.
    var cursorIndex by remember(detail?.entry?.id) { mutableStateOf(detail?.initialCursorIndex) }
    when {
        detail != null -> {
            // Constat E : sur une sortie de plusieurs jours, chaque coupure entre deux fichiers est
            // une nuit passée dehors. La donnée existait déjà, elle ne servait qu'à la duplication
            // vers la Planification (RIC-40) — la carte et le profil recevaient une liste vide.
            //
            // Identifiants dérivés du rang et non tirés au hasard : rien ici n'est enregistré, et un
            // identifiant stable évite de recréer les marqueurs à chaque recomposition.
            val journalBivouacs = remember(detail.daySegments) {
                DayJunctions.bivouacTrackPointIndices(detail.daySegments.map { it.points.size })
                    .mapIndexed { index, pointIndex ->
                        BivouacPoint(id = "jonction-$index", trackPointIndex = pointIndex)
                    }
            }
            Box(modifier = modifier.fillMaxSize()) {
                JournalMap(
                    track = detail.track,
                    bivouacPoints = journalBivouacs,
                    dayBoundaryIndices = journalBivouacs.map { it.trackPointIndex },
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
                    daySegments = detail.daySegments,
                    bivouacPoints = journalBivouacs,
                    activeCalibration = activeCalibration,
                    onCloseClick = viewModel::closeTrack,
                    onDeleteClick = viewModel::requestDelete,
                    onDuplicateClick = { viewModel.buildDuplicateForPlanification()?.let(onDuplicateToPlanification) },
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
        // RIC-65 : la vue « plusieurs traces sur la carte » (BIV-48) et le chargement qui y mène ou
        // qui mène au détail restent sur le patron carte + tiroir — seul l'accueil liste (branche
        // else ci-dessous) quitte ce patron, la carte n'a jamais quitté son rôle ici.
        multiTrack != null || uiState is JournalUiState.Loading -> {
            BottomSheetScaffold(
                modifier = modifier,
                sheetPeekHeight = PEEK_HEIGHT_EMPTY.coerceAtMost(LocalConfiguration.current.screenHeightDp.dp * 0.5f),
                sheetContent = {
                    // Loading est émis par openTrack() comme par showOnMap() : sans ce rendu, taper
                    // une trace ne donnait aucun retour visuel le temps du parsing (RIC-95) — et une
                    // trace illisible semblait juste ne rien faire (l'état Error est rendu en dialogue
                    // plus bas, la liste restant visible derrière).
                    if (uiState is JournalUiState.Loading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (multiTrack != null) {
                        JournalMultiTrackContent(
                            entries = multiTrack.entries,
                            coloredTracks = coloredTracks,
                            highlightedTrackId = highlightedTrackId,
                            onHighlightToggle = onToggleHighlight,
                            onCloseClick = viewModel::closeMultiTrack,
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
        }
        else -> {
            JournalHomeScreen(
                modifier = modifier,
                tracks = tracks,
                tracksLoaded = tracksLoaded,
                filteredTracks = filteredTracks,
                activeCalibration = activeCalibration,
                tagsByTrackId = tagsByTrackId,
                dayInfoByTrackId = dayInfoByTrackId,
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
                currentSection = currentSection,
                onSectionSelected = onSectionSelected,
                expandedYears = journalListExpandedYears,
                onExpandedYearsChanged = { journalListExpandedYears = it },
                listScrollState = journalListScrollState,
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

    // Échec d'ouverture d'une trace déjà dans le journal (fichier stocké illisible, trace
    // introuvable...) : fermer le dialogue ramène à la liste, déjà affichée derrière.
    (uiState as? JournalUiState.Error)?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::closeTrack,
            title = { Text("Ouverture impossible") },
            text = { Text(error.message) },
            confirmButton = {
                TextButton(onClick = viewModel::closeTrack) { Text("OK") }
            },
        )
    }

    multiFileImportChoice?.let { choice ->
        MultiFileImportChoiceDialog(
            fileCount = choice.fileCount,
            onMultiDay = viewModel::chooseMultiDayImport,
            onSeparate = viewModel::chooseSeparateImports,
            onCancel = viewModel::cancelMultiFileImport,
        )
    }

    importProgress?.let { progress -> ImportProgressDialog(progress) }

    separateImportReport?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSeparateImportReport,
            title = { Text("Import terminé") },
            text = { Text(formatSeparateImportReport(report)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSeparateImportReport) { Text("OK") }
            },
        )
    }

    duplicateWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicateWarning,
            title = {
                Text(
                    when (warning) {
                        is DuplicateMatch.SharedDay -> "Journée déjà présente"
                        else -> "Trace peut-être déjà présente"
                    },
                )
            },
            text = { Text(formatDuplicateWarning(warning)) },
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

/**
 * RIC-65 écran 4 : dès que le sélecteur renvoie plus d'un fichier. Les deux interprétations
 * possibles sont proposées telles quelles, sans détection automatique — « Un seul trek » est mis
 * en avant comme cas jugé le plus fréquent, « Abandonner » reste une porte de sortie explicite
 * quand le lot est un mélange des deux.
 *
 * Même habillage que le choix d'univers de RIC-104, via [ChoiceOptionCard] : les deux dialogues
 * posent la même sorte de question et se suivent parfois dans la même seconde, quand un lot arrive
 * de l'extérieur.
 */
@Composable
private fun MultiFileImportChoiceDialog(
    fileCount: Int,
    onMultiDay: () -> Unit,
    onSeparate: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Comment ajouter ces $fileCount traces ?") },
        // Les deux choix vivent dans le corps du dialogue plutôt que dans ses slots d'action :
        // c'est ce qui permet de les empiler pleine largeur et de hiérarchiser visuellement le
        // trek multi-jours. Ne reste dans les actions que la porte de sortie.
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Conservé de RIC-41 : importer un lot a l'air irréversible, et savoir que rien
                // n'est encore écrit est ce qui permet d'ouvrir ce dialogue sans crainte.
                Text("Rien n'est importé tant que tu n'as pas choisi.")
                ChoiceOptionCard(
                    icon = Icons.Default.ContentCopy,
                    title = "Sorties séparées",
                    subtitle = "$fileCount randos indépendantes, une entrée chacune dans le Journal",
                    onClick = onSeparate,
                )
                ChoiceOptionCard(
                    icon = Icons.Default.Terrain,
                    title = "Un seul trek en plusieurs jours",
                    subtitle = "Un fichier = un jour, réunis en une seule sortie multi-jours",
                    onClick = onMultiDay,
                    recommended = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("Abandonner l'ajout") }
        },
    )
}

/**
 * Attente d'import : volontairement sans aucune porte de sortie, ni bouton, ni retour arrière, ni
 * clic à côté. Un import écrit en base fichier par fichier ; laisser l'écran manipulable pendant
 * ce temps, c'est laisser ouvrir une trace dont l'import n'est pas fini, ou en relancer un second
 * par-dessus le premier. L'annuler proprement supposerait de savoir défaire un lot à moitié
 * écrit, ce qui n'existe pas ici.
 */
@Composable
private fun ImportProgressDialog(progress: ImportProgress) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Import en cours") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(
                    when (progress) {
                        // La calibration Auto reparse tout le journal : sur une banque fournie
                        // c'est l'étape la plus longue, et la nommer évite de croire à un blocage.
                        ImportProgress.Calibrating -> "Mise à jour de la vitesse personnalisée…"
                        is ImportProgress.Reading -> when {
                            progress.total == 1 -> "Lecture de la trace…"
                            progress.done == 0 && progress.total > 1 -> "Lecture de ${progress.total} fichiers…"
                            else -> "Fichier ${progress.done + 1} sur ${progress.total}…"
                        }
                    },
                )
            }
        },
        confirmButton = {},
    )
}

/**
 * Deux ressemblances de nature différente, donc deux formulations. Le doublon probable relève de
 * l'indice (même date, distance voisine), le jour partagé relève du fait établi : le fichier est
 * identique à l'octet près. La question posée reste la même dans les deux cas, parce que
 * l'intention, elle, n'est certaine ni dans un cas ni dans l'autre.
 */
private fun formatDuplicateWarning(warning: DuplicateMatch): String {
    val existingName = warning.existing.name
    val existingDate = formatStartedAtWithTime(warning.existing.startedAt)
    return when (warning) {
        is DuplicateMatch.SharedDay -> {
            val subject = when {
                warning.incomingDays == 1 -> "Cette journée est déjà"
                warning.sharedDays == 1 -> "Une des journées de ce trek est déjà"
                else -> "${warning.sharedDays} des journées de ce trek sont déjà"
            }
            "$subject dans « $existingName » ($existingDate). Importer quand même ?"
        }
        else -> "« $existingName » ($existingDate) a une date et une distance très proches. " +
            "Importer quand même ?"
    }
}

private const val MaxReportedProbableDuplicates = 5

private fun formatSeparateImportReport(report: SeparateImportReport): String {
    val lines = mutableListOf<String>()
    lines += when (report.imported) {
        0 -> "Aucune trace importée."
        1 -> "1 trace importée."
        else -> "${report.imported} traces importées."
    }
    if (report.duplicatesSkipped > 0) {
        lines += if (report.duplicatesSkipped == 1) {
            "1 fichier écarté (déjà dans le journal)."
        } else {
            "${report.duplicatesSkipped} fichiers écartés (déjà dans le journal)."
        }
    }
    if (report.failed > 0) {
        lines += if (report.failed == 1) "1 fichier illisible." else "${report.failed} fichiers illisibles."
    }
    // Le signalement des doublons probables tient dans ce bilan plutôt que dans un popup par
    // fichier : le bilan est déjà l'écran à simple acquittement de fin de lot, et multiplier les
    // popups sur un import de masse reviendrait à réintroduire l'interruption qu'on vient
    // justement de supprimer. En dernier, c'est la seule ligne qui appelle une vérification.
    if (report.probableDuplicateNames.isNotEmpty()) {
        // Plafonné : le corps d'un AlertDialog ne défile pas, une liste de 20 noms déborderait de
        // l'écran. Le reste est compté, jamais escamoté en silence.
        val shown = report.probableDuplicateNames.take(MaxReportedProbableDuplicates)
        val hidden = report.probableDuplicateNames.size - shown.size
        val names = shown.joinToString("\n") { "• $it" } +
            if (hidden > 0) "\n• et $hidden autre${if (hidden > 1) "s" else ""}" else ""
        lines += if (report.probableDuplicateNames.size == 1) {
            "\nÀ vérifier : cette trace ressemble à une sortie déjà présente (même date, distance " +
                "très proche) et a quand même été importée.\n$names"
        } else {
            "\nÀ vérifier : ces traces ressemblent à des sorties déjà présentes (même date, " +
                "distance très proche) et ont quand même été importées.\n$names"
        }
    }
    return lines.joinToString("\n")
}

@Composable
private fun JournalMap(
    track: HikeTrack?,
    bivouacPoints: List<BivouacPoint> = emptyList(),
    dayBoundaryIndices: List<Int> = emptyList(),
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
            bivouacPoints = bivouacPoints,
            selectedLayer = selectedLayer,
            recenterSignal = recenterSignal,
            visibleHeightPx = visibleMapHeightPx,
            onTrackTapped = onCursorChanged,
            onBivouacMoved = { _, _ -> },
            onBivouacDragPreview = { _, _ -> },
            bivouacsReadOnly = true,
            dayBoundaryIndices = dayBoundaryIndices,
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

/**
 * RIC-65 : accueil du Journal, écran plein natif (Scaffold + FAB) — plus de tiroir ni de carte
 * ici, la carte reste réservée à la vue trois crans d'une rando ouverte. Deux contenus possibles
 * selon qu'une trace a *déjà* été importée un jour ou non : [neverImported] est le seul cas qui
 * garde un CTA plein écran, un filtre à zéro résultat retombe sur le FAB comme l'état peuplé.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalHomeScreen(
    modifier: Modifier = Modifier,
    tracks: List<LoggedTrackEntity>,
    // Distinct de tracks.isEmpty() : tant que la toute première lecture de la base n'a pas abouti,
    // tracks vaut emptyList() par construction — sans ce drapeau, un démarrage à froid sur le
    // Journal avec une base bien remplie affichait une bouffée de l'écran vide avant la liste
    // réelle. Voir JournalViewModel.tracksLoaded.
    tracksLoaded: Boolean,
    filteredTracks: List<LoggedTrackEntity>,
    activeCalibration: SpeedCalibration,
    tagsByTrackId: Map<String, List<String>>,
    dayInfoByTrackId: Map<String, JournalDayInfo>,
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
    calibrationSelectionActive: Boolean,
    onConfirmCalibrationSelection: () -> Unit,
    currentSection: AppSection,
    onSectionSelected: (AppSection) -> Unit,
    // RIC-102 : hissés par l'appelant (JournalScreen), qui seul reste composé pendant l'aller-retour
    // vers une rando ouverte — voir le commentaire là-bas.
    expandedYears: Set<Int>?,
    onExpandedYearsChanged: (Set<Int>?) -> Unit,
    listScrollState: ScrollState,
) {
    val neverImported = tracks.isEmpty()
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppScreenHeader(title = "Journal", currentSection = currentSection, onSectionSelected = onSectionSelected) },
        floatingActionButton = {
            // Le CTA plein écran de l'état vraiment vide (ci-dessous) est la seule action possible
            // de cet écran : un FAB par-dessus ferait doublon. Masqué aussi tant que le chargement
            // n'a pas abouti — pas d'action tant qu'on ne sait pas encore laquelle est de mise.
            if (tracksLoaded && !neverImported) {
                // Patron Material : le FAB étendu se replie en carré arrondi quand le contenu
                // prend le dessus. Déclenché par la position de défilement et non par la longueur
                // de la liste — c'est ce que documente M3, et ça garde le libellé tant qu'il y a
                // la place de le lire. derivedStateOf pour ne recomposer qu'au basculement, pas à
                // chaque pixel défilé.
                val expanded by remember(listScrollState) {
                    derivedStateOf { listScrollState.value == 0 }
                }
                ExtendedFloatingActionButton(
                    onClick = onImportClick,
                    expanded = expanded,
                    // Une fois replié, l'icône est tout ce qui reste à annoncer ; étendu, le
                    // libellé s'en charge déjà et le répéter ferait doublon à la lecture d'écran.
                    icon = {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = if (expanded) null else "Ajouter une trace",
                        )
                    },
                    text = { Text("Ajouter une trace") },
                )
            }
        },
    ) { paddingValues ->
        if (!tracksLoaded) {
            // Fenêtre habituellement très courte (lecture Room locale) mais bien réelle sur une
            // base conséquente ou un appareil lent — un indicateur plutôt qu'un écran blanc, sans
            // se prononcer sur vide ou peuplé avant de le savoir vraiment.
            Box(
                modifier = Modifier.padding(paddingValues).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (neverImported) {
            FullScreenEmptyState(
                icon = Icons.Default.Terrain,
                title = "Aucune rando pour l'instant",
                subtitle = "Ajoute une trace pour commencer ton carnet : tes randos réalisées vivront ici.",
                buttonText = "Ajouter une trace",
                onButtonClick = onImportClick,
                modifier = Modifier.padding(paddingValues).fillMaxSize(),
            )
        } else {
            JournalPopulatedList(
                tracks = tracks,
                filteredTracks = filteredTracks,
                activeCalibration = activeCalibration,
                tagsByTrackId = tagsByTrackId,
                dayInfoByTrackId = dayInfoByTrackId,
                selectedFilterTags = selectedFilterTags,
                onToggleFilterTag = onToggleFilterTag,
                onTrackClick = onTrackClick,
                selectionModeActive = selectionModeActive,
                selectedTrackIds = selectedTrackIds,
                onEnterSelectionMode = onEnterSelectionMode,
                onExitSelectionMode = onExitSelectionMode,
                onToggleSelection = onToggleSelection,
                onToggleYearSelection = onToggleYearSelection,
                onShowOnMap = onShowOnMap,
                calibrationSelectionActive = calibrationSelectionActive,
                onConfirmCalibrationSelection = onConfirmCalibrationSelection,
                expandedYears = expandedYears,
                onExpandedYearsChanged = onExpandedYearsChanged,
                scrollState = listScrollState,
                modifier = Modifier.padding(paddingValues).fillMaxSize(),
            )
        }
    }
}

/**
 * RIC-65 écran 2 : résumé global, non couplé aux filtres/chips actifs — les croisements par
 * filtre relèvent de l'écran Bilan lui-même (RIC-19), pas de cette carte.
 *
 * RIC-19 : fine enveloppe autour de [TotalsCapsule], désormais partagé avec l'écran Bilan — voir sa
 * kdoc pour le design (fond neutre, grille d'icônes colorées) qui remplace l'ancien fond
 * `secondaryContainer` plein propre à cette carte.
 */
@Composable
private fun JournalBilanCard(total: Int, stats: TrackStats, bivouacCount: Int, modifier: Modifier = Modifier) {
    TotalsCapsule(
        totalLabel = "$total rando${if (total > 1) "s" else ""} au total",
        stats = stats,
        bivouacCount = bivouacCount,
        modifier = modifier,
    )
}

/**
 * RIC-65 écrans 2 et 3 : la liste peuplée, avec le Bilan et les chips toujours affichés — un
 * filtre à zéro résultat (écran 3) n'en masque que le contenu de la liste, pas ces deux-là.
 */
@Composable
private fun JournalPopulatedList(
    tracks: List<LoggedTrackEntity>,
    filteredTracks: List<LoggedTrackEntity>,
    activeCalibration: SpeedCalibration,
    tagsByTrackId: Map<String, List<String>>,
    dayInfoByTrackId: Map<String, JournalDayInfo>,
    selectedFilterTags: Set<String>,
    onToggleFilterTag: (String) -> Unit,
    onTrackClick: (LoggedTrackEntity) -> Unit,
    selectionModeActive: Boolean,
    selectedTrackIds: Set<String>,
    onEnterSelectionMode: (String) -> Unit,
    onExitSelectionMode: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onToggleYearSelection: (List<String>) -> Unit,
    onShowOnMap: () -> Unit,
    calibrationSelectionActive: Boolean,
    onConfirmCalibrationSelection: () -> Unit,
    // RIC-102 : hissés par l'appelant (JournalScreen) pour survivre à l'aller-retour vers une
    // rando ouverte, ce composable-ci se démontant et se remontant à chaque fois — voir le
    // commentaire sur JournalScreen. null (et non un set vide) veut dire « aucun choix manuel
    // encore » — c'est seulement dans ce cas que l'année la plus récente s'ouvre par défaut.
    expandedYears: Set<Int>?,
    onExpandedYearsChanged: (Set<Int>?) -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val groups = remember(filteredTracks, activeCalibration) { groupByYear(filteredTracks, activeCalibration) }
    val resolvedExpandedYears = expandedYears ?: setOfNotNull(groups.firstOrNull()?.year)
    // Sur `tracks` et non `filteredTracks` : le Bilan reste global, un filtre actif n'en retire
    // rien (RIC-65 écran 3).
    val bilanStats = remember(tracks, activeCalibration) { aggregateStats(tracks, activeCalibration) }
    // RIC-19 : bivouacCount découle de dayCount, toujours connu (contrairement aux dates, qui
    // dépendent d'un horodatage GPX exploitable) — voir JournalDayInfo.bivouacCount.
    val bilanBivouacCount = remember(tracks, dayInfoByTrackId) {
        tracks.sumOf { dayInfoByTrackId[it.id]?.bivouacCount ?: 0 }
    }

    // System tags always offered (even before ever used) so the filter is discoverable; free tags
    // only once at least one track actually has them.
    val allFilterTags = remember(tagsByTrackId) {
        (SystemTag.entries.map { it.value } + tagsByTrackId.values.flatten()).distinct()
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 96.dp),
    ) {
        JournalBilanCard(total = tracks.size, stats = bilanStats, bivouacCount = bilanBivouacCount)
        if (allFilterTags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
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
        if (filteredTracks.isEmpty()) {
            // RIC-65 écran 3 : les traces existent, seul le filtre actif n'en retient aucune —
            // registre visuel proche de l'écran 1 mais sans CTA, rien à importer ici.
            Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Terrain,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Aucune rando ne correspond", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Essaie un autre filtre.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }
        // RIC-113 : plus de "tout afficher" sans rien choisir — sur un grand Journal (des
        // dizaines de traces) ça revenait à reparser et superposer tout le Journal d'un coup,
        // lent et illisible. La ligne n'existe donc plus dans ce cas, pas juste désactivée
        // ("libérer l'espace"). Elle reste pour une sélection manuelle d'au moins 2 traces
        // (0 ou 1 se consulte déjà en tapant dessus, pas besoin d'un bouton dédié), et pour le cas
        // filtre-actif-sans-sélection : un tag filtré donne en pratique un petit sous-ensemble
        // (2 à 8 traces sur un vrai Journal de 103), le problème de départ ne s'y pose pas.
        val filterActive = selectedFilterTags.isNotEmpty()
        val showMapRow = calibrationSelectionActive ||
            selectedTrackIds.size >= 2 ||
            (selectedTrackIds.isEmpty() && filterActive)
        if (showMapRow) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = if (calibrationSelectionActive) onConfirmCalibrationSelection else onShowOnMap,
                    // A calibration fit needs at least 2 traces (see MIN_TRACKS_FOR_CALIBRATION) — the
                    // ordinary "show on map" action has no such floor, 0 or 1 is a perfectly normal
                    // selection there.
                    enabled = !calibrationSelectionActive ||
                        selectedTrackIds.size >= SpeedCalibrationCalculator.MIN_TRACKS_FOR_CALIBRATION,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when {
                            calibrationSelectionActive -> "Confirmer la sélection (${selectedTrackIds.size})"
                            selectedTrackIds.isEmpty() -> "Afficher les ${filteredTracks.size} résultats sur la carte"
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
        }
        if (calibrationSelectionActive && selectedTrackIds.size < SpeedCalibrationCalculator.MIN_TRACKS_FOR_CALIBRATION) {
            Text(
                "Choisis au moins 2 traces pour valider.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        groups.forEach { group ->
            HorizontalDivider()
            YearHeader(
                group = group,
                expanded = group.year in resolvedExpandedYears,
                selectionModeActive = selectionModeActive,
                selectionState = when {
                    group.entries.none { it.id in selectedTrackIds } -> ToggleableState.Off
                    group.entries.all { it.id in selectedTrackIds } -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                },
                onToggle = {
                    onExpandedYearsChanged(
                        if (group.year in resolvedExpandedYears) {
                            resolvedExpandedYears - group.year
                        } else {
                            resolvedExpandedYears + group.year
                        },
                    )
                },
                onToggleSelection = { onToggleYearSelection(group.entries.map { it.id }) },
            )
            if (group.year in resolvedExpandedYears) {
                group.entries.forEach { entry ->
                    HorizontalDivider()
                    JournalTrackRow(
                        entry = entry,
                        dayInfo = dayInfoByTrackId[entry.id],
                        activeCalibration = activeCalibration,
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

// Duration is recomputed from the aggregate distance/gain under the *current* calibration rather
// than summed from each entry's own stored estimate — those were frozen at whatever calibration
// was active when each hike was imported, so summing them would mix calibrations together instead
// of reflecting the one currently active (BIV-16 feedback: the Planification list had the same
// staleness, fixed the same way — see TrackStatsCalculator.recomputeDuration).
//
// Partagé entre les en-têtes d'année et la carte Bilan (RIC-65) : le total de l'écran et la somme
// de ses sections doivent tomber juste l'un par rapport à l'autre, ce que deux calculs séparés ne
// garantiraient plus dès qu'un seul des deux évoluerait.
private fun aggregateStats(tracks: List<LoggedTrackEntity>, activeCalibration: SpeedCalibration): TrackStats =
    TrackStatsCalculator.recomputeDuration(
        TrackStats(
            distanceMeters = tracks.sumOf { it.distanceMeters },
            elevationGainMeters = tracks.sumOf { it.elevationGainMeters },
            elevationLossMeters = tracks.sumOf { it.elevationLossMeters },
            estimatedDurationMinutes = 0,
        ),
        activeCalibration,
    )

private fun groupByYear(tracks: List<LoggedTrackEntity>, activeCalibration: SpeedCalibration): List<YearGroup> {
    val zone = ZoneId.systemDefault()
    return tracks
        .groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).year }
        .toSortedMap(compareByDescending { it })
        .map { (year, entries) ->
            YearGroup(
                year = year,
                entries = entries,
                totalStats = aggregateStats(entries, activeCalibration),
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
    dayInfo: JournalDayInfo?,
    activeCalibration: SpeedCalibration,
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
            // Une sortie de plusieurs jours affiche ses dates réelles plutôt que son seul jour de
            // départ, sans quoi rien ne la distingue d'une sortie d'un jour dans cette liste.
            // Retombe sur la date de départ seule quand les dates des jours sont inconnues : GPX
            // sans horodatage, ou trace pas encore rattrapée après la migration 8 vers 9.
            //
            // Le nombre de nuits et son badge reprennent trait pour trait la ligne de la liste de
            // Planification (BankedTrackRow), pour que la même information se lise pareil des deux
            // côtés — même si ici elle se déduit du nombre de jours.
            val bivouacCount = dayInfo?.bivouacCount ?: 0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = (TrekDatesFormatter.format(dayInfo?.dates.orEmpty()) ?: formatStartedAt(entry.startedAt)) +
                        if (bivouacCount > 0) " · $bivouacCount" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (bivouacCount > 0) {
                    Image(
                        painter = painterResource(R.drawable.ic_bivouac_badge),
                        contentDescription = "nuit${if (bivouacCount != 1) "s" else ""} de bivouac",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            StatsRows(TrackStatsCalculator.recomputeDuration(entry.toTrackStats(), activeCalibration))
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

/**
 * La nuit passée entre deux jours d'une sortie du Journal, dans la ventilation par jour. Reprend
 * la ligne de bivouac de la Planification, badge et altitude compris, moins tout ce qui agit :
 * ni suppression ni météo, la trace est immuable et la nuit a déjà eu lieu.
 *
 * [arrival] est le dernier point du jour qui s'achève, [departure] le premier du lendemain. Leurs
 * heures encadrent la nuit ; la flèche suffit à les qualifier, sans libellé. Les deux ou aucune :
 * une heure d'arrivée orpheline se lirait mal et n'apprendrait pas grand-chose.
 */
@Composable
private fun ReadOnlyBivouacRow(arrival: TrackPoint?, departure: TrackPoint?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_bivouac_badge),
            contentDescription = "Nuit de bivouac",
            modifier = Modifier.size(24.dp),
        )
        val elevation = arrival?.elevationMeters
        if (elevation != null) {
            InfoText(
                text = "${elevation.roundToInt()} m",
                icon = Icons.Default.Terrain,
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val arrivedAt = arrival?.time
        val leftAt = departure?.time
        if (arrivedAt != null && leftAt != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = "${formatTimeOfDay(arrivedAt)} → ${formatTimeOfDay(leftAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// internal rather than private: exercised directly by BivouacDatabaseMigrationTest's sibling,
// ThreeStopJournalDetailDirtyIndicatorTest (androidTest), to test the isDirty save-icon states
// without driving the whole Journal screen through a real ViewModel.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ThreeStopJournalDetail(
    entry: LoggedTrackEntity,
    track: HikeTrack,
    // RIC-41 : un élément par jour importé, dans l'ordre — la ventilation ne s'affiche qu'au-delà
    // d'un jour, même convention que les segments de Planification.
    daySegments: List<Segment> = emptyList(),
    // Constat E : un point par jonction entre deux jours, en lecture seule — le profil les trace
    // comme la Planification, mais rien ici ne se déplace ni ne se supprime.
    bivouacPoints: List<BivouacPoint> = emptyList(),
    activeCalibration: SpeedCalibration = SpeedCalibration.DEFAULT,
    onCloseClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onDuplicateClick: () -> Unit = {},
    onSheetTopMeasured: (Int) -> Unit,
    cursorIndex: Int?,
    onCursorDragged: (Int) -> Unit,
    currentTags: List<String>,
    tagsByTrackId: Map<String, List<String>>,
    onSaveDetails: (Set<String>, String) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val zone = remember { ZoneId.systemDefault() }
        // Date de début de chaque jour, tirée directement des points déjà chargés. Les jours sans
        // horodatage sont écartés plutôt qu'inventés, comme dans la liste.
        val dayStartDates = remember(daySegments, zone) {
            daySegments.mapNotNull { segment ->
                segment.points.firstOrNull()?.time?.atZone(zone)?.toLocalDate()
            }
        }
        var isEditing by remember(entry.id) { mutableStateOf(false) }
        var draftTags by remember(entry.id) { mutableStateOf(currentTags.toSet()) }
        // TextFieldValue et non String : le suivi du curseur pendant la frappe (RIC-100) a
        // besoin de sa position, que seule cette forme porte.
        var draftNote by remember(entry.id) { mutableStateOf(TextFieldValue(entry.note)) }
        // Déclaré ici et non dans la section notes : le focus du champ pilote aussi le repli de
        // l'en-tête et du profil (RIC-100, décision 3.2), qui se joue bien plus haut dans l'arbre.
        var noteFocused by remember(entry.id) { mutableStateOf(false) }
        var newTagText by remember(entry.id) { mutableStateOf("") }
        var pendingExit by remember(entry.id) { mutableStateOf<(() -> Unit)?>(null) }
        val knownFreeTags = remember(tagsByTrackId) {
            tagsByTrackId.values.flatten()
                .filterNot { tag -> SystemTag.entries.any { it.value == tag } }
                .distinct()
        }
        val isDirty = draftTags != currentTags.toSet() || draftNote.text != entry.note

        fun beginEditing() {
            draftTags = currentTags.toSet()
            // Curseur en fin de texte : reprendre une note, c'est presque toujours la compléter.
            draftNote = TextFieldValue(entry.note, TextRange(entry.note.length))
            // Le champ renaît sans focus ; l'état hissé repart de même, sans quoi un focus jamais
            // relâché à la sortie précédente replierait l'en-tête avant le premier tap.
            noteFocused = false
            isEditing = true
        }

        fun requestExit(exit: () -> Unit) {
            if (isEditing && isDirty) pendingExit = exit else {
                isEditing = false
                exit()
            }
        }

        fun saveAndStopEditing() {
            if (isDirty) onSaveDetails(draftTags, draftNote.text)
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
                DrawerStop.DETAIL to fullHeightPx - detailHeightPx,
                DrawerStop.PROFILE to fullHeightPx - profileHeightPx,
                DrawerStop.SUMMARY to fullHeightPx - summaryHeightPx,
            )
        }
        val drawer = rememberThreeStopDrawerState(anchors, entry.id)
        val statusBarHeightPx = WindowInsets.statusBars.getTop(density).toFloat()
        // RIC-100, décision 3.2 : clavier ouvert, la fenêtre de saisie des notes se réduit à
        // trois lignes environ. L'en-tête et le profil occupent le haut du tiroir sans rien
        // apporter à la frappe : ils se replient pendant qu'on tape une note, et rendent leur
        // hauteur à la saisie. Chaque terme borne le repli à ce seul moment :
        // - noteFocused et non isEditing : l'édition des tags garde tout son contexte, elle
        //   n'a pas ce problème de place ;
        // - clavier visible : le refermer au retour arrière laisse le focus au champ, et sans
        //   clavier le repli ne rend aucune place, il ne ferait que cacher l'en-tête sans
        //   porte de sortie évidente ;
        // - cran Détails, le seul où l'on tape : la poignée et la rangée de crans restent
        //   actives pendant la frappe, et un cran Profil doit montrer sa courbe, une
        //   Synthèse son titre ;
        // - isEditing : quitter l'édition défait toujours le repli, même si le champ
        //   disparaît sans avoir signalé la perte de son focus.
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val noteTakesAllSpace =
            isEditing && noteFocused && imeVisible && drawer.stop == DrawerStop.DETAIL

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { detailHeightPx.toDp() })
                .offset { IntOffset(0, drawer.offset.value.roundToInt()) }
                .onGloballyPositioned { onSheetTopMeasured(it.positionInRoot().y.toInt()) },
            shape = if (drawer.offset.value <= 1f) {
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
                        with(density) { drawer.statusBarOverlapPx(statusBarHeightPx).toDp() },
                    ),
                )
                Column(
                    modifier = drawer.dragModifier
                        .padding(
                            start = 20.dp,
                            end = 20.dp,
                            bottom = with(density) { navigationBarHeightPx.toDp() } + 8.dp,
                        )
                        .onGloballyPositioned { measuredSummaryHeightPx = it.size.height },
                ) {
                    ThreeStopDrawerHandle(drawer, Modifier.align(Alignment.CenterHorizontally))
                    // La rangée entière se replie, actions comprises : renommer, dupliquer ou
                    // fermer ne se font pas en pleine frappe, et le retour arrière couvre la
                    // sortie. Ne replier que les textes laisserait la hauteur des boutons.
                    AnimatedVisibility(visible = !noteTakesAllSpace) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(text = entry.name, style = MaterialTheme.typography.titleMedium)
                                // Même plage de dates que dans la liste : une sortie ouverte ne
                                // doit pas en dire moins qu'une sortie survolée. Les dates
                                // viennent ici des points déjà chargés, pas des colonnes
                                // dénormalisées, la trace étant de toute façon parsée pour être
                                // affichée.
                                Text(
                                    text = TrekDatesFormatter.format(dayStartDates) ?: formatStartedAt(entry.startedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            JournalDetailMenu(
                                onRenameClick = onRenameClick,
                                onDuplicateClick = onDuplicateClick,
                                onDeleteClick = onDeleteClick,
                            )
                            IconButton(onClick = { requestExit(onCloseClick) }) {
                                Icon(Icons.Default.Close, contentDescription = "Fermer")
                            }
                        }
                    }
                    // Sur une sortie de plusieurs jours, la ligne agrégée devient un « Total » en
                    // retrait : c'est la ventilation par jour, au cran Détails, qui porte
                    // l'information utile — même hiérarchie visuelle qu'en Planification.
                    if (daySegments.size > 1) {
                        Text(
                            text = "Total",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatsRows(
                        TrackStatsCalculator.recomputeDuration(entry.toTrackStats(), activeCalibration),
                        muted = daySegments.size > 1,
                    )
                    ThreeStopDrawerStopRow(drawer)
                }

                AnimatedVisibility(visible = !noteTakesAllSpace) {
                    ElevationProfile(
                        points = track.points,
                        bivouacPoints = bivouacPoints,
                        dayBoundaryIndices = bivouacPoints.map { it.trackPointIndex },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        cursorIndex = cursorIndex,
                        onCursorDragged = onCursorDragged,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .nestedScroll(drawer.nestedScrollConnection)
                        // Avant verticalScroll, et l'ordre est décisif : placé ici, l'inset rogne
                        // la zone de défilement elle-même, qui s'arrête au-dessus du clavier.
                        // Placé après, il s'appliquerait au contenu qui défile, la zone garderait
                        // toute la hauteur du tiroir, clavier compris, et Compose jugerait le
                        // curseur déjà visible sans jamais défiler.
                        //
                        // Le tiroir a une hauteur fixe et l'app est en edge-to-edge : la fenêtre
                        // ne se redimensionne pas à l'ouverture du clavier, personne d'autre ne
                        // fera ce travail.
                        //
                        // union et non deux paddings enchaînés : les insets se cumuleraient, alors
                        // que l'inset du clavier englobe déjà la barre de navigation.
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                        .verticalScroll(drawer.detailScrollState)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // RIC-41 : uniquement pour un import de plusieurs jours — sur un seul jour, la
                    // ligne « Total » ci-dessus dit déjà tout, une ventilation à une entrée ne
                    // serait que du bruit.
                    if (daySegments.size > 1) {
                        Text("Jours", style = MaterialTheme.typography.titleSmall)
                        Column {
                            daySegments.forEachIndexed { index, segment ->
                                HorizontalDivider()
                                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                                    // RIC-112 : jour de semaine + quantième réels plutôt qu'un ordinal,
                                    // tirés du même horodatage GPX que dayStartDates ci-dessus. Repli sur
                                    // "Jour N" si ce jour précis n'a pas d'horodatage exploitable — un
                                    // trou isolé ne doit pas casser la ventilation des autres jours.
                                    val dayLabel = segment.points.firstOrNull()?.time
                                        ?.let { formatDayLabel(it) }
                                        ?: "Jour ${index + 1}"
                                    Text(text = dayLabel, style = MaterialTheme.typography.labelLarge)
                                    StatsRows(TrackStatsCalculator.recomputeDuration(segment.stats, activeCalibration))
                                }
                                // La nuit s'intercale entre deux jours, exactement comme la
                                // Planification l'intercale entre deux segments : c'est la même
                                // lecture d'un même itinéraire, seulement figée. Sans action
                                // possible ici, ni suppression ni météo — la trace est immuable et
                                // la nuit a déjà eu lieu.
                                val bivouac = bivouacPoints.getOrNull(index)
                                if (bivouac != null) {
                                    HorizontalDivider()
                                    ReadOnlyBivouacRow(
                                        arrival = track.points.getOrNull(bivouac.trackPointIndex),
                                        departure = track.points.getOrNull(bivouac.trackPointIndex + 1),
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
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
                        // Ajouter un tag insère une ligne de chips juste au-dessus de cette
                        // ligne-ci, qui descend donc d'autant et finit sous le clavier. Le champ
                        // garde le focus et rien ne déclencherait de défilement : c'est la mise en
                        // page qui a bougé, pas le curseur. D'où ce rappel explicite à chaque
                        // changement du nombre de tags.
                        val tagFieldVisibility = remember { BringIntoViewRequester() }
                        var tagFieldFocused by remember { mutableStateOf(false) }
                        LaunchedEffect(draftTags.size, tagFieldFocused) {
                            if (tagFieldFocused) tagFieldVisibility.bringIntoView()
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .bringIntoViewRequester(tagFieldVisibility),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextField(
                                value = newTagText,
                                onValueChange = { newTagText = it },
                                placeholder = { Text("Ajouter un tag") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                modifier = Modifier
                                    .weight(1f)
                                    .onFocusChanged { tagFieldFocused = it.isFocused },
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
                        // RIC-100. La note n'a pas de plafond de hauteur, donc le champ peut
                        // dépasser la fenêtre de saisie ; le défilement que Compose déclenche de
                        // lui-même vise le champ entier, et faute de pouvoir le contenir il en
                        // aligne le haut : le bas déborde sous le clavier, curseur compris.
                        //
                        // On demande donc à voir le bas du champ, là où atterrit le curseur quand
                        // on complète une note, avec une marge pour que la ligne suivante respire.
                        // Le TextField de Material3 n'expose pas son TextLayoutResult, donc viser
                        // le rectangle exact du curseur supposerait de repasser par
                        // BasicTextField et de reconstruire tout le décor : hors de proportion
                        // tant que ce repli suffit. Conséquence assumée : une frappe insérée au
                        // milieu d'une note longue n'est pas suivie, seule la fin de texte l'est.
                        val noteVisibility = remember { BringIntoViewRequester() }
                        var noteHeightPx by remember(entry.id) { mutableIntStateOf(0) }
                        val cursorBandPx = with(density) { 56.dp.toPx() }
                        LaunchedEffect(draftNote, noteFocused, noteHeightPx) {
                            if (!noteFocused) return@LaunchedEffect
                            if (noteHeightPx == 0) return@LaunchedEffect
                            // trimEnd et non length : une note qui se termine par un retour à la
                            // ligne ou une espace laisse le curseur juste avant sa toute fin, et
                            // une comparaison stricte cesserait alors de suivre la frappe sans
                            // raison.
                            if (draftNote.selection.end < draftNote.text.trimEnd().length) return@LaunchedEffect
                            val bottom = noteHeightPx.toFloat()
                            noteVisibility.bringIntoView(
                                Rect(0f, (bottom - cursorBandPx).coerceAtLeast(0f), 1f, bottom),
                            )
                        }
                        TextField(
                            value = draftNote,
                            onValueChange = { draftNote = it },
                            visualTransformation = BulletVisualTransformation,
                            placeholder = { Text("Quelques mots sur cette rando…") },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            // Volontairement sans hauteur maximale : c'est un journal, la note
                            // doit se lire d'un bloc, en consultation comme en édition.
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp)
                                .bringIntoViewRequester(noteVisibility)
                                .onFocusChanged { noteFocused = it.isFocused }
                                .onGloballyPositioned { coordinates -> noteHeightPx = coordinates.size.height },
                        )
                    } else if (entry.note.isBlank()) {
                        NotebookEmptyHint()
                    } else {
                        Text(withBullets(entry.note), style = MaterialTheme.typography.bodyMedium)
                    }
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
private fun JournalDetailMenu(onRenameClick: () -> Unit, onDuplicateClick: () -> Unit, onDeleteClick: () -> Unit) {
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
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = { expanded = false; onDuplicateClick() },
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

// Heure locale d'un instant GPX, pour encadrer une nuit de bivouac.
private fun formatTimeOfDay(instant: Instant): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)
        .withZone(ZoneId.systemDefault())
        .format(instant)

// RIC-112 : jour de semaine + quantième d'un jour de trek, ex. "Lundi 12".
private fun formatDayLabel(instant: Instant): String =
    DateTimeFormatter.ofPattern("EEEE d", Locale.FRANCE)
        .withZone(ZoneId.systemDefault())
        .format(instant)
        .replaceFirstChar { it.titlecase(Locale.FRANCE) }

private fun formatStartedAt(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

private fun formatStartedAtWithTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH:mm", Locale.FRANCE)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

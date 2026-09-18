package com.bivouac.app.ui.gpximport

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import com.bivouac.app.gpximport.NameDialogPurpose
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
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.first

private val PEEK_HEIGHT_EMPTY = 150.dp

/**
 * RIC-164 : les quatre visages de l'écran Planification, dont deux seulement portent une carte.
 *
 * Le défaut, relevé par le test communautaire F-Droid : au tout premier lancement, cache vide,
 * l'écran affiché est « Aucune trace en préparation » (aucune carte visible), et pourtant une tuile
 * OpenTopoMap partait sur le réseau. Mécanisme exact : [GpxImportViewModel.bankedTracesLoaded] naît
 * à false, donc la toute première composition ne pouvait pas prendre la branche de l'état vide (qui
 * l'exige, pour ne pas flasher devant une session en cours de restauration) et tombait sur la
 * branche « banque non vide » : celle-ci compose un HikeMapView, qui instancie un MapView osmdroid
 * avec sa source de tuiles et son centre par défaut. Le MapView charge sa première tuile
 * immédiatement, avant même d'être dessiné, et la composition était remplacée quelques
 * millisecondes plus tard par l'état vide : un aller-retour réseau pour une carte que personne n'a
 * jamais vue.
 *
 * D'où [LOADING], qui n'existait pas : tant que la première lecture Room n'a pas répondu, l'écran
 * ne montre ni carte ni verdict sur la banque. Aucun HikeMapView n'est composé dans cet état, donc
 * aucun MapView n'est construit, donc aucune tuile n'est demandée. C'est bien la construction du
 * MapView qui déclenche le téléchargement, pas son affichage : la seule garantie possible est de ne
 * pas le créer, et c'est ce que ce mode assure.
 *
 * Fonction pure et testable plutôt qu'une cascade de `if` dans le composable : l'invariant à tenir
 * (« pas de carte avant qu'il y ait quelque chose à montrer ») se vérifie alors sans appareil.
 */
internal enum class PlanificationScreenMode {
    /** Première lecture de la banque en vol. Aucune carte. */
    LOADING,

    /** Banque vide et rien d'ouvert : plein écran d'accueil. Aucune carte. */
    EMPTY,

    /** Banque non vide, rien d'ouvert : carte de fond et tiroir listant les traces bankées. */
    BANK,

    /** Une trace ouverte : carte et tiroir de détail. */
    DETAIL,
}

internal fun planificationScreenMode(
    uiState: GpxImportUiState,
    bankedTracesEmpty: Boolean,
    bankedTracesLoaded: Boolean,
): PlanificationScreenMode = when {
    // Une trace ouverte l'emporte sur tout le reste : c'est déjà quelque chose à montrer, même si
    // la lecture de la banque n'a pas encore répondu (cas de la restauration de session).
    uiState is GpxImportUiState.Loaded -> PlanificationScreenMode.DETAIL
    !bankedTracesLoaded -> PlanificationScreenMode.LOADING
    uiState is GpxImportUiState.Idle && bankedTracesEmpty -> PlanificationScreenMode.EMPTY
    else -> PlanificationScreenMode.BANK
}

// RIC-193 : le dialogue de nom du Planning sert à la fois au premier nommage (FIRST_SAVE à
// l'import, SAVE_THEN_CLOSE à la fermeture) et à la duplication (DUPLICATE), où rien n'existe
// encore à renommer -- titre générique messages_name_dialog_title ("Nommer la trace") -- et au
// renommage d'une trace déjà banquée (RENAME depuis la trace ouverte, RENAME_FROM_LIST depuis la
// liste), qui porte déjà un nom : titre dédié gpximport_rename_dialog_title ("Renommer la trace"),
// aligné sur celui du Journal (journal_detail_rename_dialog_title). Fonction pure extraite du
// Composable pour rester testable sans Compose (GpxImportRenameDialogTitleTest).
@StringRes
internal fun nameDialogTitleRes(purpose: NameDialogPurpose): Int = when (purpose) {
    NameDialogPurpose.RENAME, NameDialogPurpose.RENAME_FROM_LIST -> R.string.gpximport_rename_dialog_title
    NameDialogPurpose.FIRST_SAVE, NameDialogPurpose.SAVE_THEN_CLOSE, NameDialogPurpose.DUPLICATE ->
        R.string.messages_name_dialog_title
}

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
    val pendingDuplicateName by viewModel.pendingDuplicateName.collectAsStateWithLifecycle()
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
        viewModel.openDuplicateFromLoggedTrack(
            request.track,
            request.bivouacPoints,
            request.suggestedName,
            request.sourceName,
        )
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

    // RIC-188 : les deux textes du nom de fichier exporte, lus ici pour que les lambdas d'export
    // ci-dessous n'aient plus qu'a les composer (voir onExportSegment).
    val defaultTrackName = stringResource(R.string.planification_default_track_name)
    val segmentExportNameFormat = stringResource(R.string.planification_segment_export_name_format)

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
    val screenMode = planificationScreenMode(uiState, bankedTraces.isEmpty(), bankedTracesLoaded)
    if (screenMode == PlanificationScreenMode.LOADING) {
        // RIC-164 : rien, surtout pas de carte, tant que la première lecture Room n'a pas répondu.
        // Voir [planificationScreenMode] pour ce que ce trou coûtait.
        Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            SectionMenuButton(
                current = currentSection,
                onSelect = onSectionSelected,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
            )
        }
    } else if (screenMode == PlanificationScreenMode.EMPTY) {
        Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            FullScreenEmptyState(
                icon = Icons.Default.Route,
                title = stringResource(R.string.gpximport_empty_title),
                subtitle = stringResource(R.string.gpximport_empty_subtitle),
                buttonText = stringResource(R.string.gpximport_open_track_button),
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
                            contentDescription = if (expanded) null else stringResource(R.string.gpximport_open_track_button),
                        )
                    },
                    text = { Text(stringResource(R.string.gpximport_open_track_button)) },
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
                    // RIC-188 : le nom porte par le GPX exporte est visible, il passe donc par des
                    // ressources et non plus par une concatenation. Lues hors de la lambda (via
                    // stringResource et non context.getString) : c'est la lecture qui suit les
                    // changements de configuration, cf. Lint LocalContextGetResourceValueCall.
                    val baseName = loaded.track.name ?: defaultTrackName
                    val dayName = String.format(Locale.getDefault(), segmentExportNameFormat, baseName, index + 1)
                    context.startActivity(GpxExporter.openIntent(context, segment.points, dayName))
                },
                onExportTrack = {
                    val name = loaded.track.name ?: defaultTrackName
                    context.startActivity(GpxExporter.openIntent(context, loaded.track.points, name))
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
        // RIC-121 : deux dialogues, un seul état. Le blocage est le même (une trace ouverte qu'on
        // ne peut pas lâcher en silence), mais ce qui l'a déclenché change complètement ce que
        // l'utilisateur doit décider : fermer, ou remplacer par la copie d'une sortie du Journal
        // qui attend derrière (RIC-40). Le dialogue générique disait « Annuler » pour une action
        // qui abandonne toute la duplication : c'est ce contresens que ce cas dédié ferme.
        val duplicateSourceName = pendingDuplicateName
        if (duplicateSourceName != null) {
            val message = stringResource(
                when (reason) {
                    CloseConfirmationReason.DIRTY -> R.string.messages_replace_dirty_body
                    CloseConfirmationReason.NEVER_SAVED -> R.string.messages_replace_never_saved_body
                },
                duplicateSourceName,
            )
            AlertDialog(
                onDismissRequest = viewModel::dismissCloseConfirmation,
                title = { Text(stringResource(R.string.messages_replace_current_track_title)) },
                text = { Text(message) },
                // Les trois issues empilées et non alignées sur une ligne : Material prescrit
                // l'empilement dès que les libellés ne tiennent pas côte à côte, et « Enregistrer
                // puis ouvrir » + « Ne pas enregistrer » + « Annuler la duplication » débordent
                // largement la largeur d'un dialogue sur un téléphone. Tout est dans le slot
                // confirmButton, seul moyen de garder les trois dans le même empilement.
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = viewModel::saveAndClose) {
                            Text(stringResource(R.string.messages_save_then_open_button))
                        }
                        TextButton(onClick = viewModel::discardAndClose) {
                            Text(stringResource(R.string.journal_msg_discard_button), color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = viewModel::dismissCloseConfirmation) {
                            Text(stringResource(R.string.messages_cancel_duplicate_button))
                        }
                    }
                },
            )
        } else {
            val (titleRes, messageRes) = when (reason) {
                CloseConfirmationReason.DIRTY ->
                    R.string.messages_dirty_title to R.string.messages_dirty_body
                CloseConfirmationReason.NEVER_SAVED ->
                    R.string.messages_never_saved_title to R.string.messages_never_saved_body
            }
            AlertDialog(
                onDismissRequest = viewModel::dismissCloseConfirmation,
                title = { Text(stringResource(titleRes)) },
                text = { Text(stringResource(messageRes)) },
                confirmButton = {
                    TextButton(onClick = viewModel::saveAndClose) { Text(stringResource(R.string.common_save_button)) }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = viewModel::dismissCloseConfirmation) {
                            Text(stringResource(R.string.common_cancel_button))
                        }
                        TextButton(onClick = viewModel::discardAndClose) {
                            Text(stringResource(R.string.journal_msg_discard_button), color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        }
    }

    nameDialogRequest?.let { request ->
        var name by remember(request) { mutableStateOf(request.suggestedName) }
        AlertDialog(
            onDismissRequest = viewModel::dismissNameDialog,
            title = { Text(stringResource(nameDialogTitleRes(request.purpose))) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    // RIC-176 : majuscule automatique en début de phrase, comme le champ de note du
                    // journal ; un nom de trace est du texte libre, pas un identifiant.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmNameDialog(name) }) {
                    Text(stringResource(R.string.common_save_button))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissNameDialog) { Text(stringResource(R.string.common_cancel_button)) }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirmation,
            title = { Text(stringResource(R.string.journal_msg_delete_track_title)) },
            text = { Text(stringResource(R.string.messages_delete_confirm_body, target.name)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.common_delete_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteConfirmation) {
                    Text(stringResource(R.string.common_cancel_button))
                }
            },
        )
    }

    // RIC-127 (suite) : popup plutôt qu'écran plein : voir la kdoc de bankOpenError.
    bankOpenError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissBankOpenError,
            title = { Text(stringResource(R.string.journal_msg_open_failed_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissBankOpenError) { Text(stringResource(R.string.common_ok_button)) }
            },
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
                    Text(stringResource(R.string.gpximport_open_track_button))
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
    val context = LocalContext.current
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
                        text = formatSavedAt(context, entry.savedAt).let { savedAt ->
                            // RIC-188 : le point median et le compte sont un format a trous, pas
                            // une concatenation : le separateur appartient a la langue.
                            if (bivouacCount == 0) {
                                savedAt
                            } else {
                                stringResource(
                                    R.string.gpximport_saved_at_with_bivouac_count_format,
                                    savedAt,
                                    bivouacCount,
                                )
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (bivouacCount > 0) {
                        Image(
                            painter = painterResource(R.drawable.ic_bivouac_badge),
                            contentDescription = pluralStringResource(
                                R.plurals.planification_bivouac_count_description,
                                bivouacCount,
                            ),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.journal_detail_menu_description),
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.journal_detail_menu_rename)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuExpanded = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete_button)) },
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
//
// RIC-188 (lot 1 i18n) : le prefixe "aujourd'hui a" devient un format a trous
// (gpximport_saved_today_format), et le motif jour+mois passe lui aussi par une ressource
// (gpximport_saved_date_format) au lieu du test sur locale.language pose au lot 0 : c'est le seul
// motif du fichier dont l'ORDRE des champs depend de la langue ("3 aout" en francais, "August 3"
// en anglais), et ofLocalizedDate(FormatStyle) n'a pas de style "jour+mois sans annee" tout fait.
// D'ou le Context : cette fonction n'est pas composable, elle est appelee depuis BankedTrackRow.
internal fun formatSavedAt(context: Context, epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val instant = Instant.ofEpochMilli(epochMillis)
    val locale = Locale.getDefault()
    return if (instant.atZone(zone).toLocalDate() == LocalDate.now(zone)) {
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant)
        context.getString(R.string.gpximport_saved_today_format, time)
    } else {
        val dayMonthPattern = context.getString(R.string.gpximport_saved_date_format)
        DateTimeFormatter.ofPattern(dayMonthPattern, locale).withZone(zone).format(instant)
    }
}

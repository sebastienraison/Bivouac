package com.bivouac.app.journal

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bivouac.app.data.db.DuplicateMatch
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.db.PreparedImport
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.SpeedCalibrationCalculator
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.DayJunctions
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.Segment
import com.bivouac.app.data.prefs.MapLayerPreferences
import com.bivouac.app.data.prefs.SettingsPreferences
import com.bivouac.app.ui.map.MapLayer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface JournalUiState {
    data object Overview : JournalUiState
    data object Loading : JournalUiState
    // daySegments (RIC-41) : la trace redécoupée par jour importé, pour la ventilation
    // « Total » + « Jour N » — voir LoggedTrackRepository.openDetail.
    data class Detail(
        val entry: LoggedTrackEntity,
        val track: HikeTrack,
        val daySegments: List<Segment>,
    ) : JournalUiState
    // BIV-48: a contemplative overview of several traces at once — entries in the order they
    // should get their (rotating) legend color, each paired with its parsed track.
    data class MultiTrack(val entries: List<Pair<LoggedTrackEntity, HikeTrack>>) : JournalUiState
    data class Error(val message: String) : JournalUiState
}

// RIC-65 écran 3 : le sélecteur a renvoyé plusieurs fichiers, et rien ne permet de deviner s'il
// s'agit d'un trek en plusieurs jours ou de plusieurs sorties indépendantes — l'utilisateur
// tranche explicitement à chaque fois, sans heuristique de date ni de proximité.
data class MultiFileImportChoice(val fileCount: Int)

// Bilan d'un import « sorties séparées » : chaque fichier est traité indépendamment, donc l'échec
// ou le doublon de l'un n'empêche pas les autres d'entrer — d'où ce compte rendu de fin de lot,
// là où un import d'une seule sortie se contente d'ouvrir la trace importée.
// probableDuplicateNames : les traces importées malgré une ressemblance avec une sortie déjà
// présente (cf. processNextSeparateImport). Nommées, et pas seulement comptées : « 3 doublons
// possibles » n'est pas actionnable, l'utilisateur ne saurait pas lesquelles aller vérifier.
data class SeparateImportReport(
    val imported: Int,
    val duplicatesSkipped: Int,
    val failed: Int,
    val probableDuplicateNames: List<String> = emptyList(),
)

// Non nul du premier fichier lu jusqu'à la toute fin de l'opération, calibration comprise. Sert à
// bloquer l'écran : pouvoir ouvrir une autre trace pendant qu'un import écrit en base est un
// risque d'état incohérent, pas seulement un inconfort. La calibration en fait partie parce
// qu'elle est, aujourd'hui, l'étape la plus lente des deux (voir refreshAutoCalibration).
sealed interface ImportProgress {
    // done vaut 0 pour un trek en plusieurs jours : prepareImport lit ses fichiers d'un bloc, il
    // n'y a pas d'étape intermédiaire à montrer. Un lot de sorties séparées, lui, avance fichier
    // par fichier et peut donc compter.
    data class Reading(val done: Int, val total: Int) : ImportProgress

    data object Calibrating : ImportProgress
}

/**
 * Ce que la liste du Journal sait des jours d'une trace. [dates] peut être vide ou incomplète, un
 * GPX pouvant n'avoir aucun horodatage, alors que [dayCount] est toujours juste.
 *
 * D'où [bivouacCount] tiré de [dayCount] et non du nombre de dates : sur une trace importée, une
 * nuit dehors est exactement une coupure entre deux fichiers, connue même sans horodatage.
 */
data class JournalDayInfo(val dayCount: Int, val dates: List<LocalDate>) {
    val bivouacCount: Int get() = (dayCount - 1).coerceAtLeast(0)
}

// RIC-40 : tout ce dont la Planification a besoin pour ouvrir cette trace du Journal comme un
// nouveau plan éditable — construit ici, et pas dans GpxImportViewModel, parce que seul le côté
// Journal connaît les frontières entre jours. La trace du Journal, elle, n'est jamais touchée :
// elle est immuable une fois importée. bivouacPoints porte déjà un point par jonction de fichiers
// (vide pour une trace d'un seul jour) ; l'écran d'arrivée le charge comme n'importe quelle autre
// sélection de bivouacs.
data class DuplicatePlanRequest(
    val track: HikeTrack,
    val bivouacPoints: List<BivouacPoint>,
    val suggestedName: String,
)

class JournalViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LoggedTrackRepository(application)
    // Resolver de l'application, et pas celui passé par l'écran : un import peut s'étaler sur
    // plusieurs allers-retours avec l'utilisateur (avertissement de doublon), il ne doit pas
    // garder une référence vers un Context d'Activity pendant ce temps. Les permissions de
    // lecture accordées par le sélecteur valent pour tout le process.
    private val contentResolver = application.contentResolver
    private val mapLayerPreferences = MapLayerPreferences(application)
    private val settingsPreferences = SettingsPreferences(application)

    private val _tracks = MutableStateFlow<List<LoggedTrackEntity>>(emptyList())
    // RIC-65 : liste non filtrée, pour distinguer « aucune trace jamais importée » (écran 1, CTA
    // plein écran) d'un « filtre à zéro résultat » (écran 3, la banque n'est pas vide) — filteredTracks
    // seul ne permet pas cette distinction une fois un filtre actif.
    val tracks: StateFlow<List<LoggedTrackEntity>> = _tracks.asStateFlow()

    // trackId -> its tags, for every track that has at least one — drives both the filter chips
    // (distinct values across all tracks) and which entries a filter selection keeps.
    private val _tagsByTrackId = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val tagsByTrackId: StateFlow<Map<String, List<String>>> = _tagsByTrackId.asStateFlow()

    private val _selectedFilterTags = MutableStateFlow<Set<String>>(emptySet())
    val selectedFilterTags: StateFlow<Set<String>> = _selectedFilterTags.asStateFlow()

    // trackId -> ce que la liste doit savoir de ses jours, pour distinguer un trek d'une sortie
    // d'un jour : leurs dates, et leur nombre. Les dates manquent tant que le rattrapage n'a pas
    // relevé celles d'une trace, auquel cas la liste se contente de la date de départ, comme
    // avant ; le nombre de jours, lui, est toujours connu.
    private val _dayInfoByTrackId = MutableStateFlow<Map<String, JournalDayInfo>>(emptyMap())
    val dayInfoByTrackId: StateFlow<Map<String, JournalDayInfo>> = _dayInfoByTrackId.asStateFlow()

    // OR semantics: a track matching any one selected tag is kept — narrows what's browsable,
    // doesn't require an exact combination match.
    val filteredTracks: StateFlow<List<LoggedTrackEntity>> =
        combine(_tracks, _tagsByTrackId, _selectedFilterTags) { tracks, tagsByTrackId, selected ->
            if (selected.isEmpty()) {
                tracks
            } else {
                tracks.filter { entry -> tagsByTrackId[entry.id]?.any { it in selected } == true }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow<JournalUiState>(JournalUiState.Overview)
    val uiState: StateFlow<JournalUiState> = _uiState.asStateFlow()

    // Tags of whichever track is currently open — kept separate from
    // tagsByTrackId's bulk map so editing one track doesn't need re-querying every track's tags.
    private val _currentTags = MutableStateFlow<List<String>>(emptyList())
    val currentTags: StateFlow<List<String>> = _currentTags.asStateFlow()

    // Shared with Planification — one "which map style" preference for the whole app, not a
    // per-screen setting. Satellite falls back to the free default while non-free features are
    // disabled (BIV-16), same as Planification's own selectedLayer.
    val selectedLayer: StateFlow<MapLayer> = combine(
        mapLayerPreferences.selectedLayer,
        settingsPreferences.nonFreeFeaturesDisabled,
    ) { layer, nonFreeDisabled ->
        if (nonFreeDisabled && layer == MapLayer.SATELLITE) MapLayer.HIKING else layer
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapLayer.HIKING)

    val nonFreeFeaturesDisabled: StateFlow<Boolean> = settingsPreferences.nonFreeFeaturesDisabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // BIV-16 Vitesse personnalisée: whichever calibration is currently active, applied when
    // importing a new hike (existing entries keep the duration they were imported with).
    val activeCalibration: StateFlow<SpeedCalibration> = settingsPreferences.effectiveCalibration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeedCalibration.DEFAULT)

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    // Le match entier et pas seulement la trace ressemblante : l'avertissement ne dit pas la même
    // chose selon qu'il s'agit d'une sortie qui ressemble à une autre ou d'un jour déjà présent.
    private val _duplicateWarning = MutableStateFlow<DuplicateMatch?>(null)
    val duplicateWarning: StateFlow<DuplicateMatch?> = _duplicateWarning.asStateFlow()
    private var pendingImport: PreparedImport? = null

    // RIC-65 écran 3 : non nul tant que l'utilisateur n'a pas tranché entre trek multi-jours et
    // sorties séparées. Aucun fichier n'est lu avant ce choix — « Abandonner » ne peut donc pas
    // laisser d'import partiel derrière lui.
    private val _multiFileImportChoice = MutableStateFlow<MultiFileImportChoice?>(null)
    val multiFileImportChoice: StateFlow<MultiFileImportChoice?> = _multiFileImportChoice.asStateFlow()
    private var pendingChoiceUris: List<Uri> = emptyList()

    private val _separateImportReport = MutableStateFlow<SeparateImportReport?>(null)
    val separateImportReport: StateFlow<SeparateImportReport?> = _separateImportReport.asStateFlow()

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()
    private var separateTotal = 0

    // File d'attente de l'import « sorties séparées » : non nulle du premier fichier au bilan de
    // fin. Elle survit à l'avertissement de doublon, qui la suspend puis la relance.
    private var separateQueue: ArrayDeque<Uri>? = null
    private var separateImported = 0
    private var separateDuplicatesSkipped = 0
    private var separateFailed = 0
    private val separateProbableNames = mutableListOf<String>()

    private val _deleteTarget = MutableStateFlow<LoggedTrackEntity?>(null)
    val deleteTarget: StateFlow<LoggedTrackEntity?> = _deleteTarget.asStateFlow()

    // BIV-47: entered via long-press on a list row; while active, tapping a row toggles it
    // instead of opening it. Cleared automatically once the multi-trace map view is shown.
    private val _selectionModeActive = MutableStateFlow(false)
    val selectionModeActive: StateFlow<Boolean> = _selectionModeActive.asStateFlow()

    private val _selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedTrackIds: StateFlow<Set<String>> = _selectedTrackIds.asStateFlow()

    // BIV-16: same selection mechanics as BIV-47 above, reused rather than duplicated, but
    // entered from Réglages ("Choisir les traces") to pick the Sélection calibration's tracks
    // instead of the map. While true, JournalScreen swaps "Afficher la sélection" for "Confirmer
    // la sélection" and confirming writes into SettingsPreferences instead of opening the map.
    private val _calibrationSelectionActive = MutableStateFlow(false)
    val calibrationSelectionActive: StateFlow<Boolean> = _calibrationSelectionActive.asStateFlow()

    init {
        refresh()
        // Rattrapage des colonnes dénormalisées, sans effet une fois la banque à jour. Lancé ici
        // plutôt qu'à l'ouverture de la base : c'est le seul endroit où le travail a un scope qui
        // s'annule (quitter le Journal l'interrompt) et où il ne retarde l'affichage de rien.
        viewModelScope.launch {
            val backfilled = withContext(Dispatchers.IO) {
                runCatching { repository.backfillDenormalizedFields() }
                    .onFailure { Log.w("JournalViewModel", "Rattrapage interrompu", it) }
                    .isSuccess
            }
            // Les plages de dates de la liste viennent des colonnes que le rattrapage remplit :
            // sans ce second passage, elles n'apparaîtraient qu'au prochain lancement.
            if (backfilled) refresh()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _tracks.value = repository.list()
                _tagsByTrackId.value = repository.tagsByTrackId()
                val zone = ZoneId.systemDefault()
                _dayInfoByTrackId.value = repository.daySummariesByTrackId()
                    .mapValues { (_, summary) ->
                        JournalDayInfo(
                            dayCount = summary.dayCount,
                            dates = summary.startMillis.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() },
                        )
                    }
            }
        }
    }

    fun setSelectedLayer(layer: MapLayer) {
        viewModelScope.launch { mapLayerPreferences.setSelectedLayer(layer) }
    }

    fun toggleFilterTag(tag: String) {
        _selectedFilterTags.value = _selectedFilterTags.value.let { if (tag in it) it - tag else it + tag }
    }

    fun openTrack(entry: LoggedTrackEntity) {
        _uiState.value = JournalUiState.Loading
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.openDetail(entry.id) }
            }.onSuccess { detail ->
                _uiState.value = if (detail != null) {
                    _currentTags.value = _tagsByTrackId.value[entry.id].orEmpty()
                    JournalUiState.Detail(entry, detail.track, detail.daySegments)
                } else {
                    JournalUiState.Error("Trace introuvable.")
                }
            }.onFailure {
                Log.e("JournalViewModel", "Échec de l'ouverture d'une trace du journal", it)
                _uiState.value = JournalUiState.Error("Trace incorrecte ou fichier illisible.")
            }
        }
    }

    /**
     * RIC-40 : null quand il n'y a rien à dupliquer (on n'est pas sur la vue détail). Les points
     * de bivouac tombent aux jonctions entre jours, voir [DayJunctions] — liste vide pour une
     * trace d'un seul jour, que la Planification ouvre alors comme n'importe quelle trace sans
     * bivouac. Rien n'est écrit ici : la trace du Journal reste telle qu'elle a été importée.
     */
    fun buildDuplicateForPlanification(): DuplicatePlanRequest? {
        val state = _uiState.value as? JournalUiState.Detail ?: return null
        val junctions = DayJunctions.bivouacTrackPointIndices(state.daySegments.map { it.points.size })
        return DuplicatePlanRequest(
            track = state.track,
            bivouacPoints = junctions.map { BivouacPoint(id = UUID.randomUUID().toString(), trackPointIndex = it) },
            suggestedName = "Copie de ${state.entry.name}",
        )
    }

    fun closeTrack() {
        _uiState.value = JournalUiState.Overview
    }

    fun enterSelectionMode(initialId: String) {
        _selectionModeActive.value = true
        _selectedTrackIds.value = setOf(initialId)
    }

    fun exitSelectionMode() {
        _selectionModeActive.value = false
        _calibrationSelectionActive.value = false
        _selectedTrackIds.value = emptySet()
    }

    // Pre-checks whatever is already saved as the Sélection calibration's tracks, so reopening
    // this flow shows the current choice rather than starting from empty.
    fun enterCalibrationSelectionMode() {
        _calibrationSelectionActive.value = true
        _selectionModeActive.value = true
        viewModelScope.launch { _selectedTrackIds.value = settingsPreferences.selectedTrackIds.first() }
    }

    // The caller (JournalScreen) navigates back to Réglages immediately after calling this —
    // that pops this screen's NavBackStackEntry, which clears this ViewModel and cancels
    // viewModelScope. Without NonCancellable, that race routinely won the race against the write
    // below (calibrationSamples() parses GPX, never instant), so the confirmed selection just
    // never made it to disk — this is why "Confirmer la sélection (N)" wasn't reliably updating
    // Réglages' count. NonCancellable keeps this specific write alive past that cancellation.
    // JournalListContent already disables the confirm button below this — guarded again here in
    // case that ever gets bypassed, since a 1-trace Sélection can't be told apart from a genuine
    // 2+ fit (see SpeedCalibrationCalculator's MIN_TRACKS_FOR_CALIBRATION).
    fun confirmCalibrationSelection() {
        val ids = _selectedTrackIds.value
        if (ids.size < SpeedCalibrationCalculator.MIN_TRACKS_FOR_CALIBRATION) return
        exitSelectionMode()
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                val samples = repository.calibrationSamples(ids)
                val calibration = SpeedCalibrationCalculator.compute(samples) ?: SpeedCalibration.DEFAULT
                settingsPreferences.setSelectionCalibration(calibration, ids)
            }
        }
    }

    fun toggleTrackSelection(id: String) {
        _selectedTrackIds.value = _selectedTrackIds.value.let { if (id in it) it - id else it + id }
    }

    // Standard "select all if not all selected yet, else clear" checkbox behavior — no partial
    // (indeterminate) visual state, just a plain toggle.
    fun toggleYearSelection(ids: List<String>) {
        _selectedTrackIds.value = if (_selectedTrackIds.value.containsAll(ids)) {
            _selectedTrackIds.value - ids.toSet()
        } else {
            _selectedTrackIds.value + ids
        }
    }

    // Nothing selected → everything currently listed (already tag-filtered if a filter is
    // active); a selection → just that. Either way, selection mode resets once the map opens.
    fun showOnMap() {
        val idsToShow = _selectedTrackIds.value.ifEmpty { filteredTracks.value.map { it.id }.toSet() }
        val entriesToShow = _tracks.value.filter { it.id in idsToShow }
        exitSelectionMode()
        _uiState.value = JournalUiState.Loading
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                entriesToShow.mapNotNull { entry -> repository.open(entry.id)?.let { entry to it } }
            }
            _uiState.value = JournalUiState.MultiTrack(loaded)
        }
    }

    fun closeMultiTrack() {
        _uiState.value = JournalUiState.Overview
    }

    fun renameCurrentTrack(name: String) {
        val entry = currentEntry() ?: return
        val trimmed = name.trim().ifBlank { return }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.rename(entry.id, trimmed) }
            _tracks.value = _tracks.value.map { if (it.id == entry.id) it.copy(name = trimmed) else it }
            val state = _uiState.value as? JournalUiState.Detail
            if (state != null && state.entry.id == entry.id) {
                _uiState.value = JournalUiState.Detail(state.entry.copy(name = trimmed), state.track, state.daySegments)
            }
        }
    }

    /**
     * Commits a whole edit-mode draft at once (tags + note together) — nothing is written while
     * the user is merely toggling chips or typing; editing this "détails" data isn't a frequent
     * operation, so it gets an explicit save/discard step rather than writing through on every tap
     * like the rest of the app does for more routine actions.
     */
    fun saveDetails(tags: Set<String>, note: String) {
        val entry = currentEntry() ?: return
        val previousTags = _currentTags.value.toSet()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                (tags - previousTags).forEach { repository.addTag(entry.id, it) }
                (previousTags - tags).forEach { repository.removeTag(entry.id, it) }
                repository.updateNote(entry.id, note)
            }
            _currentTags.value = tags.toList()
            _tagsByTrackId.value = _tagsByTrackId.value + (entry.id to tags.toList())
            refreshCurrentEntry(entry.id, note = note)
        }
    }

    private fun currentEntry(): LoggedTrackEntity? = when (val state = _uiState.value) {
        is JournalUiState.Detail -> state.entry
        else -> null
    }

    private fun refreshCurrentEntry(id: String, note: String) {
        _tracks.value = _tracks.value.map { if (it.id == id) it.copy(note = note) else it }
        val state = _uiState.value as? JournalUiState.Detail
        if (state != null && state.entry.id == id) {
            _uiState.value = JournalUiState.Detail(state.entry.copy(note = note), state.track, state.daySegments)
        }
    }

    fun requestDelete() {
        _deleteTarget.value = currentEntry()
    }

    fun dismissDeleteConfirmation() {
        _deleteTarget.value = null
    }

    fun confirmDelete() {
        val target = _deleteTarget.value ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.delete(target.id) }
            _deleteTarget.value = null
            _uiState.value = JournalUiState.Overview
            refresh()
        }
    }

    /**
     * Point d'entrée unique de l'import depuis le sélecteur de fichiers. Un seul fichier passe
     * directement (il n'y a rien à trancher) ; plusieurs déclenchent le choix de RIC-65 écran 3,
     * avant toute lecture de fichier.
     */
    fun importTracks(uris: List<Uri>) {
        when {
            uris.isEmpty() -> return
            uris.size == 1 -> importAsSingleTrack(uris)
            else -> {
                pendingChoiceUris = uris
                _multiFileImportChoice.value = MultiFileImportChoice(uris.size)
            }
        }
    }

    /** « Un seul trek en plusieurs jours » : un fichier = un jour d'une même entrée du Journal. */
    fun chooseMultiDayImport() {
        val uris = consumeImportChoice() ?: return
        importAsSingleTrack(uris)
    }

    /** « Sorties séparées » : N entrées indépendantes du Journal, traitées une par une. */
    fun chooseSeparateImports() {
        val uris = consumeImportChoice() ?: return
        separateQueue = ArrayDeque(uris)
        separateTotal = uris.size
        separateImported = 0
        separateDuplicatesSkipped = 0
        separateFailed = 0
        separateProbableNames.clear()
        processNextSeparateImport()
    }

    /** « Abandonner » : rien n'a encore été lu ni écrit, il n'y a donc rien à défaire. */
    fun cancelMultiFileImport() {
        consumeImportChoice()
    }

    private fun consumeImportChoice(): List<Uri>? {
        val uris = pendingChoiceUris.takeIf { it.isNotEmpty() }
        pendingChoiceUris = emptyList()
        _multiFileImportChoice.value = null
        return uris
    }

    /**
     * RIC-41 : plusieurs fichiers ici forment une seule sortie de plusieurs jours (un fichier =
     * un jour) — c'est [LoggedTrackRepository.prepareImport] qui ordonne les jours et agrège les
     * statistiques.
     *
     * Import tout-ou-rien : un fichier illisible fait échouer le lot entier plutôt que de laisser
     * une sortie multi-jours amputée d'un jour, plus trompeuse qu'une absence d'import. C'est
     * exactement l'inverse du mode « sorties séparées » ci-dessous, où l'indépendance des
     * fichiers est justement ce qui a été demandé.
     */
    private fun importAsSingleTrack(uris: List<Uri>) {
        _importProgress.value = ImportProgress.Reading(done = 0, total = uris.size)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val prepared = repository.prepareImport(contentResolver, uris, activeCalibration.value)
                    prepared to repository.findDuplicate(prepared)
                }
            }.onSuccess { (prepared, duplicate) ->
                when (duplicate) {
                    is DuplicateMatch.Exact ->
                        _importError.value = "« ${duplicate.existing.name} » est déjà dans le journal."
                    is DuplicateMatch.Probable, is DuplicateMatch.SharedDay -> {
                        pendingImport = prepared
                        _duplicateWarning.value = duplicate
                    }
                    null -> commit(prepared, openAfterCommit = true)
                }
            }.onFailure {
                Log.e("JournalViewModel", "Échec de l'import GPX (Journal)", it)
                _importError.value = "Trace incorrecte ou fichier illisible."
            }
            // Après le commit et sa calibration, donc après l'opération entière : ce qui suit
            // (avertissement de doublon, erreur, vue détail) est de nouveau manipulable.
            _importProgress.value = null
        }
    }

    // Un fichier à la fois, mais sans jamais rendre la main : rien dans ce mode n'interrompt le lot
    // pour poser une question. Le suivant n'est lu qu'une fois le précédent écrit, pour ne pas
    // paralléliser des écritures en base sur un lot de plusieurs dizaines de fichiers.
    private fun processNextSeparateImport() {
        val queue = separateQueue ?: return
        val uri = queue.removeFirstOrNull() ?: return finishSeparateImports()
        _importProgress.value = ImportProgress.Reading(done = separateTotal - queue.size - 1, total = separateTotal)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val prepared = repository.prepareImport(contentResolver, listOf(uri), activeCalibration.value)
                    prepared to repository.findDuplicate(prepared)
                }
            }.onSuccess { (prepared, duplicate) ->
                when (duplicate) {
                    // Écarté sans dialogue, contrairement à l'import d'une sortie seule : c'est le
                    // bilan de fin de lot qui le rapporte, sans interrompre les fichiers suivants.
                    is DuplicateMatch.Exact -> {
                        separateDuplicatesSkipped++
                        processNextSeparateImport()
                    }
                    // Importé quand même, et signalé dans le bilan de fin plutôt que par une
                    // question bloquante : sur un import de masse, une erreur visible et
                    // réversible (un doublon apparaît dans la liste, se supprime en deux taps)
                    // vaut mieux qu'un oubli silencieux, et se faire arrêter plusieurs fois au
                    // milieu de 66 fichiers est exactement la friction que ce mode doit éviter.
                    // La politique devient au passage cohérente entre les deux niveaux de
                    // détection : doublon certain écarté en silence, doublon probable importé
                    // puis signalé.
                    is DuplicateMatch.Probable, is DuplicateMatch.SharedDay -> {
                        commit(prepared, openAfterCommit = false, refreshCalibration = false)
                        separateImported++
                        separateProbableNames += prepared.entity.name
                        processNextSeparateImport()
                    }
                    null -> {
                        commit(prepared, openAfterCommit = false, refreshCalibration = false)
                        separateImported++
                        processNextSeparateImport()
                    }
                }
            }.onFailure {
                Log.e("JournalViewModel", "Échec de l'import GPX (Journal, sorties séparées)", it)
                separateFailed++
                processNextSeparateImport()
            }
        }
    }

    private fun finishSeparateImports() {
        separateQueue = null
        val imported = separateImported
        val report = SeparateImportReport(
            imported = imported,
            duplicatesSkipped = separateDuplicatesSkipped,
            failed = separateFailed,
            probableDuplicateNames = separateProbableNames.toList(),
        )
        // Une seule fois pour tout le lot, et pas après chaque fichier : recalculer la calibration
        // Auto reparse le GPX de tout le Journal, donc la faire N fois d'affilée coûte N passes
        // complètes pour un résultat que seule la dernière détermine.
        //
        // Le bilan n'est posé qu'après : sur une banque un peu fournie cette passe se compte en
        // secondes, et afficher « Import terminé » par-dessus un traitement encore en cours
        // reviendrait à rendre l'écran manipulable au pire moment.
        viewModelScope.launch {
            if (imported > 0) {
                _importProgress.value = ImportProgress.Calibrating
                withContext(Dispatchers.IO) { refreshAutoCalibration() }
            }
            _importProgress.value = null
            _separateImportReport.value = report
        }
    }

    fun dismissSeparateImportReport() {
        _separateImportReport.value = null
    }

    // L'avertissement de doublon probable ne concerne plus que l'import d'une sortie seule : une
    // question pour un fichier ne coûte rien, et l'utilisateur a le contexte pour y répondre. Un
    // lot de sorties séparées, lui, ne pose jamais la question (cf. processNextSeparateImport).
    fun confirmImportAnyway() {
        val prepared = pendingImport ?: return
        pendingImport = null
        _duplicateWarning.value = null
        viewModelScope.launch {
            commit(prepared, openAfterCommit = true)
            _importProgress.value = null
        }
    }

    fun dismissDuplicateWarning() {
        pendingImport = null
        _duplicateWarning.value = null
    }

    fun dismissImportError() {
        _importError.value = null
    }

    private suspend fun commit(
        prepared: PreparedImport,
        openAfterCommit: Boolean,
        refreshCalibration: Boolean = true,
    ) {
        withContext(Dispatchers.IO) { repository.commitImport(prepared) }
        if (refreshCalibration) {
            _importProgress.value = ImportProgress.Calibrating
            withContext(Dispatchers.IO) { refreshAutoCalibration() }
        }
        refresh()
        // Mirrors Planification's "open a track" behavior: a just-imported trace should land
        // straight on its detail view, not merely appear in the list waiting to be tapped. Un lot
        // de sorties séparées y échappe : en ouvrir une seule, arbitrairement, ne dirait rien du
        // reste du lot — c'est le bilan de fin qui tient ce rôle.
        if (openAfterCommit) openTrack(prepared.entity)
    }

    // BIV-16 Auto mode: recomputed on every import regardless of which mode is currently active,
    // so switching to Auto later never shows a stale value from before the last import.
    private suspend fun refreshAutoCalibration() {
        val calibration = SpeedCalibrationCalculator.compute(repository.calibrationSamples()) ?: return
        settingsPreferences.setAutoCalibration(calibration)
    }
}

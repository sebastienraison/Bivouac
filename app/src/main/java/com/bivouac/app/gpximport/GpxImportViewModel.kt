package com.bivouac.app.gpximport

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bivouac.app.data.db.BankedTrackEntity
import com.bivouac.app.data.db.BankedTrackRepository
import com.bivouac.app.data.db.SavedTrackRepository
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.TrackStats
import com.bivouac.app.data.gpx.TrackStatsCalculator
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.Segment
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.prefs.MapLayerPreferences
import com.bivouac.app.data.prefs.SettingsPreferences
import com.bivouac.app.ui.map.MapLayer
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface GpxImportUiState {
    data object Idle : GpxImportUiState
    data object Loading : GpxImportUiState
    data class Loaded(val track: HikeTrack, val stats: TrackStats) : GpxImportUiState
    data class Error(val message: String) : GpxImportUiState
}

enum class NameDialogPurpose { FIRST_SAVE, RENAME, RENAME_FROM_LIST, DUPLICATE, SAVE_THEN_CLOSE }

// RIC-27: pourquoi la fermeture est bloquée — modifications non enregistrées sur une trace déjà
// banquée, ou trace jamais banquée du tout (même sans modification depuis son ouverture).
enum class CloseConfirmationReason { DIRTY, NEVER_SAVED }

data class NameDialogRequest(val suggestedName: String, val purpose: NameDialogPurpose, val targetId: String? = null)

sealed interface DeleteTarget {
    val id: String
    val name: String

    data class Current(override val id: String, override val name: String) : DeleteTarget
    data class FromList(override val id: String, override val name: String) : DeleteTarget
}

class GpxImportViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SavedTrackRepository(application)
    private val bankRepository = BankedTrackRepository(application)
    private val mapLayerPreferences = MapLayerPreferences(application)
    private val settingsPreferences = SettingsPreferences(application)

    // Satellite falls back to the free default the instant non-free features get disabled, even
    // though the stored preference is left untouched — re-enabling later restores the user's
    // actual choice instead of having silently overwritten it.
    val selectedLayer: StateFlow<MapLayer> = combine(
        mapLayerPreferences.selectedLayer,
        settingsPreferences.nonFreeFeaturesDisabled,
    ) { layer, nonFreeDisabled ->
        if (nonFreeDisabled && layer == MapLayer.SATELLITE) MapLayer.HIKING else layer
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MapLayer.HIKING)

    val nonFreeFeaturesDisabled: StateFlow<Boolean> = settingsPreferences.nonFreeFeaturesDisabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // BIV-16 Vitesse personnalisée: whichever calibration (Manuel/Auto/Sélection) is currently
    // active, applied to every TrackStatsCalculator.compute() call below.
    val activeCalibration: StateFlow<SpeedCalibration> = settingsPreferences.effectiveCalibration
        .stateIn(viewModelScope, SharingStarted.Eagerly, SpeedCalibration.DEFAULT)

    fun setSelectedLayer(layer: MapLayer) {
        viewModelScope.launch { mapLayerPreferences.setSelectedLayer(layer) }
    }

    private val _uiState = MutableStateFlow<GpxImportUiState>(GpxImportUiState.Idle)
    val uiState: StateFlow<GpxImportUiState> = _uiState.asStateFlow()

    private val _bivouacPoints = MutableStateFlow<List<BivouacPoint>>(emptyList())
    val bivouacPoints: StateFlow<List<BivouacPoint>> = _bivouacPoints.asStateFlow()

    // Live position of a point currently being dragged on the map, kept separate from
    // [bivouacPoints] so the segments table can reflect it in real time without feeding back into
    // HikeMapView: that would tear down and recreate the marker mid-gesture and break the drag.
    private val _dragPreview = MutableStateFlow<Pair<String, Int>?>(null)

    // Bivouac points with the currently dragged one (if any) at its live preview position —
    // shared by the segments table and the elevation profile marker, both of which should track
    // the gesture in real time rather than only jump on release.
    val effectiveBivouacPoints: StateFlow<List<BivouacPoint>> = combine(_bivouacPoints, _dragPreview) { points, preview ->
        applyDragPreview(points, preview)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val segments: StateFlow<List<Segment>> = combine(_uiState, effectiveBivouacPoints, activeCalibration) { state, points, calibration ->
        val track = (state as? GpxImportUiState.Loaded)?.track
        if (track == null || points.isEmpty()) return@combine emptyList()
        computeSegments(track.points, points, calibration)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Banque de traces ---
    // The auto-save above (repository / SavedTrackEntity) is an invisible safety net against a
    // crash or restart. This section is the deliberate, named collection the user explicitly
    // saves to — a separate table and a separate mechanism, on purpose (see CONCEPTION notes).

    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    private val _currentBankedId = MutableStateFlow<String?>(null)
    val currentBankedId: StateFlow<String?> = _currentBankedId.asStateFlow()

    private val _bankedTraces = MutableStateFlow<List<BankedTrackEntity>>(emptyList())
    val bankedTraces: StateFlow<List<BankedTrackEntity>> = _bankedTraces.asStateFlow()

    private val _nameDialogRequest = MutableStateFlow<NameDialogRequest?>(null)
    val nameDialogRequest: StateFlow<NameDialogRequest?> = _nameDialogRequest.asStateFlow()

    private val _closeConfirmationReason = MutableStateFlow<CloseConfirmationReason?>(null)
    val closeConfirmationReason: StateFlow<CloseConfirmationReason?> = _closeConfirmationReason.asStateFlow()

    private val _deleteTarget = MutableStateFlow<DeleteTarget?>(null)
    val deleteTarget: StateFlow<DeleteTarget?> = _deleteTarget.asStateFlow()

    // RIC-127 (suite) : un GPX illisible dans la banque échoue en restant sur la liste (Idle),
    // avec ce message en popup par-dessus — même patron que RestoreOutcome.Error côté Réglages.
    // GpxImportUiState.Error écrase tout l'écran, ce qui convient à restoreLastTrack (rien à
    // perdre au démarrage) mais pas ici : la liste contient d'autres traces valides, un
    // remplacement complet les ferait disparaître pour rien.
    private val _bankOpenError = MutableStateFlow<String?>(null)
    val bankOpenError: StateFlow<String?> = _bankOpenError.asStateFlow()

    fun dismissBankOpenError() {
        _bankOpenError.value = null
    }

    init {
        refreshBankedTraces()
    }

    private fun refreshBankedTraces() {
        viewModelScope.launch {
            _bankedTraces.value = withContext(Dispatchers.IO) { bankRepository.list() }
        }
    }

    // Silent update if already banked or the track already has a name (e.g. from the GPX itself);
    // otherwise prompts for one — matches the "ask a name only if it doesn't have one yet" rule.
    fun requestSave() {
        val state = _uiState.value as? GpxImportUiState.Loaded ?: return
        val name = state.track.name
        when {
            _currentBankedId.value != null -> saveToBank(name ?: "")
            !name.isNullOrBlank() -> saveToBank(name)
            else -> _nameDialogRequest.value = NameDialogRequest("", NameDialogPurpose.FIRST_SAVE)
        }
    }

    // Only meaningful once the trace is actually banked — renaming something never saved is just
    // editing the name before that first save, already covered by the FIRST_SAVE prompt.
    fun requestRename() {
        val state = _uiState.value as? GpxImportUiState.Loaded ?: return
        if (_currentBankedId.value == null) return
        _nameDialogRequest.value = NameDialogRequest(state.track.name ?: "", NameDialogPurpose.RENAME)
    }

    fun requestDuplicate() {
        val state = _uiState.value as? GpxImportUiState.Loaded ?: return
        val baseName = state.track.name?.takeIf { it.isNotBlank() } ?: "Trace"
        _nameDialogRequest.value = NameDialogRequest("Copie de $baseName", NameDialogPurpose.DUPLICATE)
    }

    // Same convention as the open-trace toolbar, applied to a list row: renames that specific
    // bank entry directly, independent of whatever (if anything) is currently open — the home
    // screen list and an open trace are never shown at once, so there's no id collision to worry
    // about between this and requestRename().
    fun requestRenameFromList(id: String, currentName: String) {
        _nameDialogRequest.value = NameDialogRequest(currentName, NameDialogPurpose.RENAME_FROM_LIST, targetId = id)
    }

    fun confirmNameDialog(name: String) {
        val request = _nameDialogRequest.value ?: return
        _nameDialogRequest.value = null
        val trimmed = name.trim().ifBlank { "Trace" }
        when (request.purpose) {
            // A rename is just a save under the existing id with a new name — no separate
            // persistence path needed.
            NameDialogPurpose.FIRST_SAVE, NameDialogPurpose.RENAME -> saveToBank(trimmed)
            NameDialogPurpose.RENAME_FROM_LIST -> renameInBank(request.targetId ?: return, trimmed)
            NameDialogPurpose.DUPLICATE -> duplicateToBank(trimmed)
            NameDialogPurpose.SAVE_THEN_CLOSE -> saveToBank(trimmed, thenClose = true)
        }
    }

    private fun renameInBank(id: String, name: String) {
        viewModelScope.launch {
            // RIC-127 : rename() reparse le GPX (pour resynchroniser le <name> embarqué) et peut
            // donc échouer sur un fichier devenu illisible. Pas de _uiState.Error ici à dessein :
            // cette fonction peut s'exécuter pendant qu'une AUTRE trace est ouverte à l'écran
            // (rename depuis la liste de la banque), écraser tout l'écran pour un échec sur une
            // entrée différente serait pire que l'absence de retour visuel. Juste empêcher le
            // crash pour l'instant — un vrai retour utilisateur (snackbar ?) reste à définir, voir
            // RIC-127 pour la discussion.
            runCatching { withContext(Dispatchers.IO) { bankRepository.rename(id, name) } }
                .onFailure { Log.e("GpxImportViewModel", "Échec du renommage d'une trace de la banque", it) }
            refreshBankedTraces()
        }
    }

    fun dismissNameDialog() {
        _nameDialogRequest.value = null
    }

    // Overwrites the current banked entry (currentBankedId != null) or creates a new one.
    private fun saveToBank(name: String, thenClose: Boolean = false) {
        val state = _uiState.value as? GpxImportUiState.Loaded ?: return
        val points = _bivouacPoints.value
        val renamedTrack = state.track.copy(name = name)
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                bankRepository.save(_currentBankedId.value, name, renamedTrack, points, state.stats)
            }
            _currentBankedId.value = id
            _uiState.value = state.copy(track = renamedTrack)
            _dirty.value = false
            refreshBankedTraces()
            if (thenClose) performClose()
        }
    }

    // Always creates a new entry (new id) and switches the current session onto it.
    private fun duplicateToBank(name: String) {
        val state = _uiState.value as? GpxImportUiState.Loaded ?: return
        val points = _bivouacPoints.value
        val renamedTrack = state.track.copy(name = name)
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                bankRepository.save(null, name, renamedTrack, points, state.stats)
            }
            _currentBankedId.value = id
            _uiState.value = state.copy(track = renamedTrack)
            _dirty.value = false
            refreshBankedTraces()
        }
    }

    fun openFromBank(id: String) {
        _uiState.value = GpxImportUiState.Loading
        viewModelScope.launch {
            // RIC-127 : un GPX illisible en banque ne doit pas crasher l'app, voir openTrack
            // (JournalViewModel) pour le même filet côté Journal. Distinct de "introuvable en
            // banque" (opened == null sans exception, cas légitime -> Idle).
            val result = runCatching { withContext(Dispatchers.IO) { bankRepository.open(id) } }
            if (result.isFailure) {
                Log.e("GpxImportViewModel", "Échec de l'ouverture d'une trace de la banque", result.exceptionOrNull())
                // Reste sur la liste (Idle) plutôt que GpxImportUiState.Error : les autres
                // traces de la banque restent valides, pas de raison de les faire disparaître.
                _uiState.value = GpxImportUiState.Idle
                _bankOpenError.value = "Trace incorrecte ou fichier illisible."
                return@launch
            }
            val opened = result.getOrNull()
            if (opened == null) {
                _uiState.value = GpxImportUiState.Idle
                return@launch
            }
            val (track, points) = opened
            _uiState.value = GpxImportUiState.Loaded(track, TrackStatsCalculator.compute(track.points, activeCalibration.value))
            _bivouacPoints.value = points
            _currentBankedId.value = id
            _dirty.value = false
        }
    }

    fun requestDelete() {
        val id = _currentBankedId.value ?: return
        val name = (_uiState.value as? GpxImportUiState.Loaded)?.track?.name ?: "cette trace"
        _deleteTarget.value = DeleteTarget.Current(id, name)
    }

    fun requestDeleteFromList(id: String, name: String) {
        _deleteTarget.value = DeleteTarget.FromList(id, name)
    }

    fun confirmDelete() {
        val target = _deleteTarget.value ?: return
        _deleteTarget.value = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { bankRepository.delete(target.id) }
            refreshBankedTraces()
            if (target is DeleteTarget.Current) {
                performClose()
            }
        }
    }

    fun dismissDeleteConfirmation() {
        _deleteTarget.value = null
    }

    // Jamais banquée l'emporte sur simplement modifiée : dans ce cas c'est la trace entière qui
    // n'est pas enregistrée, pas seulement les changements depuis le dernier save.
    fun requestClose() {
        val reason = closeConfirmationReasonForCurrentTrack()
        _closeConfirmationReason.value = reason
        if (reason == null) {
            performClose()
        }
    }

    // Null quand fermer ne fait rien perdre — parce que rien n'est ouvert, ou parce que ce qui
    // l'est est déjà banqué et intact.
    private fun closeConfirmationReasonForCurrentTrack(): CloseConfirmationReason? = when {
        _uiState.value !is GpxImportUiState.Loaded -> null
        _currentBankedId.value == null -> CloseConfirmationReason.NEVER_SAVED
        _dirty.value -> CloseConfirmationReason.DIRTY
        else -> null
    }

    fun dismissCloseConfirmation() {
        _closeConfirmationReason.value = null
        // L'utilisateur a choisi de garder ce qui est ouvert plutôt que de le lâcher — une
        // duplication en attente (voir openDuplicateFromLoggedTrack) n'attendait que cette même
        // confirmation, elle doit donc être abandonnée aussi, pas rejouée en douce.
        pendingDuplicateLoad = null
    }

    fun discardAndClose() {
        _closeConfirmationReason.value = null
        performClose()
    }

    fun saveAndClose() {
        _closeConfirmationReason.value = null
        val state = _uiState.value as? GpxImportUiState.Loaded ?: run { performClose(); return }
        val name = state.track.name
        when {
            _currentBankedId.value != null -> saveToBank(name ?: "", thenClose = true)
            !name.isNullOrBlank() -> saveToBank(name, thenClose = true)
            else -> _nameDialogRequest.value = NameDialogRequest("", NameDialogPurpose.SAVE_THEN_CLOSE)
        }
    }

    private fun performClose() {
        _uiState.value = GpxImportUiState.Idle
        _bivouacPoints.value = emptyList()
        _currentBankedId.value = null
        _dirty.value = false
        viewModelScope.launch { withContext(Dispatchers.IO) { repository.clear() } }
        // Chaque appel à performClose() (abandon, enregistrer-puis-fermer, supprimer-puis-fermer)
        // est un moment valide pour appliquer une duplication qui n'attendait que la fermeture de
        // la trace en cours — voir openDuplicateFromLoggedTrack.
        pendingDuplicateLoad?.let { loadDuplicate(it) }
        pendingDuplicateLoad = null
    }

    // --- RIC-40 : dupliquer une trace du Journal vers la Planification ---

    private var pendingDuplicateLoad: DuplicatePlan? = null

    private data class DuplicatePlan(
        val track: HikeTrack,
        val bivouacPoints: List<BivouacPoint>,
        val suggestedName: String,
    )

    /**
     * Charge une trace dupliquée depuis le Journal (immuable là-bas — on ne fait que la lire) en
     * tant que nouveau plan éditable, avec un point de bivouac déjà posé à chaque jonction de
     * fichiers (voir JournalViewModel.buildDuplicateForPlanification). Le reste emprunte le flux
     * « Dupliquer » déjà en place — même dialogue de nom, même chemin
     * [NameDialogPurpose.DUPLICATE] vers une nouvelle entrée de la banque — plutôt que
     * d'inventer une seconde façon de faire atterrir un plan.
     *
     * Si une trace est déjà ouverte ici et que la fermer poserait question, ça ne l'écrase pas en
     * silence : la même confirmation qu'une fermeture manuelle s'affiche d'abord, et la
     * duplication ne se charge qu'une fois l'utilisateur décidé (voir performClose /
     * dismissCloseConfirmation).
     */
    fun openDuplicateFromLoggedTrack(track: HikeTrack, bivouacPoints: List<BivouacPoint>, suggestedName: String) {
        val plan = DuplicatePlan(track, bivouacPoints, suggestedName)
        val reason = closeConfirmationReasonForCurrentTrack()
        if (reason == null) {
            loadDuplicate(plan)
        } else {
            pendingDuplicateLoad = plan
            _closeConfirmationReason.value = reason
        }
    }

    private fun loadDuplicate(plan: DuplicatePlan) {
        _uiState.value = GpxImportUiState.Loaded(
            plan.track,
            TrackStatsCalculator.compute(plan.track.points, activeCalibration.value),
        )
        _bivouacPoints.value = plan.bivouacPoints
        _currentBankedId.value = null
        _dirty.value = false
        persistCurrentState()
        _nameDialogRequest.value = NameDialogRequest(plan.suggestedName, NameDialogPurpose.DUPLICATE)
    }

    // --- Import / restauration ---

    fun importGpx(resolver: ContentResolver, uri: Uri) {
        _uiState.value = GpxImportUiState.Loading
        _bivouacPoints.value = emptyList()
        _currentBankedId.value = null
        _dirty.value = false
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val track = resolver.openInputStream(uri)?.use { GpxParser.parse(it) }
                        ?: throw IOException("Impossible d'ouvrir le fichier sélectionné")
                    track to TrackStatsCalculator.compute(track.points, activeCalibration.value)
                }
            }
            _uiState.value = result.fold(
                onSuccess = { (track, stats) -> GpxImportUiState.Loaded(track, stats) },
                onFailure = {
                    Log.e("GpxImportViewModel", "Échec de l'import GPX", it)
                    GpxImportUiState.Error("Trace incorrecte ou fichier illisible.")
                },
            )
            persistCurrentState()
        }
    }

    // Restores the trace saved from the previous session, if any — called once on a fresh start
    // (not after an incoming-GPX import already handled it), so a restart doesn't lose the plan.
    // Not linked back to a bank entry even if it originally came from one: the auto-save singleton
    // doesn't track that link, so a restored session is treated as detached — a fresh save creates
    // a new bank entry rather than silently overwriting the one it may have started from.
    fun restoreLastTrack() {
        _uiState.value = GpxImportUiState.Loading
        viewModelScope.launch {
            // RIC-127 : exécuté à chaque démarrage à froid — sans ce filet, une session
            // auto-sauvegardée devenue illisible bloquerait le lancement de l'app. Distinct de
            // "rien à restaurer" (restored == null sans exception, cas normal -> Idle).
            val result = runCatching { withContext(Dispatchers.IO) { repository.loadLast() } }
            if (result.isFailure) {
                Log.e("GpxImportViewModel", "Échec de la restauration de la session précédente", result.exceptionOrNull())
                _uiState.value = GpxImportUiState.Error("Trace incorrecte ou fichier illisible.")
                return@launch
            }
            val restored = result.getOrNull()
            if (restored == null) {
                _uiState.value = GpxImportUiState.Idle
                return@launch
            }
            val (track, points) = restored
            _uiState.value = GpxImportUiState.Loaded(track, TrackStatsCalculator.compute(track.points, activeCalibration.value))
            _bivouacPoints.value = points
            _currentBankedId.value = null
            _dirty.value = false
        }
    }

    fun addBivouacPoint(trackPointIndex: Int) {
        val current = _bivouacPoints.value
        if (current.any { it.trackPointIndex == trackPointIndex }) return
        _bivouacPoints.value = (current + BivouacPoint(UUID.randomUUID().toString(), trackPointIndex))
            .sortedBy { it.trackPointIndex }
        _dirty.value = true
        persistCurrentState()
    }

    fun removeBivouacPoint(id: String) {
        _bivouacPoints.value = _bivouacPoints.value.filterNot { it.id == id }
        _dirty.value = true
        persistCurrentState()
    }

    fun moveBivouacPoint(id: String, newTrackPointIndex: Int) {
        _dragPreview.value = null
        _bivouacPoints.value = _bivouacPoints.value
            .map { if (it.id == id) it.copy(trackPointIndex = newTrackPointIndex) else it }
            .sortedBy { it.trackPointIndex }
        _dirty.value = true
        persistCurrentState()
    }

    // Fire-and-forget: called after every settled (non-transient) change to the loaded track or
    // its bivouac points. Never called from previewBivouacDrag, which fires on every drag frame —
    // only the drop (moveBivouacPoint) persists. This is the auto-save singleton, unrelated to the
    // banque de traces mechanism above.
    private fun persistCurrentState() {
        val track = (_uiState.value as? GpxImportUiState.Loaded)?.track ?: return
        val points = _bivouacPoints.value
        viewModelScope.launch { withContext(Dispatchers.IO) { repository.save(track, points) } }
    }

    fun previewBivouacDrag(id: String, trackPointIndex: Int) {
        _dragPreview.value = id to trackPointIndex
    }

    private fun applyDragPreview(points: List<BivouacPoint>, preview: Pair<String, Int>?): List<BivouacPoint> {
        if (preview == null) return points
        return points.map { if (it.id == preview.first) it.copy(trackPointIndex = preview.second) else it }
            .sortedBy { it.trackPointIndex }
    }

    private fun computeSegments(points: List<TrackPoint>, bivouacs: List<BivouacPoint>, calibration: SpeedCalibration): List<Segment> {
        val boundaries = listOf(0) + bivouacs.map { it.trackPointIndex } + listOf(points.lastIndex)
        return boundaries.zipWithNext { start, end ->
            val segmentPoints = points.subList(start, end + 1)
            Segment(segmentPoints, TrackStatsCalculator.compute(segmentPoints, calibration))
        }
    }
}

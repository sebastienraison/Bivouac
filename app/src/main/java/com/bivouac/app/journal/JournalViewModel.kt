package com.bivouac.app.journal

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bivouac.app.data.db.DuplicateMatch
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.db.PreparedImport
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.prefs.MapLayerPreferences
import com.bivouac.app.ui.map.MapLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface JournalUiState {
    data object Overview : JournalUiState
    data object Loading : JournalUiState
    data class Detail(val entry: LoggedTrackEntity, val track: HikeTrack) : JournalUiState
    // BIV-48: a contemplative overview of several traces at once — entries in the order they
    // should get their (rotating) legend color, each paired with its parsed track.
    data class MultiTrack(val entries: List<Pair<LoggedTrackEntity, HikeTrack>>) : JournalUiState
    data class Error(val message: String) : JournalUiState
}

class JournalViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LoggedTrackRepository(application)
    private val mapLayerPreferences = MapLayerPreferences(application)

    private val _tracks = MutableStateFlow<List<LoggedTrackEntity>>(emptyList())
    val tracks: StateFlow<List<LoggedTrackEntity>> = _tracks.asStateFlow()

    // trackId -> its tags, for every track that has at least one — drives both the filter chips
    // (distinct values across all tracks) and which entries a filter selection keeps.
    private val _tagsByTrackId = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val tagsByTrackId: StateFlow<Map<String, List<String>>> = _tagsByTrackId.asStateFlow()

    private val _selectedFilterTags = MutableStateFlow<Set<String>>(emptySet())
    val selectedFilterTags: StateFlow<Set<String>> = _selectedFilterTags.asStateFlow()

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
    // per-screen setting.
    val selectedLayer: StateFlow<MapLayer> = mapLayerPreferences.selectedLayer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapLayer.HIKING)

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val _probableDuplicate = MutableStateFlow<LoggedTrackEntity?>(null)
    val probableDuplicate: StateFlow<LoggedTrackEntity?> = _probableDuplicate.asStateFlow()
    private var pendingImport: PreparedImport? = null

    private val _deleteTarget = MutableStateFlow<LoggedTrackEntity?>(null)
    val deleteTarget: StateFlow<LoggedTrackEntity?> = _deleteTarget.asStateFlow()

    // BIV-47: entered via long-press on a list row; while active, tapping a row toggles it
    // instead of opening it. Cleared automatically once the multi-trace map view is shown.
    private val _selectionModeActive = MutableStateFlow(false)
    val selectionModeActive: StateFlow<Boolean> = _selectionModeActive.asStateFlow()

    private val _selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedTrackIds: StateFlow<Set<String>> = _selectedTrackIds.asStateFlow()

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _tracks.value = repository.list()
                _tagsByTrackId.value = repository.tagsByTrackId()
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
                withContext(Dispatchers.IO) { repository.open(entry.id) }
            }.onSuccess { track ->
                _uiState.value = if (track != null) {
                    _currentTags.value = _tagsByTrackId.value[entry.id].orEmpty()
                    JournalUiState.Detail(entry, track)
                } else {
                    JournalUiState.Error("Trace introuvable.")
                }
            }.onFailure {
                Log.e("JournalViewModel", "Échec de l'ouverture d'une trace du journal", it)
                _uiState.value = JournalUiState.Error("Trace incorrecte ou fichier illisible.")
            }
        }
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
        _selectedTrackIds.value = emptySet()
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
                _uiState.value = JournalUiState.Detail(state.entry.copy(name = trimmed), state.track)
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
            _uiState.value = JournalUiState.Detail(state.entry.copy(note = note), state.track)
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

    fun importTrack(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val prepared = repository.prepareImport(resolver, uri)
                    prepared to repository.findDuplicate(prepared)
                }
            }.onSuccess { (prepared, duplicate) ->
                when (duplicate) {
                    is DuplicateMatch.Exact ->
                        _importError.value = "« ${duplicate.existing.name} » est déjà dans le journal."
                    is DuplicateMatch.Probable -> {
                        pendingImport = prepared
                        _probableDuplicate.value = duplicate.existing
                    }
                    null -> commit(prepared)
                }
            }.onFailure {
                Log.e("JournalViewModel", "Échec de l'import GPX (Journal)", it)
                _importError.value = "Trace incorrecte ou fichier illisible."
            }
        }
    }

    fun confirmImportAnyway() {
        val prepared = pendingImport ?: return
        dismissDuplicateWarning()
        viewModelScope.launch { commit(prepared) }
    }

    fun dismissDuplicateWarning() {
        pendingImport = null
        _probableDuplicate.value = null
    }

    fun dismissImportError() {
        _importError.value = null
    }

    private suspend fun commit(prepared: PreparedImport) {
        withContext(Dispatchers.IO) { repository.commitImport(prepared) }
        refresh()
        // Mirrors Planification's "open a track" behavior: a just-imported trace should land
        // straight on its detail view, not merely appear in the list waiting to be tapped.
        openTrack(prepared.entity)
    }
}

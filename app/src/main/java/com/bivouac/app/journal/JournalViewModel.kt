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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface JournalUiState {
    data object Overview : JournalUiState
    data object Loading : JournalUiState
    data class Detail(val entry: LoggedTrackEntity, val track: HikeTrack) : JournalUiState
    data class Error(val message: String) : JournalUiState
}

class JournalViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LoggedTrackRepository(application)
    private val mapLayerPreferences = MapLayerPreferences(application)

    private val _tracks = MutableStateFlow<List<LoggedTrackEntity>>(emptyList())
    val tracks: StateFlow<List<LoggedTrackEntity>> = _tracks.asStateFlow()

    private val _uiState = MutableStateFlow<JournalUiState>(JournalUiState.Overview)
    val uiState: StateFlow<JournalUiState> = _uiState.asStateFlow()

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

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            _tracks.value = withContext(Dispatchers.IO) { repository.list() }
        }
    }

    fun setSelectedLayer(layer: MapLayer) {
        viewModelScope.launch { mapLayerPreferences.setSelectedLayer(layer) }
    }

    fun openTrack(entry: LoggedTrackEntity) {
        _uiState.value = JournalUiState.Loading
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.open(entry.id) }
            }.onSuccess { track ->
                _uiState.value = if (track != null) {
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

    fun requestDelete() {
        _deleteTarget.value = (_uiState.value as? JournalUiState.Detail)?.entry
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
    }
}

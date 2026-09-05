package com.bivouac.app.bilan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.prefs.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RIC-19 : écran Bilan, prolongement du Journal : pas de filtre/sélection ici, contrairement au
 * Journal (RIC-65 écran 3), le Bilan reste toujours sur l'intégralité de la banque.
 *
 * Recalculé au complet à chaque [refresh] plutôt que via des Flow combinés comme JournalViewModel :
 * BilanStatsCalculator.compute() est un calcul en mémoire pur sur des colonnes déjà dénormalisées
 * (aucun reparsing de GPX), donc bon marché même sur une banque fournie, plus simple à lire qu'un
 * graphe de `combine` pour un écran qui n'a, par construction, qu'un seul état à recalculer.
 */
class BilanViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LoggedTrackRepository(application)
    private val settingsPreferences = SettingsPreferences(application)

    private val _stats = MutableStateFlow<BilanStats?>(null)
    val stats: StateFlow<BilanStats?> = _stats.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val calibration = settingsPreferences.effectiveCalibration.first()
            val computed = withContext(Dispatchers.IO) {
                val tracks = repository.list()
                val daysByTrackId = repository.allDaysByTrackId()
                BilanStatsCalculator.compute(tracks, daysByTrackId, calibration)
            }
            _stats.value = computed
            _loaded.value = true
        }
    }
}

package com.bivouac.app.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.prefs.SettingsPreferences
import com.bivouac.app.data.storage.AppStorageUsage
import com.bivouac.app.data.storage.AppStorageUsageCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RIC-140 : l'écran « Espace utilisé ».
 *
 * Le relevé est fait à l'ouverture, une fois, et jamais observé : voir
 * [AppStorageUsageCalculator]. [usage] reste donc nul pendant le calcul, ce que l'écran traduit par
 * un état de chargement plutôt que par des zéros, qui seraient faux et non « pas encore connus ».
 */
class StorageUsageViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LoggedTrackRepository(application)
    private val settingsPreferences = SettingsPreferences(application)

    private val _usage = MutableStateFlow<AppStorageUsage?>(null)
    val usage: StateFlow<AppStorageUsage?> = _usage.asStateFlow()

    /**
     * RIC-152 : les photos débrayées, rien de ce qui touche à la galerie n'est proposé, et surtout
     * pas la recompression, qui déclencherait une demande de permission là où le réglage promet
     * qu'aucune ne sera jamais demandée. Le relevé d'espace, lui, reste affiché : savoir ce que les
     * photos conservées occupent est justement ce qui donne son sens à la bascule.
     */
    val photosEnabled: StateFlow<Boolean> = settingsPreferences.photosEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        refresh()
    }

    /** Relance le relevé : à l'ouverture, et après toute opération qui a changé ce qu'il mesure. */
    fun refresh() {
        viewModelScope.launch {
            val photos = withContext(Dispatchers.IO) { repository.allPhotos() }
            _usage.value = AppStorageUsageCalculator.compute(getApplication(), photos)
        }
    }
}

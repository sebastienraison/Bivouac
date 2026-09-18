package com.bivouac.app.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.operations.ExclusiveOperation
import com.bivouac.app.data.operations.ExclusiveOperations
import com.bivouac.app.data.operations.exclusiveOperationRefusalMessage
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

    /**
     * RIC-157 : non nul tant que la recompression tourne. Alimente le dialogue bloquant partagé
     * (voir BlockingProgressDialog), qui se charge seul de l'anti-flash.
     */
    private val _recompressionProgress = MutableStateFlow<RecompressionProgress?>(null)
    val recompressionProgress: StateFlow<RecompressionProgress?> = _recompressionProgress.asStateFlow()

    /** Le rapport de fin, tenu jusqu'à ce que l'utilisateur le referme : jamais de fin silencieuse. */
    private val _recompressionReport = MutableStateFlow<LoggedTrackRepository.PhotoRecompressionReport?>(null)
    val recompressionReport: StateFlow<LoggedTrackRepository.PhotoRecompressionReport?> =
        _recompressionReport.asStateFlow()

    private val _recompressionError = MutableStateFlow<String?>(null)
    val recompressionError: StateFlow<String?> = _recompressionError.asStateFlow()

    /**
     * L'opération longue en vol pour tout le process : une sauvegarde lancée depuis les Réglages
     * ou un import de photos lancé depuis le Journal grisent le bouton d'ici, et réciproquement.
     */
    val ongoingOperation: StateFlow<ExclusiveOperation?> = ExclusiveOperations.current

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

    /**
     * RIC-157 : la recompression du stock existant.
     *
     * Même discipline que la sauvegarde et la purge (RIC-156/158) : le verrou est posé par le CLIC
     * lui-même et non dans la coroutine, sans quoi il ne fermerait la fenêtre qu'au moment où
     * l'ordonnanceur veut bien s'en occuper ; et il est levé dans un `finally`, pour qu'une
     * destruction de l'écran ne le laisse pas posé pour toujours.
     *
     * L'appelant a déjà vérifié la permission galerie : voir StorageUsageScreen, qui déclenche le
     * flux de permission existant plutôt que de lancer une passe qui ne trouverait aucun original.
     */
    fun recompressPhotos() {
        if (!ExclusiveOperations.tryStart(ExclusiveOperation.PHOTO_RECOMPRESS)) {
            _recompressionError.value = refusalMessage()
            return
        }
        _recompressionProgress.value = RecompressionProgress(done = 0, total = null)
        viewModelScope.launch {
            val report = try {
                withContext(Dispatchers.IO) {
                    repository.recompressFullPhotos { done, total ->
                        _recompressionProgress.value = RecompressionProgress(done, total)
                    }
                }
            } finally {
                _recompressionProgress.value = null
                ExclusiveOperations.finish(ExclusiveOperation.PHOTO_RECOMPRESS)
            }
            // Après la levée du dialogue bloquant, jamais avant : posé pendant, le rapport
            // s'ouvrirait derrière lui (même écueil que backup()/restore(), RIC-156).
            _recompressionReport.value = report
            refresh()
        }
    }

    fun dismissRecompressionReport() {
        _recompressionReport.value = null
    }

    fun dismissRecompressionError() {
        _recompressionError.value = null
    }

    // Censé inatteignable, le bouton étant grisé dès qu'une opération tourne : reste écrit pour la
    // même raison défensive que dans SettingsViewModel.
    private fun refusalMessage(): String = exclusiveOperationRefusalMessage(getApplication())
}

/**
 * RIC-157 : où en est la recompression. [total] est nul le temps que la liste des candidates soit
 * établie : le dialogue montre alors le tourniquet seul, plutôt qu'un dénominateur inventé.
 */
data class RecompressionProgress(val done: Int, val total: Int?)

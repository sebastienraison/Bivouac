package com.bivouac.app.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bivouac.app.data.backup.AppRestart
import com.bivouac.app.data.backup.BackupManager
import com.bivouac.app.data.backup.RestorePhase
import com.bivouac.app.data.backup.RestoreResult
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.db.PhotoStorageSummary
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.SpeedCalibrationCalculator
import com.bivouac.app.data.operations.ExclusiveOperation
import com.bivouac.app.data.operations.ExclusiveOperations
import com.bivouac.app.data.prefs.SettingsPreferences
import com.bivouac.app.data.prefs.SpeedCalibrationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RIC-156 : les trois temps que le dialogue bloquant des Réglages sait annoncer.
 *
 * La restauration en a deux, et non un seul : l'extraction est la phase longue et dénombrable, le
 * remplacement est court et ne l'est pas — les fondre donnerait un compteur qui se fige à la fin
 * sans que rien n'explique pourquoi.
 */
enum class DataOperationPhase(val title: String) {
    BACKUP("Sauvegarde en cours"),
    RESTORE_EXTRACTION("Lecture de la sauvegarde"),
    RESTORE_REPLACEMENT("Restauration en cours"),

    // RIC-158 : la purge des photos peut porter sur des centaines de Mo — assez long pour mériter
    // le même dialogue bloquant que la sauvegarde et la restauration, cohérence oblige.
    PHOTO_PURGE("Purge des photos en cours"),
}

/** RIC-156 : où en est la sauvegarde ou la restauration. [total] est null quand le travail n'est pas dénombrable. */
data class DataOperationProgress(val phase: DataOperationPhase, val done: Int, val total: Int?)

/** Result of a completed restore, held until the user acknowledges the "app is about to restart" dialog. */
sealed interface RestoreOutcome {
    data object PendingRestart : RestoreOutcome
    data class VersionTooNew(val backupVersion: Int, val appVersion: Int) : RestoreOutcome
    data class Error(val message: String) : RestoreOutcome
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsPreferences = SettingsPreferences(application)
    private val loggedTrackRepository = LoggedTrackRepository(application)

    val mode: StateFlow<SpeedCalibrationMode> = settingsPreferences.speedCalibrationMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeedCalibrationMode.MANUAL)

    val manualCalibration: StateFlow<SpeedCalibration> = settingsPreferences.manualCalibration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeedCalibration.DEFAULT)

    val autoCalibration: StateFlow<SpeedCalibration> = settingsPreferences.autoCalibration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeedCalibration.DEFAULT)

    val selectionCalibration: StateFlow<SpeedCalibration> = settingsPreferences.selectionCalibration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeedCalibration.DEFAULT)

    val selectedTrackCount: StateFlow<Int> = settingsPreferences.selectedTrackIds
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val nonFreeFeaturesDisabled: StateFlow<Boolean> = settingsPreferences.nonFreeFeaturesDisabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // RIC-152 : activée par défaut, y compris comme valeur initiale du StateFlow — un faux
    // "désactivé" le temps de la première lecture du DataStore ferait clignoter tout le bandeau
    // Photos à chaque ouverture des Réglages.
    val photosEnabled: StateFlow<Boolean> = settingsPreferences.photosEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // Bousculé après une purge, pour que le relevé ci-dessous soit refait. Le reste du temps c'est
    // la bascule qui le déclenche.
    private val _photoStorageRefresh = MutableStateFlow(0)

    /**
     * RIC-152 : ce que les photos occupent réellement, relevé seulement quand la fonctionnalité est
     * désactivée — c'est le seul cas où le bouton de purge existe, et il n'y a aucune raison de
     * compter des fichiers pour ne rien en faire.
     *
     * Recalculé à chaque bascule et non une fois à l'ouverture de l'écran : désactiver puis voir
     * apparaître le bouton dans la foulée est exactement ce qu'on attend, et l'obliger à ressortir
     * des Réglages pour le voir serait incompréhensible.
     */
    val photoStorage: StateFlow<PhotoStorageSummary?> =
        combine(settingsPreferences.photosEnabled, _photoStorageRefresh) { enabled, _ -> enabled }
            .map { enabled ->
                if (enabled) null else withContext(Dispatchers.IO) { loggedTrackRepository.photoStorageSummary() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Non nul pendant que le dialogue de confirmation est ouvert — il porte le relevé montré au
    // moment du clic, pour que le dialogue chiffre exactement ce que le bouton annonçait.
    private val _photoPurgeConfirmation = MutableStateFlow<PhotoStorageSummary?>(null)
    val photoPurgeConfirmation: StateFlow<PhotoStorageSummary?> = _photoPurgeConfirmation.asStateFlow()

    // RIC-158 : réservé au refus — inatteignable en pratique puisque le bouton de purge est grisé
    // dès qu'une autre opération tourne (voir ongoingOperation), même politique défensive que
    // backupError pour un chemin oublié.
    private val _photoPurgeError = MutableStateFlow<String?>(null)
    val photoPurgeError: StateFlow<String?> = _photoPurgeError.asStateFlow()

    val lastBackupAtMillis: StateFlow<Long?> = settingsPreferences.lastBackupAtMillis
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * RIC-156 : non nul tant qu'une sauvegarde ou une restauration est en vol. Alimente le dialogue
     * bloquant de l'écran (voir BlockingProgressDialog), qui se charge seul de l'anti-flash.
     */
    private val _dataOperationProgress = MutableStateFlow<DataOperationProgress?>(null)
    val dataOperationProgress: StateFlow<DataOperationProgress?> = _dataOperationProgress.asStateFlow()

    /**
     * RIC-156 : l'opération longue en vol pour tout le process, photos du Journal comprises.
     *
     * Exposée telle quelle et non recopiée dans un état local : c'est ce qui garantit que les
     * boutons Sauvegarder/Restaurer sont grisés pendant un import de photos lancé depuis un autre
     * écran, cas que ce ViewModel ne peut pas connaître autrement.
     */
    val ongoingOperation: StateFlow<ExclusiveOperation?> = ExclusiveOperations.current

    private val _backupError = MutableStateFlow<String?>(null)
    val backupError: StateFlow<String?> = _backupError.asStateFlow()

    private val _restoreOutcome = MutableStateFlow<RestoreOutcome?>(null)
    val restoreOutcome: StateFlow<RestoreOutcome?> = _restoreOutcome.asStateFlow()

    // Gates Auto/Sélection in the segmented control: below MIN_JOURNAL_TRACKS_FOR_CALIBRATION,
    // neither mode has enough data to ever compute anything but the default — see
    // SpeedCalibrationCalculator's kdoc on why one data point can't separate speed from D+ penalty.
    // Snapshotted once per Settings-screen open (same refresh cadence as refreshAutoCalibration
    // below), not observed live — consistent with the rest of this screen, and with why deleting
    // a track elsewhere doesn't retroactively grey anything out until Réglages is reopened.
    private val _journalTrackCount = MutableStateFlow(0)
    val journalTrackCount: StateFlow<Int> = _journalTrackCount.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _journalTrackCount.value = loggedTrackRepository.list().size
                // Populates the Auto readout even for a Journal that already had hikes before
                // BIV-16 shipped (JournalViewModel otherwise only refreshes this on a *new* import).
                refreshAutoCalibration()
            }
        }
    }

    private suspend fun refreshAutoCalibration() {
        val input = loggedTrackRepository.calibrationSamples()
        val result = SpeedCalibrationCalculator.compute(input.aggregate, input.fallbackSamples) ?: return
        settingsPreferences.setAutoCalibration(result.calibration)
    }

    fun setMode(mode: SpeedCalibrationMode) {
        viewModelScope.launch { settingsPreferences.setSpeedCalibrationMode(mode) }
    }

    fun setManualSpeed(walkingSpeedKmh: Double) {
        viewModelScope.launch {
            settingsPreferences.setManualCalibration(
                walkingSpeedKmh,
                manualCalibration.value.elevationGainPenaltyMetersPerKm,
                manualCalibration.value.pauseFractionPercent,
            )
        }
    }

    fun setManualPenalty(elevationGainPenaltyMetersPerKm: Double) {
        viewModelScope.launch {
            settingsPreferences.setManualCalibration(
                manualCalibration.value.walkingSpeedKmh,
                elevationGainPenaltyMetersPerKm,
                manualCalibration.value.pauseFractionPercent,
            )
        }
    }

    // RIC-115 : curseur "Pauses pendant la marche", actif en mode Manuel seulement (grisé en
    // Auto/Sélection, voir SettingsScreen) — même politique que setManualSpeed/setManualPenalty.
    fun setManualPause(pauseFractionPercent: Double) {
        viewModelScope.launch {
            settingsPreferences.setManualCalibration(
                manualCalibration.value.walkingSpeedKmh,
                manualCalibration.value.elevationGainPenaltyMetersPerKm,
                pauseFractionPercent,
            )
        }
    }

    fun setNonFreeFeaturesDisabled(disabled: Boolean) {
        viewModelScope.launch { settingsPreferences.setNonFreeFeaturesDisabled(disabled) }
    }

    fun setPhotosEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsPreferences.setPhotosEnabled(enabled) }
    }

    /**
     * RIC-152 : la purge est demandée, pas encore faite — le dialogue de confirmation s'ouvre.
     *
     * Rien n'est jamais purgé automatiquement : désactiver la fonctionnalité continue de tout
     * conserver, et ce bouton est le seul chemin vers la suppression des photos en masse.
     */
    fun requestPhotoPurge() {
        _photoPurgeConfirmation.value = photoStorage.value ?: return
    }

    fun dismissPhotoPurge() {
        _photoPurgeConfirmation.value = null
    }

    /**
     * RIC-158 : entre au registre d'exclusion comme la sauvegarde et la restauration — la purge
     * supprime en masse des fichiers de photos/, exactement ce que la sauvegarde zippe et ce que la
     * restauration remplace en bloc. Même discipline que backup()/restore() : verrou pris avant le
     * launch, par le clic lui-même, et levé dans un finally.
     */
    fun confirmPhotoPurge() {
        val storage = _photoPurgeConfirmation.value
        _photoPurgeConfirmation.value = null
        if (!ExclusiveOperations.tryStart(ExclusiveOperation.PHOTO_PURGE)) {
            _photoPurgeError.value = refusalMessage()
            return
        }
        _dataOperationProgress.value =
            DataOperationProgress(DataOperationPhase.PHOTO_PURGE, done = 0, total = storage?.count)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    loggedTrackRepository.purgeAllPhotos { done, total ->
                        _dataOperationProgress.value = DataOperationProgress(DataOperationPhase.PHOTO_PURGE, done, total)
                    }
                }
            } finally {
                _dataOperationProgress.value = null
                ExclusiveOperations.finish(ExclusiveOperation.PHOTO_PURGE)
            }
            _photoStorageRefresh.value += 1
        }
    }

    fun dismissPhotoPurgeError() {
        _photoPurgeError.value = null
    }

    /**
     * RIC-156 : le verrou est pris AVANT le launch, comme pour les opérations photo (RIC-149) — un
     * verrou posé dans la coroutine dépendrait du moment où elle est ordonnancée, et laisserait
     * exactement la fenêtre qu'il est censé fermer. Même raison pour la progression initiale : le
     * dialogue doit exister du fait du clic, pas d'un aller-retour d'ordonnanceur.
     */
    fun backup(uri: Uri) {
        if (!ExclusiveOperations.tryStart(ExclusiveOperation.BACKUP)) {
            _backupError.value = refusalMessage()
            return
        }
        _dataOperationProgress.value = DataOperationProgress(DataOperationPhase.BACKUP, done = 0, total = null)
        viewModelScope.launch {
            val result = try {
                // BackupManager stamps lastBackupAtMillis itself, before zipping — lastBackupAtMillis
                // above picks it up reactively once the write lands, no need to set it again here.
                BackupManager.backup(getApplication(), uri) { done, total ->
                    _dataOperationProgress.value = DataOperationProgress(DataOperationPhase.BACKUP, done, total)
                }
            } finally {
                // Dans un finally, et pas à la suite du corps : une annulation du viewModelScope
                // (écran détruit) libère le verrou au lieu de le laisser posé pour toujours.
                _dataOperationProgress.value = null
                ExclusiveOperations.finish(ExclusiveOperation.BACKUP)
            }
            // Après la levée du dialogue bloquant, jamais avant : posé pendant, le message
            // d'erreur s'ouvrirait derrière lui. Il reste une fenêtre résiduelle, le temps que la
            // durée minimale d'affichage s'écoule, mais le dialogue posé en dernier passe devant.
            result.onFailure {
                _backupError.value = it.message ?: "Échec de la sauvegarde."
            }
        }
    }

    fun restore(uri: Uri) {
        if (!ExclusiveOperations.tryStart(ExclusiveOperation.RESTORE)) {
            _restoreOutcome.value = RestoreOutcome.Error(refusalMessage())
            return
        }
        _dataOperationProgress.value =
            DataOperationProgress(DataOperationPhase.RESTORE_EXTRACTION, done = 0, total = null)
        viewModelScope.launch {
            val result = try {
                BackupManager.restore(getApplication(), uri) { progress ->
                    val phase = when (progress.phase) {
                        RestorePhase.EXTRACTION -> DataOperationPhase.RESTORE_EXTRACTION
                        RestorePhase.REPLACEMENT -> DataOperationPhase.RESTORE_REPLACEMENT
                    }
                    _dataOperationProgress.value = DataOperationProgress(phase, progress.done, progress.total)
                }
            } finally {
                _dataOperationProgress.value = null
                ExclusiveOperations.finish(ExclusiveOperation.RESTORE)
            }
            // Même raison que pour la sauvegarde : le bilan ne doit pas être posé pendant que le
            // dialogue bloquant est encore en place, sous peine de s'ouvrir derrière lui.
            _restoreOutcome.value = when (result) {
                is RestoreResult.Success -> RestoreOutcome.PendingRestart
                is RestoreResult.VersionTooNew -> RestoreOutcome.VersionTooNew(result.backupVersion, result.appVersion)
                is RestoreResult.Error -> RestoreOutcome.Error(result.message)
            }
        }
    }

    /**
     * RIC-156 : le refus est censé être inatteignable — les boutons sont grisés dès qu'une
     * opération tourne. Il reste écrit, et nommé, parce qu'un chemin oublié doit refuser proprement
     * plutôt que de laisser deux écritures se croiser sur les mêmes fichiers.
     */
    private fun refusalMessage(): String {
        val ongoing = ExclusiveOperations.current.value?.label ?: "une autre opération"
        return "Impossible pour l'instant : $ongoing est en cours. Attends qu'elle se termine, puis recommence."
    }

    fun dismissRestoreOutcome() {
        _restoreOutcome.value = null
    }

    fun dismissBackupError() {
        _backupError.value = null
    }

    // Only reached from the PendingRestart dialog's confirm button — see AppRestart's kdoc for
    // why nothing short of a full process restart can safely pick up a just-restored database.
    fun confirmRestartAfterRestore() {
        AppRestart.restart(getApplication())
    }
}

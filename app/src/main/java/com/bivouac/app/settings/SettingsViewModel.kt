package com.bivouac.app.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bivouac.app.R
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
import com.bivouac.app.data.operations.exclusiveOperationRefusalMessage
import com.bivouac.app.data.photo.PhotoRecompression
import com.bivouac.app.data.photo.PhotoStorageMode
import com.bivouac.app.data.photo.PhotoStoragePolicy
import com.bivouac.app.data.prefs.SettingsPreferences
import com.bivouac.app.data.prefs.SpeedCalibrationMode
import com.bivouac.app.data.storage.AppStorageUsageCalculator
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

    // RIC-152 : activée par défaut, y compris comme valeur initiale du StateFlow : un faux
    // "désactivé" le temps de la première lecture du DataStore ferait clignoter tout le bandeau
    // Photos à chaque ouverture des Réglages.
    val photosEnabled: StateFlow<Boolean> = settingsPreferences.photosEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // RIC-157 : le nombre de photos du Journal, relevé une fois à l'ouverture de l'écran et
    // rafraîchi après une purge : il ne sert qu'à départager le mode de stockage EFFECTIF quand
    // l'utilisateur n'a rien tranché, ce qui n'a besoin d'être juste qu'à la seconde près quand il
    // regarde ce réglage. Voir PhotoStoragePolicy.resolve.
    private val _journalPhotoCount = MutableStateFlow(0)

    /**
     * RIC-157 : ce que la section « Photos du Journal » doit afficher comme sélection.
     *
     * Le mode EFFECTIF, jamais la décision brute : tant que l'utilisateur n'a pas tranché, les
     * Réglages doivent montrer ce qui s'appliquera réellement à son prochain import, pas un
     * troisième état « rien de coché » qui ne dirait rien de ce que l'app fait.
     */
    val photoStorageMode: StateFlow<PhotoStorageMode> =
        combine(settingsPreferences.photoStorageModeDecision, _journalPhotoCount) { decision, count ->
            PhotoStoragePolicy.resolve(decision, hasExistingPhotos = count > 0)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhotoStorageMode.REDUCED)

    // Bousculé après une purge, pour que le relevé ci-dessous soit refait. Le reste du temps c'est
    // la bascule qui le déclenche.
    private val _photoStorageRefresh = MutableStateFlow(0)

    /**
     * RIC-152 : ce que les photos occupent réellement, relevé seulement quand la fonctionnalité est
     * désactivée : c'est le seul cas où le bouton de purge existe, et il n'y a aucune raison de
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

    /**
     * RIC-157 : la proposition posée juste après avoir basculé vers la copie réduite alors qu'il
     * reste des photos en pleine résolution (voir [PhotoRecompression.shouldOfferRecompressionAfterModeChange]).
     * Non nulle tant que le dialogue doit rester à l'écran ; porte l'estimation déjà calculée, pour
     * que le dialogue chiffre exactement ce qu'il annonce sans la recalculer.
     */
    private val _photoRecompressionOffer = MutableStateFlow<PhotoRecompression.Estimate?>(null)
    val photoRecompressionOffer: StateFlow<PhotoRecompression.Estimate?> = _photoRecompressionOffer.asStateFlow()

    /**
     * RIC-157 : le rapport de fin de la recompression lancée DEPUIS la proposition ci-dessus.
     *
     * Même type et même mise en forme (voir `recompressionReportMessage`, StorageUsageScreen) que
     * la recompression lancée depuis l'écran « Espace utilisé » : c'est la même opération, jamais
     * de fin silencieuse quel que soit l'endroit d'où elle a démarré.
     */
    private val _photoRecompressionReport = MutableStateFlow<LoggedTrackRepository.PhotoRecompressionReport?>(null)
    val photoRecompressionReport: StateFlow<LoggedTrackRepository.PhotoRecompressionReport?> =
        _photoRecompressionReport.asStateFlow()

    private val _photoRecompressionError = MutableStateFlow<String?>(null)
    val photoRecompressionError: StateFlow<String?> = _photoRecompressionError.asStateFlow()

    // Non nul pendant que le dialogue de confirmation est ouvert : il porte le relevé montré au
    // moment du clic, pour que le dialogue chiffre exactement ce que le bouton annonçait.
    private val _photoPurgeConfirmation = MutableStateFlow<PhotoStorageSummary?>(null)
    val photoPurgeConfirmation: StateFlow<PhotoStorageSummary?> = _photoPurgeConfirmation.asStateFlow()

    // RIC-158 : réservé au refus, inatteignable en pratique puisque le bouton de purge est grisé
    // dès qu'une autre opération tourne (voir ongoingOperation), même politique défensive que
    // backupError pour un chemin oublié.
    private val _photoPurgeError = MutableStateFlow<String?>(null)
    val photoPurgeError: StateFlow<String?> = _photoPurgeError.asStateFlow()

    /**
     * RIC-151 : le nombre de photos dont le fichier local a disparu, relevé à l'ouverture de
     * l'écran et après chaque recherche.
     *
     * C'est lui, et lui seul, qui fait exister « Retrouver les photos manquantes » : proposer en
     * permanence une action qui ne pourrait que répondre « zéro » ajouterait du bruit dans les
     * Réglages pour un cas qui, chez la plupart des gens, ne se produira jamais. Un `stat` par
     * photo à l'ouverture, jamais de détection en tâche de fond : arbitrage du pilotage.
     */
    private val _missingPhotoCount = MutableStateFlow(0)
    val missingPhotoCount: StateFlow<Int> = _missingPhotoCount.asStateFlow()

    private val _photoRecoveryReport = MutableStateFlow<LoggedTrackRepository.PhotoRecoveryReport?>(null)
    val photoRecoveryReport: StateFlow<LoggedTrackRepository.PhotoRecoveryReport?> = _photoRecoveryReport.asStateFlow()

    private val _photoRecoveryError = MutableStateFlow<String?>(null)
    val photoRecoveryError: StateFlow<String?> = _photoRecoveryError.asStateFlow()

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
    // neither mode has enough data to ever compute anything but the default: see
    // SpeedCalibrationCalculator's kdoc on why one data point can't separate speed from D+ penalty.
    // Snapshotted once per Settings-screen open (same refresh cadence as refreshAutoCalibration
    // below), not observed live: consistent with the rest of this screen, and with why deleting
    // a track elsewhere doesn't retroactively grey anything out until Réglages is reopened.
    private val _journalTrackCount = MutableStateFlow(0)
    val journalTrackCount: StateFlow<Int> = _journalTrackCount.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _journalTrackCount.value = loggedTrackRepository.list().size
                _journalPhotoCount.value = loggedTrackRepository.countAllPhotos()
                _missingPhotoCount.value = loggedTrackRepository.countMissingPhotoFiles()
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
    // Auto/Sélection, voir SettingsScreen), même politique que setManualSpeed/setManualPenalty.
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
     * RIC-157 : le choix explicite du mode de stockage.
     *
     * Ne touche à aucune photo déjà importée, ni à sa ligne : chacune garde le mode sous lequel
     * elle est entrée (voir LoggedTrackPhotoEntity.storageMode). Ce réglage ne dit que ce qui
     * arrivera aux suivantes.
     */
    fun setPhotoStorageMode(mode: PhotoStorageMode) {
        viewModelScope.launch { settingsPreferences.setPhotoStorageMode(mode) }
    }

    /**
     * RIC-157 : le point d'entrée réel du sélecteur des Réglages (voir SettingsScreen), qui
     * enchaîne [setPhotoStorageMode] et la proposition de recompresser le stock existant.
     *
     * Le mode PRÉCÉDENT est celui affiché à l'instant du clic ([photoStorageMode], déjà le mode
     * EFFECTIF résolu par PhotoStoragePolicy) : c'est lui qui distingue une vraie bascule d'un
     * appui sur le bouton déjà sélectionné. Le relevé qui suit (nombre de photos FULL, estimation)
     * réutilise tel quel [AppStorageUsageCalculator.compute], le même calcul que l'écran « Espace
     * utilisé » : aucune deuxième formule pour un chiffre qui doit rester identique aux deux
     * endroits où il peut apparaître.
     */
    fun choosePhotoStorageMode(mode: PhotoStorageMode) {
        val previousMode = photoStorageMode.value
        setPhotoStorageMode(mode)
        viewModelScope.launch {
            val usage = withContext(Dispatchers.IO) {
                AppStorageUsageCalculator.compute(getApplication(), loggedTrackRepository.allPhotos())
            }
            if (
                PhotoRecompression.shouldOfferRecompressionAfterModeChange(
                    previousMode = previousMode,
                    newMode = mode,
                    fullPhotoCount = usage.photos.fullCount,
                    estimate = usage.recompression,
                )
            ) {
                _photoRecompressionOffer.value = usage.recompression
            }
        }
    }

    fun dismissPhotoRecompressionOffer() {
        _photoRecompressionOffer.value = null
    }

    /**
     * RIC-157 : la recompression lancée depuis la proposition ci-dessus, exactement l'action du
     * bouton dédié de l'écran « Espace utilisé » (StorageUsageViewModel.recompressPhotos) : même
     * verrou ExclusiveOperation.PHOTO_RECOMPRESS posé par le clic, même appel à
     * LoggedTrackRepository.recompressFullPhotos, même rapport de fin. Reprise ici plutôt
     * qu'appelée à distance : les deux écrans ont chacun leur propre ViewModel et leur propre
     * dialogue bloquant (voir DataOperationProgress), comme la purge et la recherche des photos
     * manquantes le font déjà pour ce même écran.
     *
     * L'appelant a déjà vérifié la permission galerie, comme pour recoverMissingPhotos ci-dessus.
     */
    fun recompressPhotosFromOffer() {
        _photoRecompressionOffer.value = null
        if (!ExclusiveOperations.tryStart(ExclusiveOperation.PHOTO_RECOMPRESS)) {
            _photoRecompressionError.value = refusalMessage()
            return
        }
        _dataOperationProgress.value =
            DataOperationProgress(DataOperationPhase.PHOTO_RECOMPRESS, done = 0, total = null)
        viewModelScope.launch {
            val report = try {
                withContext(Dispatchers.IO) {
                    loggedTrackRepository.recompressFullPhotos { done, total ->
                        _dataOperationProgress.value =
                            DataOperationProgress(DataOperationPhase.PHOTO_RECOMPRESS, done, total)
                    }
                }
            } finally {
                _dataOperationProgress.value = null
                ExclusiveOperations.finish(ExclusiveOperation.PHOTO_RECOMPRESS)
            }
            _photoRecompressionReport.value = report
        }
    }

    fun dismissPhotoRecompressionReport() {
        _photoRecompressionReport.value = null
    }

    fun dismissPhotoRecompressionError() {
        _photoRecompressionError.value = null
    }

    /**
     * RIC-152 : la purge est demandée, pas encore faite : le dialogue de confirmation s'ouvre.
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
     * RIC-158 : entre au registre d'exclusion comme la sauvegarde et la restauration : la purge
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
            // RIC-157 : le Journal n'a plus de photos du tout : sans ce relevé, le mode effectif
            // affiché resterait sur le défaut « il y a déjà des photos » (FULL) alors qu'il n'y en a
            // plus une seule.
            _journalPhotoCount.value = withContext(Dispatchers.IO) { loggedTrackRepository.countAllPhotos() }
        }
    }

    fun dismissPhotoPurgeError() {
        _photoPurgeError.value = null
    }

    /**
     * RIC-151 : la recherche des photos manquantes, déclenchée à la main et seulement à la main.
     *
     * Même discipline que la purge et la sauvegarde : verrou du registre d'exclusion posé par le
     * clic (elle écrit dans photos/ et réécrit des lignes), progression publiée dans la foulée,
     * verrou levé dans un `finally`, rapport de fin publié APRÈS la levée du dialogue bloquant.
     *
     * Le mode de stockage passé est le mode EFFECTIF d'aujourd'hui (voir [photoStorageMode]) : une
     * photo reprise maintenant l'est sous le régime courant, pas sous celui qu'elle portait quand
     * son fichier existait encore.
     *
     * La permission galerie est vérifiée par l'écran, qui déclenche le flux existant : sans elle la
     * passe ne trouverait rien et compterait tout comme introuvable.
     */
    fun recoverMissingPhotos() {
        if (!ExclusiveOperations.tryStart(ExclusiveOperation.PHOTO_RECOVERY)) {
            _photoRecoveryError.value = refusalMessage()
            return
        }
        _dataOperationProgress.value =
            DataOperationProgress(DataOperationPhase.PHOTO_RECOVERY, done = 0, total = _missingPhotoCount.value)
        val storageMode = photoStorageMode.value
        viewModelScope.launch {
            val report = try {
                withContext(Dispatchers.IO) {
                    loggedTrackRepository.recoverMissingPhotos(storageMode) { done, total ->
                        _dataOperationProgress.value =
                            DataOperationProgress(DataOperationPhase.PHOTO_RECOVERY, done, total)
                    }
                }
            } finally {
                _dataOperationProgress.value = null
                ExclusiveOperations.finish(ExclusiveOperation.PHOTO_RECOVERY)
            }
            _photoRecoveryReport.value = report
            _missingPhotoCount.value = withContext(Dispatchers.IO) { loggedTrackRepository.countMissingPhotoFiles() }
        }
    }

    fun dismissPhotoRecoveryReport() {
        _photoRecoveryReport.value = null
    }

    fun dismissPhotoRecoveryError() {
        _photoRecoveryError.value = null
    }

    /**
     * RIC-156 : le verrou est pris AVANT le launch, comme pour les opérations photo (RIC-149) : un
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
                // BackupManager stamps lastBackupAtMillis itself, before zipping: lastBackupAtMillis
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
                // RIC-191 : le message de l'exception vient de BackupManager, qui le compose
                // désormais lui-même à partir des ressources ; ce repli ne sert qu'aux exceptions
                // muettes (OOM, IO sans message).
                _backupError.value = it.message
                    ?: getApplication<Application>().getString(R.string.backup_generic_failure_message)
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
     * RIC-156 : le refus est censé être inatteignable : les boutons sont grisés dès qu'une
     * opération tourne. Il reste écrit, et nommé, parce qu'un chemin oublié doit refuser proprement
     * plutôt que de laisser deux écritures se croiser sur les mêmes fichiers.
     */
    private fun refusalMessage(): String = exclusiveOperationRefusalMessage(getApplication())

    fun dismissRestoreOutcome() {
        _restoreOutcome.value = null
    }

    fun dismissBackupError() {
        _backupError.value = null
    }

    // Only reached from the PendingRestart dialog's confirm button: see AppRestart's kdoc for
    // why nothing short of a full process restart can safely pick up a just-restored database.
    fun confirmRestartAfterRestore() {
        AppRestart.restart(getApplication())
    }
}

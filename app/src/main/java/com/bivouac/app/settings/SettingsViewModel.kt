package com.bivouac.app.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bivouac.app.data.backup.AppRestart
import com.bivouac.app.data.backup.BackupManager
import com.bivouac.app.data.backup.RestoreResult
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.SpeedCalibrationCalculator
import com.bivouac.app.data.prefs.SettingsPreferences
import com.bivouac.app.data.prefs.SpeedCalibrationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    val lastBackupAtMillis: StateFlow<Long?> = settingsPreferences.lastBackupAtMillis
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _backupInProgress = MutableStateFlow(false)
    val backupInProgress: StateFlow<Boolean> = _backupInProgress.asStateFlow()

    private val _restoreInProgress = MutableStateFlow(false)
    val restoreInProgress: StateFlow<Boolean> = _restoreInProgress.asStateFlow()

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

    fun backup(uri: Uri) {
        viewModelScope.launch {
            _backupInProgress.value = true
            // BackupManager stamps lastBackupAtMillis itself, before zipping — lastBackupAtMillis
            // above picks it up reactively once the write lands, no need to set it again here.
            val result = BackupManager.backup(getApplication(), uri)
            _backupInProgress.value = false
            result.onFailure {
                _backupError.value = it.message ?: "Échec de la sauvegarde."
            }
        }
    }

    fun restore(uri: Uri) {
        viewModelScope.launch {
            _restoreInProgress.value = true
            val result = BackupManager.restore(getApplication(), uri)
            _restoreInProgress.value = false
            _restoreOutcome.value = when (result) {
                is RestoreResult.Success -> RestoreOutcome.PendingRestart
                is RestoreResult.VersionTooNew -> RestoreOutcome.VersionTooNew(result.backupVersion, result.appVersion)
                is RestoreResult.Error -> RestoreOutcome.Error(result.message)
            }
        }
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

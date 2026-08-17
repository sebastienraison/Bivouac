package com.bivouac.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bivouac.app.data.gpx.SpeedCalibration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

// Backup filename (BIV-66) — the whole point of this file being the single DataStore for the
// Réglages screen is that a backup only has to embed this one preferences file alongside
// map_layer_prefs, not enumerate every DataStore the app happens to have.
internal const val SETTINGS_DATASTORE_NAME = "bivouac_settings"
private val Context.settingsDataStore by preferencesDataStore(name = SETTINGS_DATASTORE_NAME)

enum class SpeedCalibrationMode { MANUAL, AUTO, SELECTION }

/**
 * Everything under the Réglages screen (BIV-16): the three Vitesse personnalisée modes and their
 * values, the non-free-features toggle, and the last-backup timestamp shown in Données.
 */
class SettingsPreferences(private val context: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("speed_calibration_mode")
        val MANUAL_SPEED = doublePreferencesKey("manual_walking_speed_kmh")
        val MANUAL_PENALTY = doublePreferencesKey("manual_elevation_gain_penalty")
        val AUTO_SPEED = doublePreferencesKey("auto_walking_speed_kmh")
        val AUTO_PENALTY = doublePreferencesKey("auto_elevation_gain_penalty")
        val SELECTION_SPEED = doublePreferencesKey("selection_walking_speed_kmh")
        val SELECTION_PENALTY = doublePreferencesKey("selection_elevation_gain_penalty")
        val SELECTED_TRACK_IDS = stringPreferencesKey("selection_track_ids")
        val NON_FREE_DISABLED = booleanPreferencesKey("non_free_features_disabled")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at_millis")
    }

    val speedCalibrationMode: Flow<SpeedCalibrationMode> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.MODE]?.let { name -> runCatching { SpeedCalibrationMode.valueOf(name) }.getOrNull() }
            ?: SpeedCalibrationMode.MANUAL
    }

    val manualCalibration: Flow<SpeedCalibration> = context.settingsDataStore.data.map { prefs ->
        SpeedCalibration(
            walkingSpeedKmh = prefs[Keys.MANUAL_SPEED] ?: SpeedCalibration.DEFAULT.walkingSpeedKmh,
            elevationGainPenaltyMetersPerKm = prefs[Keys.MANUAL_PENALTY]
                ?: SpeedCalibration.DEFAULT.elevationGainPenaltyMetersPerKm,
        )
    }

    val autoCalibration: Flow<SpeedCalibration> = context.settingsDataStore.data.map { prefs ->
        SpeedCalibration(
            walkingSpeedKmh = prefs[Keys.AUTO_SPEED] ?: SpeedCalibration.DEFAULT.walkingSpeedKmh,
            elevationGainPenaltyMetersPerKm = prefs[Keys.AUTO_PENALTY]
                ?: SpeedCalibration.DEFAULT.elevationGainPenaltyMetersPerKm,
        )
    }

    val selectionCalibration: Flow<SpeedCalibration> = context.settingsDataStore.data.map { prefs ->
        SpeedCalibration(
            walkingSpeedKmh = prefs[Keys.SELECTION_SPEED] ?: SpeedCalibration.DEFAULT.walkingSpeedKmh,
            elevationGainPenaltyMetersPerKm = prefs[Keys.SELECTION_PENALTY]
                ?: SpeedCalibration.DEFAULT.elevationGainPenaltyMetersPerKm,
        )
    }

    val selectedTrackIds: Flow<Set<String>> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.SELECTED_TRACK_IDS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    // Whichever mode is active, resolved to the single calibration that should drive
    // TrackStatsCalculator everywhere a duration estimate is computed.
    val effectiveCalibration: Flow<SpeedCalibration> =
        combine(speedCalibrationMode, manualCalibration, autoCalibration, selectionCalibration) { mode, manual, auto, selection ->
            when (mode) {
                SpeedCalibrationMode.MANUAL -> manual
                SpeedCalibrationMode.AUTO -> auto
                SpeedCalibrationMode.SELECTION -> selection
            }
        }

    // BIV-13 (F-Droid review): Satellite (Esri) and the Meteoblue link both flagged NonFreeNet.
    val nonFreeFeaturesDisabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.NON_FREE_DISABLED] ?: false }

    val lastBackupAtMillis: Flow<Long?> = context.settingsDataStore.data.map { it[Keys.LAST_BACKUP_AT] }

    suspend fun setSpeedCalibrationMode(mode: SpeedCalibrationMode) {
        context.settingsDataStore.edit { it[Keys.MODE] = mode.name }
    }

    suspend fun setManualCalibration(walkingSpeedKmh: Double, elevationGainPenaltyMetersPerKm: Double) {
        context.settingsDataStore.edit {
            it[Keys.MANUAL_SPEED] = walkingSpeedKmh
            it[Keys.MANUAL_PENALTY] = elevationGainPenaltyMetersPerKm
        }
    }

    suspend fun setAutoCalibration(calibration: SpeedCalibration) {
        context.settingsDataStore.edit {
            it[Keys.AUTO_SPEED] = calibration.walkingSpeedKmh
            it[Keys.AUTO_PENALTY] = calibration.elevationGainPenaltyMetersPerKm
        }
    }

    // Selection ids are persisted together with their resulting calibration — they're only ever
    // written back together (see JournalViewModel.confirmCalibrationSelection), so there's no
    // window where one could be stale relative to the other.
    suspend fun setSelectionCalibration(calibration: SpeedCalibration, trackIds: Set<String>) {
        context.settingsDataStore.edit {
            it[Keys.SELECTION_SPEED] = calibration.walkingSpeedKmh
            it[Keys.SELECTION_PENALTY] = calibration.elevationGainPenaltyMetersPerKm
            it[Keys.SELECTED_TRACK_IDS] = trackIds.joinToString(",")
        }
    }

    suspend fun setNonFreeFeaturesDisabled(disabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.NON_FREE_DISABLED] = disabled }
    }

    suspend fun setLastBackupAtMillis(millis: Long) {
        context.settingsDataStore.edit { it[Keys.LAST_BACKUP_AT] = millis }
    }
}

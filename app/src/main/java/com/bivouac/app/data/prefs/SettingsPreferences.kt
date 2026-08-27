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
        val MANUAL_PAUSE = doublePreferencesKey("manual_pause_fraction_percent")
        val AUTO_SPEED = doublePreferencesKey("auto_walking_speed_kmh")
        val AUTO_PENALTY = doublePreferencesKey("auto_elevation_gain_penalty")
        val AUTO_PAUSE = doublePreferencesKey("auto_pause_fraction_percent")
        val SELECTION_SPEED = doublePreferencesKey("selection_walking_speed_kmh")
        val SELECTION_PENALTY = doublePreferencesKey("selection_elevation_gain_penalty")
        val SELECTION_PAUSE = doublePreferencesKey("selection_pause_fraction_percent")
        val SELECTED_TRACK_IDS = stringPreferencesKey("selection_track_ids")
        val NON_FREE_DISABLED = booleanPreferencesKey("non_free_features_disabled")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at_millis")
        val PHOTO_DATE_RANGE_SEARCH = booleanPreferencesKey("photo_date_range_search_enabled")
    }

    // RIC-115 : contrairement à AUTO_PAUSE/SELECTION_PAUSE (repli sur SpeedCalibration.DEFAULT.
    // pauseFractionPercent = 0.0, valeur neutre "jamais réglé, aucune provision"), la toute
    // première fois que le mode Manuel est actif sans que l'utilisateur ait touché le curseur, la
    // provision doit valoir 15 % — légèrement au-dessus de la médiane réelle mesurée (12,9 %),
    // choix délibérément prudent tranché par le pilotage (mieux vaut surestimer un peu la pause
    // par défaut que sous-estimer une durée de rando avec bivouac à la clé). Rien n'est écrit
    // proactivement : ce repli suffit à produire l'effet voulu dès la première lecture, exactement
    // comme MANUAL_SPEED/MANUAL_PENALTY se replient déjà sur SpeedCalibration.DEFAULT sans jamais
    // écrire cette valeur par défaut en avance de phase.
    private val MANUAL_PAUSE_INITIAL_PERCENT = 15.0

    val speedCalibrationMode: Flow<SpeedCalibrationMode> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.MODE]?.let { name -> runCatching { SpeedCalibrationMode.valueOf(name) }.getOrNull() }
            ?: SpeedCalibrationMode.MANUAL
    }

    val manualCalibration: Flow<SpeedCalibration> = context.settingsDataStore.data.map { prefs ->
        SpeedCalibration(
            walkingSpeedKmh = prefs[Keys.MANUAL_SPEED] ?: SpeedCalibration.DEFAULT.walkingSpeedKmh,
            elevationGainPenaltyMetersPerKm = prefs[Keys.MANUAL_PENALTY]
                ?: SpeedCalibration.DEFAULT.elevationGainPenaltyMetersPerKm,
            pauseFractionPercent = prefs[Keys.MANUAL_PAUSE] ?: MANUAL_PAUSE_INITIAL_PERCENT,
        )
    }

    val autoCalibration: Flow<SpeedCalibration> = context.settingsDataStore.data.map { prefs ->
        SpeedCalibration(
            walkingSpeedKmh = prefs[Keys.AUTO_SPEED] ?: SpeedCalibration.DEFAULT.walkingSpeedKmh,
            elevationGainPenaltyMetersPerKm = prefs[Keys.AUTO_PENALTY]
                ?: SpeedCalibration.DEFAULT.elevationGainPenaltyMetersPerKm,
            pauseFractionPercent = prefs[Keys.AUTO_PAUSE] ?: SpeedCalibration.DEFAULT.pauseFractionPercent,
        )
    }

    val selectionCalibration: Flow<SpeedCalibration> = context.settingsDataStore.data.map { prefs ->
        SpeedCalibration(
            walkingSpeedKmh = prefs[Keys.SELECTION_SPEED] ?: SpeedCalibration.DEFAULT.walkingSpeedKmh,
            elevationGainPenaltyMetersPerKm = prefs[Keys.SELECTION_PENALTY]
                ?: SpeedCalibration.DEFAULT.elevationGainPenaltyMetersPerKm,
            pauseFractionPercent = prefs[Keys.SELECTION_PAUSE] ?: SpeedCalibration.DEFAULT.pauseFractionPercent,
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

    // RIC-43 : désactivée par défaut, même convention que nonFreeFeaturesDisabled ci-dessus —
    // activer cette bascule ne demande rien tant que l'utilisateur ne tape pas « Ajouter des
    // photos » sur une trace, voir JournalViewModel.
    val photoDateRangeSearchEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.PHOTO_DATE_RANGE_SEARCH] ?: false }

    suspend fun setSpeedCalibrationMode(mode: SpeedCalibrationMode) {
        context.settingsDataStore.edit { it[Keys.MODE] = mode.name }
    }

    // RIC-115 : pauseFractionPercent est un paramètre à part entière, pas un défaut — un défaut
    // écraserait silencieusement la provision de pause persistée à chaque appel de
    // setManualSpeed/setManualPenalty (SettingsViewModel), qui n'ont eux-mêmes que speed/penalty à
    // faire varier. Les trois appelants (setManualSpeed, setManualPenalty, setManualPause) passent
    // donc chacun les trois valeurs, en ne faisant varier que celle qui les concerne — même
    // discipline que manualCalibration.value déjà utilisé pour recomposer les deux champs existants.
    suspend fun setManualCalibration(walkingSpeedKmh: Double, elevationGainPenaltyMetersPerKm: Double, pauseFractionPercent: Double) {
        context.settingsDataStore.edit {
            it[Keys.MANUAL_SPEED] = walkingSpeedKmh
            it[Keys.MANUAL_PENALTY] = elevationGainPenaltyMetersPerKm
            it[Keys.MANUAL_PAUSE] = pauseFractionPercent
        }
    }

    suspend fun setAutoCalibration(calibration: SpeedCalibration) {
        context.settingsDataStore.edit {
            it[Keys.AUTO_SPEED] = calibration.walkingSpeedKmh
            it[Keys.AUTO_PENALTY] = calibration.elevationGainPenaltyMetersPerKm
            it[Keys.AUTO_PAUSE] = calibration.pauseFractionPercent
        }
    }

    // Selection ids are persisted together with their resulting calibration — they're only ever
    // written back together (see JournalViewModel.confirmCalibrationSelection), so there's no
    // window where one could be stale relative to the other.
    suspend fun setSelectionCalibration(calibration: SpeedCalibration, trackIds: Set<String>) {
        context.settingsDataStore.edit {
            it[Keys.SELECTION_SPEED] = calibration.walkingSpeedKmh
            it[Keys.SELECTION_PENALTY] = calibration.elevationGainPenaltyMetersPerKm
            it[Keys.SELECTION_PAUSE] = calibration.pauseFractionPercent
            it[Keys.SELECTED_TRACK_IDS] = trackIds.joinToString(",")
        }
    }

    suspend fun setNonFreeFeaturesDisabled(disabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.NON_FREE_DISABLED] = disabled }
    }

    suspend fun setLastBackupAtMillis(millis: Long) {
        context.settingsDataStore.edit { it[Keys.LAST_BACKUP_AT] = millis }
    }

    suspend fun setPhotoDateRangeSearchEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.PHOTO_DATE_RANGE_SEARCH] = enabled }
    }
}

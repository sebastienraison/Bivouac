package com.bivouac.app.data.db

import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.SpeedCalibrationCalculator
import com.bivouac.app.data.gpx.TrackStatsParameters
import com.bivouac.app.data.prefs.SettingsPreferences
import kotlinx.coroutines.flow.first

/**
 * BIV-16/RIC-109 : recalcul de la calibration Auto et Sélection à partir du Journal, factorisé
 * ici plutôt que dupliqué une troisième fois (conception RIC-114 §5.5) : avant ce ticket,
 * [JournalViewModel][com.bivouac.app.journal.JournalViewModel].refreshAutoCalibration/
 * confirmCalibrationSelection et
 * [SettingsViewModel][com.bivouac.app.settings.SettingsViewModel].refreshAutoCalibration portaient
 * chacun leur propre copie du même calcul ; la phase 3 du rattrapage ci-dessous ([refreshIfNeeded])
 * en aurait sinon ajouté une troisième.
 */
object CalibrationRefresh {

    suspend fun refreshAuto(repository: LoggedTrackRepository, settingsPreferences: SettingsPreferences) {
        val input = repository.calibrationSamples()
        val result = SpeedCalibrationCalculator.compute(input.aggregate, input.fallbackSamples) ?: return
        settingsPreferences.setAutoCalibration(result.calibration)
    }

    suspend fun refreshSelection(
        repository: LoggedTrackRepository,
        settingsPreferences: SettingsPreferences,
        ids: Set<String>,
    ) {
        if (ids.isEmpty()) return
        val input = repository.calibrationSamples(ids)
        val result = SpeedCalibrationCalculator.compute(input.aggregate, input.fallbackSamples)
        settingsPreferences.setSelectionCalibration(result?.calibration ?: SpeedCalibration.DEFAULT, ids)
    }

    /**
     * RIC-114 lot 2, phase 3 (conception §5.3/§5.5) : recalcule Auto puis Sélection (Manuel n'est
     * jamais touché) et pose `calibration_stats_version` en dernier, à condition que la préférence
     * ne soit pas déjà à jour OU qu'au moins une trace du Journal vienne d'être rattrapée en
     * phase 1 ([staleJournalStatsBackfilled]) : sans cette seconde condition, une Sélection
     * confirmée après le dernier changement d'algorithme resterait calculée sur l'ancien D+ pour
     * toujours. Sans travail à faire (cas courant), ne lit qu'une préférence : c'est ce qui permet
     * de l'appeler sans popup même sur une installation neuve.
     */
    suspend fun refreshIfNeeded(
        repository: LoggedTrackRepository,
        settingsPreferences: SettingsPreferences,
        staleJournalStatsBackfilled: Boolean,
    ) {
        val currentVersion = settingsPreferences.calibrationStatsVersion.first()
        if (currentVersion >= TrackStatsParameters.ALGORITHM_VERSION && !staleJournalStatsBackfilled) return
        refreshAuto(repository, settingsPreferences)
        val selectedIds = settingsPreferences.selectedTrackIds.first()
        if (selectedIds.isNotEmpty()) refreshSelection(repository, settingsPreferences, selectedIds)
        settingsPreferences.setCalibrationStatsVersion(TrackStatsParameters.ALGORITHM_VERSION)
    }
}

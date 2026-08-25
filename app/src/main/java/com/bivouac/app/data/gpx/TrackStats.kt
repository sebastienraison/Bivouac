package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import kotlin.math.roundToInt

data class TrackStats(
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val estimatedDurationMinutes: Int,
)

/**
 * Les trois leviers derrière l'estimation de durée (BIV-16 "Vitesse personnalisée") — vitesse à
 * plat, correction D+ façon Naismith, et provision de pause (RIC-115) — tous réglables à la main en
 * mode Manuel ou calculés depuis l'historique du Journal en mode Auto/Sélection (voir
 * [com.bivouac.app.data.gpx.SpeedCalibrationCalculator]).
 */
data class SpeedCalibration(
    val walkingSpeedKmh: Double,
    val elevationGainPenaltyMetersPerKm: Double,
    // RIC-115 : part du temps passée à l'arrêt (0-100), convertie en majoration du temps de marche
    // via P / (1 - P) — voir TrackStatsCalculator.applyPauseProvision. 0.0 par défaut : valeur
    // neutre obligatoire, pour qu'un utilisateur qui n'a jamais touché ce réglage voie un
    // comportement strictement inchangé par rapport à avant ce ticket.
    val pauseFractionPercent: Double = 0.0,
) {
    companion object {
        val DEFAULT = SpeedCalibration(
            walkingSpeedKmh = 3.5,
            elevationGainPenaltyMetersPerKm = 100.0,
            pauseFractionPercent = 0.0,
        )
    }
}

object TrackStatsCalculator {

    // Raw GPS/barometric elevation is noisy; smoothing it before summing deltas avoids
    // wildly overestimating D+/D- from point-to-point jitter.
    private const val ELEVATION_SMOOTHING_WINDOW = 5

    // RIC-115 : défense en profondeur — l'IHM borne le curseur à 35 %, mais une valeur DataStore
    // corrompue ou une migration future ne doivent jamais pouvoir amener le dénominateur de
    // applyPauseProvision à <= 0.
    private const val MAX_SAFE_PAUSE_FRACTION_PERCENT = 90.0

    fun compute(points: List<TrackPoint>, calibration: SpeedCalibration = SpeedCalibration.DEFAULT): TrackStats {
        val distance = points.zipWithNext { a, b ->
            GeoMath.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        }.sum()
        val smoothedElevations = smoothValues(points.mapNotNull { it.elevationMeters })

        var gain = 0.0
        var loss = 0.0
        smoothedElevations.zipWithNext { a, b ->
            val delta = b - a
            if (delta > 0) gain += delta else loss += -delta
        }

        val durationMinutes = applyPauseProvision(walkingMinutes(distance, gain, calibration), calibration.pauseFractionPercent).roundToInt()

        return TrackStats(
            distanceMeters = distance,
            elevationGainMeters = gain,
            elevationLossMeters = loss,
            estimatedDurationMinutes = durationMinutes,
        )
    }

    /**
     * Re-derives just the duration of an already-computed [TrackStats] under a different
     * [calibration] — distance/elevation are physical facts of the track and don't change, but
     * [TrackStats.estimatedDurationMinutes] does whenever the active calibration does. Used to
     * keep list rows (banked traces, Journal) showing a duration consistent with the current
     * Réglages calibration without re-parsing every trace's GPX just to redraw a list.
     */
    fun recomputeDuration(stats: TrackStats, calibration: SpeedCalibration): TrackStats {
        val durationMinutes = applyPauseProvision(
            walkingMinutes(stats.distanceMeters, stats.elevationGainMeters, calibration),
            calibration.pauseFractionPercent,
        ).roundToInt()
        return stats.copy(estimatedDurationMinutes = durationMinutes)
    }

    /**
     * Minutes de marche pure (hors provision de pause) pour une distance et un D+ donnés, sous
     * [calibration]. Exposé (pas seulement interne à [compute]/[recomputeDuration]) pour les
     * aperçus IHM qui n'ont pas de [TrackPoint] réels — Réglages, aperçu illustratif de l'effet du
     * D+ sur une rando type (RIC-115).
     */
    fun walkingMinutes(distanceMeters: Double, elevationGainMeters: Double, calibration: SpeedCalibration): Double {
        val equivalentDistanceKm = distanceMeters / 1000.0 + elevationGainMeters / calibration.elevationGainPenaltyMetersPerKm
        return equivalentDistanceKm / calibration.walkingSpeedKmh * 60
    }

    /**
     * RIC-115 : convertit des minutes de marche pure en minutes totales (marche + pause), à partir
     * d'un pourcentage "temps passé à l'arrêt" (voir [SpeedCalibration.pauseFractionPercent]). La
     * conversion P -> majoration est P / (1 - P) : si P % du temps total est passé à l'arrêt, le
     * temps de marche restant (1 - P) doit être multiplié par 1 / (1 - P) pour reconstituer le
     * temps total. Coercée à [MAX_SAFE_PAUSE_FRACTION_PERCENT] avant division, même si l'IHM borne
     * déjà le curseur à 35 % — défense en profondeur contre une valeur DataStore corrompue.
     */
    fun applyPauseProvision(walkingMinutes: Double, pauseFractionPercent: Double): Double {
        val fraction = pauseFractionPercent.coerceIn(0.0, MAX_SAFE_PAUSE_FRACTION_PERCENT) / 100.0
        return walkingMinutes / (1.0 - fraction)
    }

    /**
     * Smoothed elevation for each point, index-aligned with [points] (for charting, where a
     * marker needs to land on the exact index a [com.bivouac.app.data.model.BivouacPoint]
     * refers to). Returns null if any point lacks elevation, rather than silently compacting the
     * series and breaking that index alignment.
     */
    fun smoothedElevationSeries(points: List<TrackPoint>): List<Double>? {
        val elevations = points.map { it.elevationMeters ?: return null }
        return smoothValues(elevations)
    }

    private fun smoothValues(values: List<Double>): List<Double> {
        if (values.size < ELEVATION_SMOOTHING_WINDOW) return values
        val halfWindow = ELEVATION_SMOOTHING_WINDOW / 2
        return values.indices.map { i ->
            val start = (i - halfWindow).coerceAtLeast(0)
            val end = (i + halfWindow).coerceAtMost(values.lastIndex)
            values.subList(start, end + 1).average()
        }
    }
}

package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import kotlin.math.roundToInt

data class TrackStats(
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val pointCount: Int,
    val estimatedDurationMinutes: Int,
)

/**
 * The two knobs behind the duration estimate (BIV-16 "Vitesse personnalisée") — flat walking pace
 * and the Naismith-style D+ correction, both user-editable in Manuel mode or computed from Journal
 * history in Auto/Sélection mode (see [com.bivouac.app.data.gpx.SpeedCalibrationCalculator]).
 */
data class SpeedCalibration(
    val walkingSpeedKmh: Double,
    val elevationGainPenaltyMetersPerKm: Double,
    // Reserved for a future D- correction (docs/CONCEPTION.md §9, known limitation) — not yet
    // factored into [TrackStatsCalculator.compute] or surfaced in the Réglages UI.
    val elevationLossPenaltyMetersPerKm: Double? = null,
) {
    companion object {
        val DEFAULT = SpeedCalibration(
            walkingSpeedKmh = 3.5,
            elevationGainPenaltyMetersPerKm = 100.0,
        )
    }
}

object TrackStatsCalculator {

    // Raw GPS/barometric elevation is noisy; smoothing it before summing deltas avoids
    // wildly overestimating D+/D- from point-to-point jitter.
    private const val ELEVATION_SMOOTHING_WINDOW = 5

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

        val equivalentDistanceKm = distance / 1000.0 + gain / calibration.elevationGainPenaltyMetersPerKm
        val durationMinutes = (equivalentDistanceKm / calibration.walkingSpeedKmh * 60).roundToInt()

        return TrackStats(
            distanceMeters = distance,
            elevationGainMeters = gain,
            elevationLossMeters = loss,
            pointCount = points.size,
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
        val equivalentDistanceKm = stats.distanceMeters / 1000.0 + stats.elevationGainMeters / calibration.elevationGainPenaltyMetersPerKm
        val durationMinutes = (equivalentDistanceKm / calibration.walkingSpeedKmh * 60).roundToInt()
        return stats.copy(estimatedDurationMinutes = durationMinutes)
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

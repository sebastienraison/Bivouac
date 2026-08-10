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

object TrackStatsCalculator {

    // Raw GPS/barometric elevation is noisy; smoothing it before summing deltas avoids
    // wildly overestimating D+/D- from point-to-point jitter.
    private const val ELEVATION_SMOOTHING_WINDOW = 5

    // Flat walking pace used for the duration estimate.
    private const val WALKING_SPEED_KMH = 3.5

    // Simplified Naismith-style correction: every 100m of elevation gain counts as an extra km
    // of flat-equivalent distance when estimating duration. Doesn't account for descent or
    // terrain difficulty.
    private const val ELEVATION_GAIN_METERS_PER_KM_EQUIVALENT = 100.0

    fun compute(points: List<TrackPoint>): TrackStats {
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

        val equivalentDistanceKm = distance / 1000.0 + gain / ELEVATION_GAIN_METERS_PER_KM_EQUIVALENT
        val durationMinutes = (equivalentDistanceKm / WALKING_SPEED_KMH * 60).roundToInt()

        return TrackStats(
            distanceMeters = distance,
            elevationGainMeters = gain,
            elevationLossMeters = loss,
            pointCount = points.size,
            estimatedDurationMinutes = durationMinutes,
        )
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

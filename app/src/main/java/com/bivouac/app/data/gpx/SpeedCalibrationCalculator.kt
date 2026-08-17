package com.bivouac.app.data.gpx

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Derives a [SpeedCalibration] from real hikes instead of asking the user to type numbers in —
 * the "Auto"/"Sélection" modes of BIV-16's Vitesse personnalisée.
 *
 * [TrackStats.estimatedDurationMinutes] can't be used as ground truth here (it's itself an output
 * of a [SpeedCalibration], so refitting from it would just reproduce whatever calibration produced
 * it). The real signal is each hike's *actual* elapsed time, taken from its own GPX timestamps
 * (see [Sample.elapsedHours] — callers derive it per Journal day to avoid counting overnight gaps
 * on multi-day hikes; a hike without usable timestamps simply can't contribute a sample).
 */
object SpeedCalibrationCalculator {

    data class Sample(val distanceMeters: Double, val elevationGainMeters: Double, val elapsedHours: Double)

    private const val MIN_SPEED_KMH = 1.0
    private const val MAX_SPEED_KMH = 8.0
    private const val MIN_PENALTY_M_PER_KM = 20.0
    private const val MAX_PENALTY_M_PER_KM = 300.0

    // Above this |correlation| between distance and gain across samples, the two-parameter fit
    // below is too ill-conditioned to trust (a 2x2 system on near-collinear inputs amplifies noise
    // wildly) — falls back to fitting speed alone against the default D+ penalty instead.
    private const val COLLINEARITY_THRESHOLD = 0.999

    /**
     * The duration model is linear in two unknowns: elapsedHours = a * distanceKm + b * gainMeters,
     * where a = 1 / speed and b = a / penalty (so `speed = 1/a` and `penalty = a/b`). That makes
     * this an ordinary bivariate linear regression, solved directly via the 2x2 normal-equations
     * system (closed form, no iteration) rather than an approximate/iterative scheme. Every fitted
     * value is clamped to a plausible hiking range, so noisy input degrades towards
     * [SpeedCalibration.DEFAULT]-like numbers rather than producing something absurd.
     *
     * Returns null when there's nothing usable to fit from (no sample with positive elapsed time).
     */
    fun compute(samples: List<Sample>): SpeedCalibration? {
        val valid = samples.filter { it.elapsedHours > 0.0 && it.distanceMeters >= 0.0 && it.elevationGainMeters >= 0.0 }
        if (valid.isEmpty()) return null

        val x1 = valid.map { it.distanceMeters / 1000.0 } // km
        val x2 = valid.map { it.elevationGainMeters } // m
        val y = valid.map { it.elapsedHours }

        val sxx1 = x1.sumOf { it * it }
        val sx1x2 = x1.indices.sumOf { x1[it] * x2[it] }
        val sxx2 = x2.sumOf { it * it }

        val correlationDenominator = sqrt(sxx1 * sxx2)
        val collinear = correlationDenominator <= 0.0 || abs(sx1x2 / correlationDenominator) > COLLINEARITY_THRESHOLD

        val (a, b) = if (!collinear) {
            val sx1y = x1.indices.sumOf { x1[it] * y[it] }
            val sx2y = x2.indices.sumOf { x2[it] * y[it] }
            val determinant = sxx1 * sxx2 - sx1x2 * sx1x2
            val fittedA = (sx1y * sxx2 - sx2y * sx1x2) / determinant
            val fittedB = (sxx1 * sx2y - sx1x2 * sx1y) / determinant
            fittedA to fittedB
        } else {
            // Not enough independent variation between distance and gain to separate the two
            // effects (e.g. a single hike, or several near-identical ones) — fit speed alone
            // against the default penalty rather than risk an ill-conditioned solve.
            val defaultPenalty = SpeedCalibration.DEFAULT.elevationGainPenaltyMetersPerKm
            val totalEquivalentKm = valid.sumOf { it.distanceMeters / 1000.0 + it.elevationGainMeters / defaultPenalty }
            val totalHours = y.sum()
            val fittedA = if (totalEquivalentKm > 0.0) totalHours / totalEquivalentKm else 1.0 / SpeedCalibration.DEFAULT.walkingSpeedKmh
            fittedA to (fittedA / defaultPenalty)
        }

        val speed = if (a > 0.0) (1.0 / a).coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH) else SpeedCalibration.DEFAULT.walkingSpeedKmh
        val penalty = if (b > 0.0) (a / b).coerceIn(MIN_PENALTY_M_PER_KM, MAX_PENALTY_M_PER_KM) else SpeedCalibration.DEFAULT.elevationGainPenaltyMetersPerKm

        return SpeedCalibration(walkingSpeedKmh = speed, elevationGainPenaltyMetersPerKm = penalty)
    }
}

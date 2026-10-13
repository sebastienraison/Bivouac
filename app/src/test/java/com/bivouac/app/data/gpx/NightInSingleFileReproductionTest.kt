package com.bivouac.app.data.gpx

import com.bivouac.app.data.db.LoggedTrackDayEntity
import com.bivouac.app.data.model.TrackPoint
import java.time.Duration
import java.time.Instant
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// RIC-222, 2026-10-13 : test de reproduction, échoue tant que le défaut n'est pas corrigé.
// Un trek de deux jours importé en UN SEUL fichier GPX : la nuit (trou d'enregistrement de 17 h entre
// deux points consécutifs distants de 141 m) ne doit pas être comptée comme du temps de marche.
// Les tests "témoin" (reprise à 40 m, sous GAP_MAX_METERS) passent aujourd'hui : ils prouvent que
// seul le seuil de distance de TrackPauseDetector sépare les deux cas.

/**
 * Trace d'un seul jour (une seule liste de points) : [HALF_DAY_HOURS] h de marche à 4 km/h (un point
 * tous les 30 m, 27 s), un trou de [NIGHT_HOURS] h dont la reprise se fait à [resumeGapMeters] du
 * dernier point, puis [HALF_DAY_HOURS] h de marche. Points sur un méridien, altitude vallonnée.
 *
 * Chaîne vérifiée, de la plus persistée à la plus affichée :
 *   - Journal (liste, cartouche, "% walking") : [DaySegmentSums.of] donne `pausedSeconds`, rangé sur
 *     `logged_track_day` avec `elapsedSeconds` (dernier moins premier point, voir
 *     LoggedTrackRepository.elapsedSeconds, privée donc reproduite ici), puis
 *     [RealDurationCalculator.forDays] ;
 *   - vue Analyse : [TrackAnalysisCalculator.compute] (totaux, estimation) puis
 *     [TrackTimelineCalculator.compute] (part de marche).
 */
class NightInSingleFileReproductionTest {

    private val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)

    private fun trace(resumeGapMeters: Double): List<TrackPoint> {
        val stepsPerHalfDay = (HALF_DAY_HOURS * 3_600 / STEP_SECONDS).toInt()
        val points = mutableListOf<TrackPoint>()
        var north = 0.0
        var time = START
        fun add() {
            points += TrackPoint(
                latitude = 45.0 + north * degPerMeter,
                longitude = 6.0,
                elevationMeters = 1_000.0 + 40.0 * sin(north / 500.0),
                time = time,
            )
        }
        add()
        repeat(stepsPerHalfDay) {
            north += STEP_METERS
            time = time.plusSeconds(STEP_SECONDS)
            add()
        }
        // La nuit : l'enregistrement s'arrête, puis reprend à resumeGapMeters du dernier point.
        north += resumeGapMeters
        time = time.plus(Duration.ofHours(NIGHT_HOURS))
        add()
        repeat(stepsPerHalfDay) {
            north += STEP_METERS
            time = time.plusSeconds(STEP_SECONDS)
            add()
        }
        return points
    }

    private fun journalDuration(points: List<TrackPoint>): RealDurationCalculator.RealDuration {
        val sums = DaySegmentSums.of(points)
        val elapsed = Duration.between(points.first().time, points.last().time).seconds
        val day = LoggedTrackDayEntity(
            trackId = "t",
            dayIndex = 0,
            rawGpxFilePath = "irrelevant",
            elapsedSeconds = elapsed,
            pausedSeconds = sums.pausedSeconds,
        )
        val real = RealDurationCalculator.forDays(listOf(day))
        assertNotNull(real)
        return real!!
    }

    private fun analysis(points: List<TrackPoint>): TrackAnalysis =
        TrackAnalysisCalculator.compute(listOf(points), SpeedCalibration.DEFAULT, emptyList())

    private fun describe(points: List<TrackPoint>): String {
        val real = journalDuration(points)
        val a = analysis(points)
        val totals = a.totals!!
        val timeline = TrackTimelineCalculator.compute(listOf(points), a)
        return "durée réelle ${real.elapsedSeconds / 3_600.0} h, marche ${real.walkingSeconds!! / 3_600.0} h, " +
            "part ${real.walkingSharePercent} %, analyse : écoulé ${totals.elapsedSeconds / 3_600.0} h, " +
            "pauses ${totals.pausedSeconds / 3_600.0} h, marche ${totals.movingSeconds / 3_600.0} h, " +
            "part ${timeline.walkingSharePercent} %, écart à l'estimation ${a.estimate!!.deltaSeconds / 3_600.0} h"
    }

    // --- Reproduction : la reprise est à 141 m, la nuit passe pour de la marche -------------------

    @Test
    fun `journal - le temps de marche ne contient pas la nuit quand le lendemain repart a 141 m`() {
        val points = trace(resumeGapMeters = 141.0)
        val real = journalDuration(points)
        val walkingHours = real.walkingSeconds!! / 3_600.0
        assertTrue(
            "temps de marche ${walkingHours} h, attendu moins de ${2 * HALF_DAY_HOURS + MARGIN_HOURS} h " +
                "(deux demi-journées de ${HALF_DAY_HOURS} h plus une marge) ; ${describe(points)}",
            walkingHours < 2 * HALF_DAY_HOURS + MARGIN_HOURS,
        )
    }

    @Test
    fun `analyse - le temps de marche des totaux ne contient pas la nuit quand le lendemain repart a 141 m`() {
        val points = trace(resumeGapMeters = 141.0)
        val movingHours = analysis(points).totals!!.movingSeconds / 3_600.0
        assertTrue(
            "temps de marche ${movingHours} h, attendu moins de ${2 * HALF_DAY_HOURS + MARGIN_HOURS} h ; ${describe(points)}",
            movingHours < 2 * HALF_DAY_HOURS + MARGIN_HOURS,
        )
    }

    @Test
    fun `analyse - la part de marche affichee ne depasse pas la part des deux demi-journees quand le lendemain repart a 141 m`() {
        val points = trace(resumeGapMeters = 141.0)
        val share = TrackTimelineCalculator.compute(listOf(points), analysis(points)).walkingSharePercent!!
        // 14 h de marche sur 31 h écoulées : 45 %. Borne large : 60 %.
        assertTrue("part de marche $share %, attendu au plus 60 % ; ${describe(points)}", share <= 60)
    }

    @Test
    fun `detecteur - une nuit de 17 h reprise a 141 m est detectee comme une pause`() {
        val pauses = TrackPauseDetector.detect(trace(resumeGapMeters = 141.0))
        val pausedHours = pauses.sumOf { it.seconds } / 3_600.0
        assertTrue(
            "pauses détectées : ${pauses.size} pour $pausedHours h, attendu au moins $NIGHT_HOURS h (seuil de distance " +
                "GAP_MAX_METERS = ${AnalysisParameters.DEFAULT.gapMaxMeters} m, reprise à 141 m)",
            pausedHours >= NIGHT_HOURS,
        )
    }

    // --- Témoins : reprise à 40 m, sous GAP_MAX_METERS (50 m) ; ces tests PASSENT aujourd'hui ------

    @Test
    fun `temoin journal - la reprise a 40 m voit la nuit comptee comme pause`() {
        val points = trace(resumeGapMeters = 40.0)
        val real = journalDuration(points)
        val walkingHours = real.walkingSeconds!! / 3_600.0
        assertTrue("temps de marche $walkingHours h ; ${describe(points)}", walkingHours < 2 * HALF_DAY_HOURS + MARGIN_HOURS)
        assertTrue("part ${real.walkingSharePercent} %", real.walkingSharePercent!! <= 60)
    }

    @Test
    fun `temoin analyse - la reprise a 40 m voit la nuit comptee comme pause`() {
        val points = trace(resumeGapMeters = 40.0)
        val a = analysis(points)
        val totals = a.totals!!
        assertTrue("pauses ${totals.pausedSeconds / 3_600.0} h ; ${describe(points)}", totals.pausedSeconds >= NIGHT_HOURS * 3_600.0)
        assertTrue(totals.movingSeconds / 3_600.0 < 2 * HALF_DAY_HOURS + MARGIN_HOURS)
        assertTrue(TrackTimelineCalculator.compute(listOf(points), a).walkingSharePercent!! <= 60)
    }

    @Test
    fun `temoin detecteur - la reprise a 40 m produit une pause couvrant la nuit`() {
        val pauses = TrackPauseDetector.detect(trace(resumeGapMeters = 40.0))
        assertEquals(1, pauses.size)
        assertTrue(pauses.single().seconds >= NIGHT_HOURS * 3_600.0)
    }

    private companion object {
        val START: Instant = Instant.parse("2026-06-01T07:00:00Z")
        const val STEP_METERS = 30.0
        const val STEP_SECONDS = 27L
        const val HALF_DAY_HOURS = 7L
        const val NIGHT_HOURS = 17L
        const val MARGIN_HOURS = 1L
    }
}

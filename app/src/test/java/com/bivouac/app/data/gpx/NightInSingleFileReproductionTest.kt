package com.bivouac.app.data.gpx

import com.bivouac.app.data.db.LoggedTrackDayEntity
import com.bivouac.app.data.model.TrackPoint
import java.time.Duration
import java.time.Instant
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Test

// RIC-222, 2026-10-13 : test de reproduction, échoue tant que le défaut n'est pas corrigé.
// 2026-10-14 (RIC-225) : le test du détecteur est vert contre BivouacDetector.
// 2026-10-14 (RIC-226) : les deux tests de temps de marche (Journal, Analyse) sont actifs et verts.
// 2026-10-15 (RIC-226, décision du pilotage) : les tests qui décrivaient l'ancien comportement (part de
// marche sur l'écoulé avec la nuit, nuit à 40 m comptée en pause) sont retirés, contraires à la
// conception validée le 14/10 (§3.4, « la nuit est retirée de la durée écoulée et du temps de
// marche »). Le comportement attendu est verrouillé par DayBivouacsTest (mêmes chiffres à 40 m et à
// 141 m, et mêmes chiffres qu'en deux fichiers).
// Un trek de deux jours importé en UN SEUL fichier GPX : la nuit (trou d'enregistrement de 17 h entre
// deux points consécutifs distants de 141 m) ne doit pas être comptée comme du temps de marche.
// Le témoin restant (reprise à 40 m, sous GAP_MAX_METERS) montre que le détecteur de pauses, lui,
// voit toujours la nuit comme une pause à cette distance : seul son seuil sépare les deux cas.

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
        // RIC-226 : les deux colonnes viennent désormais du calcul public que l'import, le
        // rattrapage et le découpage partagent (JournalDayStats), plus d'une reproduction locale de
        // l'ancienne fonction privée LoggedTrackRepository.elapsedSeconds.
        val figures = JournalDayStats.of(points)
        val day = LoggedTrackDayEntity(
            trackId = "t",
            dayIndex = 0,
            rawGpxFilePath = "irrelevant",
            elapsedSeconds = figures.elapsedSeconds,
            pausedSeconds = figures.sums.pausedSeconds,
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
    fun `detecteur - une nuit de 17 h reprise a 141 m est detectee comme un bivouac`() {
        // RIC-225 : la règle du bivouac regarde le trou et la nuit, pas la distance. Fuseau imposé.
        val points = trace(resumeGapMeters = 141.0)
        val bivouacs = BivouacDetector.detect(points) { ZoneId.of("Europe/Paris") }
        assertTrue("bivouacs détectés : ${bivouacs.size}, attendu 1", bivouacs.size == 1)
        val bivouac = bivouacs.single()
        assertEquals(points.size / 2 - 1, bivouac.stopIndex)
        assertEquals(bivouac.stopIndex + 1, bivouac.resumeIndex)
        assertTrue(
            "trou de ${Duration.between(bivouac.stopAt, bivouac.resumeAt).toHours()} h, attendu ${NIGHT_HOURS} h",
            Duration.between(bivouac.stopAt, bivouac.resumeAt) == Duration.ofHours(NIGHT_HOURS),
        )
        // La nuit n'est pas une pause : le détecteur de pauses ne la voit toujours pas à 141 m.
        assertTrue(TrackPauseDetector.detect(points).none { it.seconds >= NIGHT_HOURS * 3_600.0 })
    }

    // --- Témoins : reprise à 40 m, sous GAP_MAX_METERS (50 m) ; ces tests PASSENT aujourd'hui ------

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

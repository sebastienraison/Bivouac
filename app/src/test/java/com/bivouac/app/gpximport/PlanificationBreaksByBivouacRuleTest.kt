package com.bivouac.app.gpximport

import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.TrackPoint
import java.time.Instant
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-223 (lot 4 de la 2.7.0) : totaux et segments de la Planification quand les coupures
 * viennent de la règle du bivouac (DayJunctions.planificationSeriesBreaks). Le fuseau est celui des
 * coordonnées (45 N, 6 E : Europe/Paris, UTC+2 en juillet). Traces sur un méridien : la distance
 * entre deux points vaut l'écart de leur abscisse.
 */
class PlanificationBreaksByBivouacRuleTest {

    private val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)
    private val calibration = SpeedCalibration.DEFAULT

    private fun elevation(northMeters: Double) = 1_800.0 - 0.03 * northMeters + 40.0 * sin(northMeters / 600.0)

    /**
     * 80 points espacés de 141 m. Avec [nightGapMeters], un trou est ouvert après l'index
     * [NIGHT_STOP] (20 h 30 locale, reprise à 7 h le lendemain) et les points suivants sont
     * décalés d'autant ; sans lui, la trace n'a pas d'heures.
     */
    private fun track(nightGapMeters: Double?): List<TrackPoint> {
        var north = 0.0
        return (0 until 80).map { i ->
            if (i > 0) north += 141.0 + if (nightGapMeters != null && i == NIGHT_STOP + 1) nightGapMeters else 0.0
            val time = nightGapMeters?.let {
                if (i <= NIGHT_STOP) Instant.parse("2026-07-01T18:30:00Z").minusSeconds(120L * (NIGHT_STOP - i))
                else Instant.parse("2026-07-02T05:00:00Z").plusSeconds(120L * (i - NIGHT_STOP - 1))
            }
            TrackPoint(45.0 + north * degPerMeter, 6.0, elevation(north), time)
        }
    }

    private fun assertSegmentsSumToTotal(points: List<TrackPoint>, bivouacs: List<BivouacPoint>) {
        val total = planificationTotalStats(points, bivouacs, calibration)
        val segments = planificationSegments(points, bivouacs, bivouacs, calibration)
        assertEquals(bivouacs.size + 1, segments.size)
        assertEquals(total.distanceMeters, segments.sumOf { it.stats.distanceMeters }, 1e-6)
        assertEquals(total.elevationGainMeters, segments.sumOf { it.stats.elevationGainMeters }, 1e-6)
        assertEquals(total.elevationLossMeters, segments.sumOf { it.stats.elevationLossMeters }, 1e-6)
    }

    @Test
    fun `sans heures, poser des bivouacs ne change ni le total ni la somme des segments`() {
        val points = track(nightGapMeters = null)
        val none = planificationTotalStats(points, emptyList(), calibration)
        val bivouacs = listOf(BivouacPoint("a", 20), BivouacPoint("b", NIGHT_STOP), BivouacPoint("c", 60))
        val with = planificationTotalStats(points, bivouacs, calibration)

        assertEquals(none.distanceMeters, with.distanceMeters, 0.0)
        assertEquals(none.elevationGainMeters, with.elevationGainMeters, 0.0)
        assertEquals(none.elevationLossMeters, with.elevationLossMeters, 0.0)

        val segments = planificationSegments(points, bivouacs, bivouacs, calibration)
        assertEquals(none.distanceMeters, segments.sumOf { it.stats.distanceMeters }, 1e-6)
        assertEquals(none.elevationGainMeters, segments.sumOf { it.stats.elevationGainMeters }, 1e-6)
        assertEquals(none.elevationLossMeters, segments.sumOf { it.stats.elevationLossMeters }, 1e-6)
    }

    @Test
    fun `avec une nuit coupee loin du camp, la somme des segments egale le total et le troncon de la nuit est exclu`() {
        val points = track(nightGapMeters = 2_000.0)
        val bivouacs = listOf(BivouacPoint("pause", 20), BivouacPoint("nuit", NIGHT_STOP))
        assertSegmentsSumToTotal(points, bivouacs)

        // Le trou de la nuit : 141 m de pas habituel + 2 000 m, entre les index NIGHT_STOP et +1.
        val nightLeg = 141.0 + 2_000.0
        val without = planificationTotalStats(points, emptyList(), calibration)
        val with = planificationTotalStats(points, bivouacs, calibration)
        assertEquals(nightLeg, without.distanceMeters - with.distanceMeters, 1e-3)
        assertTrue("la nuit retire aussi du dénivelé ou n'en change pas", with.elevationLossMeters <= without.elevationLossMeters)

        // La nuit ne tombe dans aucun segment comme distance : le segment qui la contient commence au bivouac.
        val segments = planificationSegments(points, bivouacs, bivouacs, calibration)
        assertEquals(
            without.distanceMeters - nightLeg,
            segments.sumOf { it.stats.distanceMeters },
            1e-3,
        )
    }

    @Test
    fun `une nuit a 30 m du camp est une coupure aussi, le troncon exclu est negligeable`() {
        val points = track(nightGapMeters = -111.0)
        val bivouacs = listOf(BivouacPoint("nuit", NIGHT_STOP))
        assertSegmentsSumToTotal(points, bivouacs)
        val without = planificationTotalStats(points, emptyList(), calibration)
        val with = planificationTotalStats(points, bivouacs, calibration)
        assertEquals(30.0, without.distanceMeters - with.distanceMeters, 1e-3)
    }

    private companion object {
        const val NIGHT_STOP = 39
    }
}

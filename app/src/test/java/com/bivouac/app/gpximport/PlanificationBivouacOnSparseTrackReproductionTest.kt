package com.bivouac.app.gpximport

import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.TrackPoint
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

// RIC-223, 2026-10-13 : test de reproduction, échoue tant que le défaut n'est pas corrigé.
// En Planification, poser un bivouac sur un point intermédiaire d'une trace continue et clairsemée
// (points espacés de 141 m, donc au-delà de DayJunctions.GAP_THRESHOLD_METERS = 50 m) ne doit rien
// retirer des totaux. Le test "témoin" (même géométrie rééchantillonnée à 30 m) passe aujourd'hui :
// il prouve que seul l'espacement des points sépare les deux cas.

/**
 * Les totaux sont lus par [planificationTotalStats], la fonction que GpxImportViewModel
 * (`computeTotalStats`) utilise pour la statistique affichée en Planification. Trace continue, sans
 * horodatage, sur un méridien : la distance entre deux points vaut exactement leur écart d'abscisse.
 */
class PlanificationBivouacOnSparseTrackReproductionTest {

    private val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)
    private val calibration = SpeedCalibration.DEFAULT

    /** Altitude fonction de l'abscisse seule : descente de 320 m en tendance plus des ondulations
     * (pente moyenne 2,9 %, ondulation jusqu'à 6,7 % : il y a du D+ comme du D-). */
    private fun elevation(northMeters: Double) =
        1_800.0 - 320.0 * northMeters / TOTAL_METERS + 40.0 * sin(northMeters / 600.0)

    /** [steps] pas de [stepMeters] ; le dernier point est à steps * stepMeters du départ. */
    private fun track(stepMeters: Double, steps: Int): List<TrackPoint> =
        (0..steps).map { i ->
            val north = i * stepMeters
            TrackPoint(
                latitude = 45.0 + north * degPerMeter,
                longitude = 6.0,
                elevationMeters = elevation(north),
                time = null,
            )
        }

    private class Totals(val distance: Double, val gain: Double, val loss: Double) {
        override fun toString() = "distance %.1f m, D+ %.2f m, D- %.2f m".format(distance, gain, loss)
    }

    private fun totals(points: List<TrackPoint>, bivouacIndex: Int?): Totals {
        val bivouacs = bivouacIndex?.let { listOf(BivouacPoint("b", it)) } ?: emptyList()
        val stats = planificationTotalStats(points, bivouacs, calibration)
        return Totals(stats.distanceMeters, stats.elevationGainMeters, stats.elevationLossMeters)
    }

    private fun assertBivouacLosesNothing(points: List<TrackPoint>, bivouacIndex: Int) {
        val without = totals(points, null)
        val with = totals(points, bivouacIndex)
        val equal = abs(with.distance - without.distance) < 1e-6 &&
            abs(with.gain - without.gain) < 1e-6 &&
            abs(with.loss - without.loss) < 1e-6
        assertTrue(
            "poser un bivouac sur le point $bivouacIndex change les totaux : sans bivouac [$without], avec [$with], " +
                "perdu : distance %.1f m, D+ %.2f m, D- %.2f m".format(
                    without.distance - with.distance,
                    without.gain - with.gain,
                    without.loss - with.loss,
                ),
            equal,
        )
    }

    @Test
    fun `un bivouac pose sur une trace a points espaces de 141 m ne retire rien des totaux`() {
        // 79 pas de 141 m : 11 139 m, soit les 11,1 km du constat.
        assertBivouacLosesNothing(track(stepMeters = 141.0, steps = 79), bivouacIndex = 40)
    }

    @Test
    fun `temoin - la meme geometrie a 30 m entre points ne perd rien avec un bivouac`() {
        // 371 pas de 30 m : 11 130 m, même parcours, même fonction d'altitude, sous le seuil de 50 m.
        assertBivouacLosesNothing(track(stepMeters = 30.0, steps = 371), bivouacIndex = 185)
    }

    private companion object {
        const val TOTAL_METERS = 11_139.0
    }
}

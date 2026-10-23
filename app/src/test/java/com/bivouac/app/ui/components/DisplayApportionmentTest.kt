package com.bivouac.app.ui.components

import com.bivouac.app.data.gpx.TrackStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.floor
import kotlin.math.round

/**
 * RIC-234 : la répartition au plus grand reste, éprouvée sans Compose ni locale. Les distances sont
 * en mètres avec un pas de 100 m (0,1 km affiché), les dénivelés en mètres avec un pas de 1 m.
 */
class DisplayApportionmentTest {

    private fun km(steps: List<Long>) = steps.map { it / 10.0 }

    @Test
    fun emptyList_givesEmptyList() {
        assertEquals(emptyList<Long>(), apportionToDisplayedTotal(0.0, emptyList(), 100.0))
        assertEquals(emptyList<Long>(), apportionToDisplayedTotal(11_760.0, emptyList(), 100.0))
    }

    @Test
    fun singlePart_isTheRoundedTotal() {
        assertEquals(listOf(118L), apportionToDisplayedTotal(11_760.0, listOf(11_760.0), 100.0))
        assertEquals(listOf(0L), apportionToDisplayedTotal(0.0, listOf(0.0), 100.0))
    }

    @Test
    fun sebExample_firstPartTakesTheMissingTenth() {
        // 10,24 + 1,52 = 11,76 km. Valeurs réelles inconnues du relevé (10,2 + 1,5 affichés pour 11,8) :
        // celles-ci reproduisent l'écart. Restes : 0,4 et 0,2, donc la première part prend le dixième.
        val steps = apportionToDisplayedTotal(11_760.0, listOf(10_240.0, 1_520.0), 100.0)
        assertEquals(listOf(103L, 15L), steps)
        assertEquals(11.8, km(steps).sum(), 1e-9)
    }

    @Test
    fun missingTenthGoesToTheLargestRemainderWhoeverItIs() {
        // 10,21 + 1,54 = 11,75 km (arrondi pair : 11,8). Restes : 0,1 et 0,4, donc la SECONDE part.
        val steps = apportionToDisplayedTotal(11_750.0, listOf(10_210.0, 1_540.0), 100.0)
        assertEquals(listOf(102L, 16L), steps)
    }

    @Test
    fun emulatorExample_8_8_and_2_9_for_11_8() {
        // 8,84 + 2,93 = 11,77 km : l'arrondi valeur par valeur donnerait 8,8 + 2,9 = 11,7.
        val steps = apportionToDisplayedTotal(11_770.0, listOf(8_840.0, 2_930.0), 100.0)
        assertEquals(listOf(89L, 29L), steps)
        assertEquals(11.8, km(steps).sum(), 1e-9)
    }

    @Test
    fun partsAlreadySummingToTheRoundedTotal_areUnchanged() {
        assertEquals(listOf(50L, 30L), apportionToDisplayedTotal(8_000.0, listOf(5_000.0, 3_000.0), 100.0))
        // 8,84 + 2,83 = 11,67 km -> 11,7 : 88 + 28 = 116, un pas manquant, pris par la plus grande reste.
        assertEquals(listOf(89L, 28L), apportionToDisplayedTotal(11_670.0, listOf(8_840.0, 2_830.0), 100.0))
        // La somme des parties entières (50 + 25) est déjà le total arrondi (7,54 km -> 75) : inchangées.
        assertEquals(listOf(50L, 25L), apportionToDisplayedTotal(7_540.0, listOf(5_020.0, 2_520.0), 100.0))
    }

    @Test
    fun zeroPart_staysZero() {
        val steps = apportionToDisplayedTotal(7_560.0, listOf(0.0, 5_040.0, 2_520.0), 100.0)
        assertEquals(listOf(0L, 51L, 25L), steps)
    }

    @Test
    fun individualRoundingsExceedingTheTotal_getTheirExtraStepRemoved() {
        // 0,06 + 0,06 + 0,06 km avec un pas de 0,1 : chacune s'arrondirait à 0,1 (somme 0,3) alors que
        // le total, 0,18, s'arrondit à 0,2. Le pas en trop n'est jamais distribué : à égalité de
        // restes, les deux premières parts reçoivent les deux pas, la troisième reste à zéro.
        val steps = apportionToDisplayedTotal(180.0, listOf(60.0, 60.0, 60.0), 100.0)
        assertEquals(listOf(1L, 1L, 0L), steps)
    }

    @Test
    fun inconsistentTotal_removesTheSurplusFromTheSmallestRemainder() {
        // Cas défensif (le total réel est toujours la somme des parts) : les parties entières (1 + 1)
        // dépassent le total arrondi (1). Le pas en trop est retiré à la plus petite reste ; à égalité,
        // à la dernière de la liste.
        assertEquals(listOf(1L, 0L), apportionToDisplayedTotal(100.0, listOf(120.0, 120.0), 100.0))
        assertEquals(listOf(1L, 0L), apportionToDisplayedTotal(100.0, listOf(130.0, 110.0), 100.0))
        assertEquals(listOf(0L, 1L), apportionToDisplayedTotal(100.0, listOf(110.0, 130.0), 100.0))
    }

    @Test
    fun elevationStep_truncatedTotalMatchesTheDisplayedTruncation() {
        // L'affichage du D+ tronque (formatGroupedInt : toLong). 100,75 + 200,75 = 301,5 : affiché 301.
        val truncated = apportionToDisplayedTotal(301.5, listOf(100.75, 200.75), 1.0, TotalRounding.TRUNCATE)
        assertEquals(listOf(101L, 200L), truncated)
        assertEquals(301L, truncated.sum())
        // Arrondi au plus proche (pair), le même relevé donnerait 302.
        val nearest = apportionToDisplayedTotal(301.5, listOf(100.75, 200.75), 1.0, TotalRounding.NEAREST)
        assertEquals(listOf(101L, 201L), nearest)
    }

    @Test
    fun randomInputs_alwaysSumToTheTargetAndStayWithinOneStep() {
        val random = Random(234)
        repeat(500) {
            val count = 1 + random.nextInt(8)
            val parts = List(count) { if (random.nextInt(6) == 0) 0.0 else random.nextDouble() * 30_000 }
            val total = parts.sum()
            for ((step, rounding) in listOf(100.0 to TotalRounding.NEAREST, 1.0 to TotalRounding.TRUNCATE)) {
                val steps = apportionToDisplayedTotal(total, parts, step, rounding)
                val target = if (rounding == TotalRounding.NEAREST) round(total / step) else floor(total / step)
                assertEquals(target.toLong(), steps.sum())
                steps.forEachIndexed { i, s ->
                    val exact = parts[i] / step
                    assertTrue("part $i : $s pour $exact", s >= floor(exact).toLong() && s <= floor(exact).toLong() + 1)
                    if (parts[i] == 0.0) assertEquals(0L, s)
                }
            }
        }
    }

    @Test
    fun segmentStats_sumToTheDisplayedTotal_andLeaveTheRestUntouched() {
        val total = TrackStats(11_770.0, 1_000.6, 900.6, 480, elevationAvailable = true)
        val segments = listOf(
            TrackStats(8_840.0, 600.3, 500.3, 360),
            TrackStats(2_930.0, 400.3, 400.3, 120),
        )
        val shown = apportionedSegmentStats(total, segments)
        assertEquals(listOf(8_900.0, 2_900.0), shown.map { it.distanceMeters })
        assertEquals(11_800.0, shown.sumOf { it.distanceMeters }, 1e-9)
        // D+ : 600,3 + 400,3 = 1000,6 affiché 1000 (tronqué) ; D- : 500,3 + 400,3 = 900,6 affiché 900.
        assertEquals(1_000.0, shown.sumOf { it.elevationGainMeters }, 1e-9)
        assertEquals(900.0, shown.sumOf { it.elevationLossMeters }, 1e-9)
        assertEquals(listOf(360, 120), shown.map { it.estimatedDurationMinutes })
        assertEquals(listOf(true, true), shown.map { it.elevationAvailable })
    }

    @Test
    fun segmentStats_withoutElevation_keepTheirElevationFigures() {
        val total = TrackStats(11_770.0, 0.0, 0.0, 480, elevationAvailable = false)
        val segments = listOf(
            TrackStats(8_840.0, 0.0, 0.0, 360, elevationAvailable = false),
            TrackStats(2_930.0, 0.0, 0.0, 120, elevationAvailable = false),
        )
        val shown = apportionedSegmentStats(total, segments)
        assertEquals(listOf(8_900.0, 2_900.0), shown.map { it.distanceMeters })
        assertEquals(segments.map { it.elevationGainMeters }, shown.map { it.elevationGainMeters })
        assertEquals(segments.map { it.elevationLossMeters }, shown.map { it.elevationLossMeters })
        assertEquals(listOf(false, false), shown.map { it.elevationAvailable })
    }

    @Test
    fun segmentStats_emptyList_staysEmpty() {
        assertEquals(emptyList<TrackStats>(), apportionedSegmentStats(TrackStats(0.0, 0.0, 0.0, 0), emptyList()))
    }
}

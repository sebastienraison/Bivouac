package com.bivouac.app.data.db

import com.bivouac.app.data.gpx.DetectedBivouac
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** RIC-226 : [DaySplitPlan.fileRanges], les plages des jours issus d'un découpage (calcul pur). */
class DaySplitPlanTest {

    private fun bivouac(stop: Int, resume: Int = stop + 1) =
        DetectedBivouac(stop, resume, Instant.EPOCH, Instant.EPOCH, ZoneId.of("Europe/Paris"))

    @Test
    fun oneBivouacGivesTwoContiguousRanges() {
        assertEquals(listOf(0..14, 15..29), DaySplitPlan.fileRanges(30, 0, listOf(bivouac(14))))
    }

    @Test
    fun rangesAreExpressedInFileIndicesForADayThatIsAlreadyARange() {
        assertEquals(listOf(15..29, 30..44), DaySplitPlan.fileRanges(30, 15, listOf(bivouac(14))))
    }

    @Test
    fun bivouacsInAnyOrderGiveRangesInFileOrderCoveringTheDay() {
        val ranges = DaySplitPlan.fileRanges(45, 0, listOf(bivouac(29), bivouac(14)))
        assertEquals(listOf(0..14, 15..29, 30..44), ranges)
        assertEquals((0..44).toList(), ranges.flatMap { it.toList() })
    }

    @Test
    fun aOnePointPieceIsAllowed() {
        assertEquals(listOf(0..5, 6..6, 7..9), DaySplitPlan.fileRanges(10, 0, listOf(bivouac(5), bivouac(6))))
    }

    @Test
    fun invalidBivouacsAreRefused() {
        val cases = listOf(
            "vide" to emptyList(),
            "reprise au-delà du dernier point" to listOf(bivouac(29)),
            "arrêt négatif" to listOf(bivouac(-1)),
            "reprise qui ne suit pas l'arrêt" to listOf(bivouac(10, 12)),
            "reprise avant l'arrêt" to listOf(bivouac(10, 9)),
            "même bivouac deux fois" to listOf(bivouac(14), bivouac(14)),
        )
        for ((label, bivouacs) in cases) {
            try {
                DaySplitPlan.fileRanges(30, 0, bivouacs)
                fail("$label : accepté")
            } catch (expected: IllegalArgumentException) {
                // attendu
            }
        }
    }
}

package com.bivouac.app.bilan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIC-231 : les textes « indisponible » (ressources v21) dans les deux langues, et la ligne méta
 * d'un trek du Bilan dont aucun jour n'a d'altitude (la distance seule, pas un D+ de 0 m).
 */
@RunWith(RobolectricTestRunner::class)
class ElevationUnavailableTextsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "fr")
    fun frenchTexts() {
        assertEquals("D+ indisponible", context.getString(R.string.fmt_stats_rows_gain_unavailable))
        assertEquals("D- indisponible", context.getString(R.string.fmt_stats_rows_loss_unavailable))
        assertEquals("Pas d'altitude dans cette trace.", context.getString(R.string.elevation_profile_unavailable))
        assertTrue(context.getString(R.string.elevation_missing_banner).startsWith("Cette trace n'a pas d'altitude"))
        assertTrue(context.getString(R.string.analysis_slope_unavailable).startsWith("Pente indisponible"))
        assertEquals("Analyse indisponible : cette trace n'a pas d'altitude.", context.getString(R.string.journal_analysis_no_elevation))
    }

    @Test
    @Config(qualifiers = "en")
    fun englishTexts() {
        assertEquals("Gain unavailable", context.getString(R.string.fmt_stats_rows_gain_unavailable))
        assertEquals("Loss unavailable", context.getString(R.string.fmt_stats_rows_loss_unavailable))
        assertEquals("No elevation data in this track.", context.getString(R.string.elevation_profile_unavailable))
        assertTrue(context.getString(R.string.elevation_missing_banner).startsWith("No elevation data in this track"))
        assertTrue(context.getString(R.string.analysis_slope_unavailable).startsWith("Gradient unavailable"))
        assertEquals("Analysis unavailable: no elevation data in this track.", context.getString(R.string.journal_analysis_no_elevation))
    }

    @Test
    @Config(qualifiers = "fr")
    fun aTrekRecordWithoutGainShowsItsDistanceAloneOnTheExtraLine() {
        val withGain = BilanRecord(
            kind = BilanRecordKind.BIGGEST_TREK, value = 3.0, placeName = "Trek", whenMillis = 0L, trackId = "t",
            extraDistanceKm = 30.0, extraGainMeters = 1_200.0,
        )
        val withoutGain = withGain.copy(extraGainMeters = null)

        val linesWith = recordMetaLines(context, withGain)
        val linesWithout = recordMetaLines(context, withoutGain)

        assertEquals(3, linesWith.size)
        assertEquals(3, linesWithout.size)
        assertTrue(linesWithout.last().contains("30"))
        assertTrue("pas de D+ dans la ligne : ${linesWithout.last()}", !linesWithout.last().contains("D+"))
    }
}

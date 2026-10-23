package com.bivouac.app.ui.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIC-235 : durée du trou dans le corps du dialogue « Arrêt prolongé détecté », alignée sur le
 * motif des instants (« 19h02 » en français, « 19h 02m » en anglais). Sous une heure,
 * journal_analysis_pause_minutes ; arrondi à la minute comme formatPauseDuration.
 */
@RunWith(RobolectricTestRunner::class)
class BivouacGapDurationFormatTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun minutes(m: Int): Double = m * 60.0

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `francais`() {
        assertEquals("59 min", formatBivouacGapDuration(minutes(59), context))
        assertEquals("1h00", formatBivouacGapDuration(minutes(60), context))
        assertEquals("19h02", formatBivouacGapDuration(minutes(19 * 60 + 2), context))
        assertEquals("25h03", formatBivouacGapDuration(minutes(25 * 60 + 3), context))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `anglais`() {
        assertEquals("59 min", formatBivouacGapDuration(minutes(59), context))
        assertEquals("1h 00m", formatBivouacGapDuration(minutes(60), context))
        assertEquals("19h 02m", formatBivouacGapDuration(minutes(19 * 60 + 2), context))
        assertEquals("25h 03m", formatBivouacGapDuration(minutes(25 * 60 + 3), context))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `arrondi a la minute`() {
        assertEquals("59 min", formatBivouacGapDuration(minutes(59) + 29.0, context))
        assertEquals("1h00", formatBivouacGapDuration(minutes(59) + 30.0, context))
        assertEquals("19h02", formatBivouacGapDuration(minutes(19 * 60 + 1) + 31.0, context))
    }
}

package com.bivouac.app.ui.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIC-146 lot 4 correction 3 (brief Partie A.3) : durée d'une pause sur la carte
 * ([formatShortDuration]), de part et d'autre d'une heure. Le pendant du cran Détails
 * ([formatPauseDuration] dans JournalAnalysisContent.kt) applique la même règle mais n'est pas
 * testé séparément : c'est un @Composable, pas exercable en JVM pur sans règle de test Compose,
 * et sa logique (le seuil de 60 min) est celle-ci, dupliquée volontairement (voir le commentaire de
 * formatShortDuration).
 */
@RunWith(RobolectricTestRunner::class)
class PauseDurationFormattingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // Format français de fmt_stats_rows_duration (chaîne pré-existante, hors périmètre de ce lot) :
    // "%1$dh%2$s", sans espace ni "m" -- différent de la version anglaise ("%1$dh %2$sm").
    @Test
    @Config(qualifiers = "fr-rFR")
    fun `sous une heure la duree courte est utilisee`() {
        assertEquals("12 min", formatShortDuration(context, 12 * 60.0))
        assertEquals("0 min", formatShortDuration(context, 0.0))
        // 59 min 29 s arrondit a 59 min, reste sous le seuil de l'heure.
        assertEquals("59 min", formatShortDuration(context, 59 * 60.0 + 29.0))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `a partir d une heure le format existant s applique`() {
        assertEquals("1h00", formatShortDuration(context, 60 * 60.0))
        assertEquals("1h31", formatShortDuration(context, (60 + 31) * 60.0))
        assertEquals("2h05", formatShortDuration(context, (2 * 60 + 5) * 60.0))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `l arrondi a la minute peut faire basculer d un cote ou de l autre de l heure`() {
        // 59 min 31 s arrondit a 60 min : bascule sur le format heures/minutes.
        assertEquals("1h00", formatShortDuration(context, 59 * 60.0 + 31.0))
        // 60 min 29 s arrondit a 60 min tout pareil.
        assertEquals("1h00", formatShortDuration(context, 60 * 60.0 + 29.0))
    }
}

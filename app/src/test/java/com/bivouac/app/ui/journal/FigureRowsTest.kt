package com.bivouac.app.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-146 lot 7 (brief Partie D.6) : les lignes de la section "Chiffres", six avec horodatage,
 * réduites à quatre (altitudes et pentes seulement) sans horodatage. Fonction pure, extraite
 * d'AnalysisFiguresSection pour ce test.
 */
class FigureRowsTest {

    @Test
    fun sixLignesAvecHorodatage() {
        assertEquals(
            listOf(
                FigureRowKind.MOVING_SPEED,
                FigureRowKind.CLIMBING_SPEED,
                FigureRowKind.HIGHEST,
                FigureRowKind.LOWEST,
                FigureRowKind.STEEPEST_CLIMB,
                FigureRowKind.STEEPEST_DESCENT,
            ),
            figureRowsFor(hasTimestamps = true),
        )
    }

    @Test
    fun quatreLignesReduitesAuxAltitudesEtPentesSansHorodatage() {
        assertEquals(
            listOf(FigureRowKind.HIGHEST, FigureRowKind.LOWEST, FigureRowKind.STEEPEST_CLIMB, FigureRowKind.STEEPEST_DESCENT),
            figureRowsFor(hasTimestamps = false),
        )
    }
}

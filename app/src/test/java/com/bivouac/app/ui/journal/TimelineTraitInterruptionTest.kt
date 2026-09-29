package com.bivouac.app.ui.journal

import com.bivouac.app.data.gpx.ShortTimelinePause
import com.bivouac.app.data.gpx.TimelinePhase
import com.bivouac.app.data.gpx.TimelinePhaseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-146 lot 8 : position et hauteur des interruptions du trait d'une phase aux pauses courtes
 * qu'elle contient ([traitInterruptionsFor]). Phase de 3 000 s (50 min) -> phaseHeightDp = 78 dp
 * (au-dessus du plancher, pour un calcul de fraction sans arrondi de plancher à démêler).
 */
class TimelineTraitInterruptionTest {

    private fun phase(startSeconds: Double, endSeconds: Double) = TimelinePhase(
        kind = TimelinePhaseKind.CLIMB,
        startIndex = 0,
        endIndex = 1,
        startSeconds = startSeconds,
        endSeconds = endSeconds,
        distanceMeters = 1_000.0,
        elevationGainMeters = 100.0,
        elevationLossMeters = 0.0,
        movingSeconds = endSeconds - startSeconds,
        movingSpeedKmh = 3.0,
        paceDeltaPercent = 0,
    )

    @Test
    fun positionEtHauteurAuMilieuDeLaPhase() {
        val p = phase(100.0, 3_100.0) // durée 3 000 s, hauteur 78 dp
        val pause = ShortTimelinePause(startSeconds = 1_600.0, pausedSeconds = 120.0, distanceMeters = 500.0) // milieu, 2 min
        val result = traitInterruptionsFor(p, listOf(pause))
        assertEquals(1, result.size)
        assertEquals(39f, result[0].startDp, 0.01f) // 50 % de 78 dp
        assertEquals(3.12f, result[0].heightDp, 0.05f) // 4 % de 78 dp
    }

    @Test
    fun uneInterruptionTresCourteRestePlancheeAUnMinimumVisible() {
        val p = phase(100.0, 3_100.0)
        val pause = ShortTimelinePause(startSeconds = 1_600.0, pausedSeconds = 10.0, distanceMeters = 50.0) // 10 s, quasi invisible
        val result = traitInterruptionsFor(p, listOf(pause))
        assertEquals(TimelineLayout.MIN_INTERRUPTION_HEIGHT_DP, result[0].heightDp, 0.001f)
    }

    @Test
    fun unePauseAvantLaPhaseEstIgnoree() {
        val p = phase(100.0, 3_100.0)
        val pause = ShortTimelinePause(startSeconds = 50.0, pausedSeconds = 60.0, distanceMeters = 10.0)
        assertTrue(traitInterruptionsFor(p, listOf(pause)).isEmpty())
    }

    @Test
    fun unePauseAuDebutExactDeLaPhaseSuivanteEstIgnoree() {
        val p = phase(100.0, 3_100.0)
        // Pile à endSeconds : appartient à la phase suivante, pas à celle-ci (bornes demi-ouvertes).
        val pause = ShortTimelinePause(startSeconds = 3_100.0, pausedSeconds = 60.0, distanceMeters = 10.0)
        assertTrue(traitInterruptionsFor(p, listOf(pause)).isEmpty())
    }

    @Test
    fun unePhaseDeDureeNulleNaAucuneInterruption() {
        val p = phase(100.0, 100.0)
        val pause = ShortTimelinePause(startSeconds = 100.0, pausedSeconds = 5.0, distanceMeters = 1.0)
        assertTrue(traitInterruptionsFor(p, listOf(pause)).isEmpty())
    }

    @Test
    fun laHauteurNeDepassePasCeQuiResteDansLaPhase() {
        val p = phase(0.0, 3_000.0) // hauteur 78 dp
        // Pause presque aussi longue que la phase, en fin de phase : la hauteur ne peut pas déborder.
        val pause = ShortTimelinePause(startSeconds = 2_900.0, pausedSeconds = 90.0, distanceMeters = 5.0)
        val result = traitInterruptionsFor(p, listOf(pause))
        val startDp = result[0].startDp
        assertTrue(startDp + result[0].heightDp <= 78f + 0.01f)
    }
}

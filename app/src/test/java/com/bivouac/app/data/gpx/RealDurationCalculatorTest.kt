package com.bivouac.app.data.gpx

import com.bivouac.app.data.db.LoggedTrackDayEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-209 (brief Partie B, chantier RIC-146 lot 5) : durée réelle, à partir des colonnes du lot 1
 * (elapsedSeconds/pausedSeconds), sans passer par TrackStatsCalculator.
 */
class RealDurationCalculatorTest {

    private fun day(elapsedSeconds: Long?, pausedSeconds: Double?, dayIndex: Int = 0) = LoggedTrackDayEntity(
        trackId = "t",
        dayIndex = dayIndex,
        rawGpxFilePath = "irrelevant",
        elapsedSeconds = elapsedSeconds,
        pausedSeconds = pausedSeconds,
    )

    // --- forDays --------------------------------------------------------------------------------

    @Test
    fun uneRandoDUnJourDonneSaDureeEtSaMarche() {
        val days = listOf(day(elapsedSeconds = 7 * 3_600L + 31 * 60L, pausedSeconds = 2 * 3_600.0))
        val real = RealDurationCalculator.forDays(days)
        assertEquals(7 * 3_600L + 31 * 60L, real?.elapsedSeconds)
        assertEquals(7 * 3_600L + 31 * 60L - 2 * 3_600L, real?.walkingSeconds)
    }

    @Test
    fun uneRandoDeDeuxJoursSommeLesDeuxJoursSansCompterLaNuit() {
        val days = listOf(
            day(elapsedSeconds = 5 * 3_600L, pausedSeconds = 1 * 3_600.0, dayIndex = 0),
            day(elapsedSeconds = 4 * 3_600L, pausedSeconds = 3_000.0, dayIndex = 1),
        )
        val real = RealDurationCalculator.forDays(days)
        assertEquals(9 * 3_600L, real?.elapsedSeconds)
        assertEquals(9 * 3_600L - 3_600L - 3_000L, real?.walkingSeconds)
    }

    @Test
    fun uneRandoSansHorodatageNADAucuneDureeReelle() {
        // Un seul jour sans elapsedSeconds suffit à priver TOUTE la rando de durée réelle (brief
        // §Règles), même si l'autre jour, lui, est horodaté.
        val days = listOf(
            day(elapsedSeconds = 5 * 3_600L, pausedSeconds = 3_600.0),
            day(elapsedSeconds = null, pausedSeconds = null),
        )
        assertNull(RealDurationCalculator.forDays(days))
    }

    @Test
    fun uneListeVideNADAucuneDureeReelle() {
        assertNull(RealDurationCalculator.forDays(emptyList()))
    }

    @Test
    fun unJourHorodateSansPausedSecondsEncoreDonneLaDureeMaisPasLaMarche() {
        // Rattrapage RIC-146 lot 1 pas encore passé sur ce jour : elapsedSeconds existe (migration
        // RIC-98/99, plus ancienne) mais pausedSeconds pas encore (brief §Règles).
        val days = listOf(day(elapsedSeconds = 3 * 3_600L, pausedSeconds = null))
        val real = RealDurationCalculator.forDays(days)
        assertEquals(3 * 3_600L, real?.elapsedSeconds)
        assertNull(real?.walkingSeconds)
    }

    // --- aggregate --------------------------------------------------------------------------------

    @Test
    fun unTotalToutReelNAPasDePrefixe() {
        val items = listOf(
            RealDurationCalculator.RealDuration(3_600L, 3_000L) to 0L,
            RealDurationCalculator.RealDuration(7_200L, 6_000L) to 0L,
        )
        val aggregated = RealDurationCalculator.aggregate(items)
        assertEquals(10_800L, aggregated.totalSeconds)
        assertFalse(aggregated.isEstimated)
        assertEquals(9_000L, aggregated.walkingSeconds)
    }

    @Test
    fun unTotalMelangeantReelEtEstimePorteLePrefixeEtSommeLesDeux() {
        // Brief §Règles : "un total additionne les durées réelles des randos horodatées et les
        // estimations des autres. S'il contient au moins une estimation, il est lui aussi précédé
        // de « ≈ »."
        val items = listOf(
            RealDurationCalculator.RealDuration(3_600L, 3_000L) to 0L,
            // Rando sans horodatage : pas de RealDuration, son estimation (calibration active,
            // calculée par l'appelant) est utilisée à la place.
            null to 5_400L,
        )
        val aggregated = RealDurationCalculator.aggregate(items)
        assertEquals(3_600L + 5_400L, aggregated.totalSeconds)
        assertTrue(aggregated.isEstimated)
    }

    @Test
    fun laSecondeLigneEstMasqueeSiAucuneRandoNEstHorodatee() {
        val items = listOf(null to 3_600L, null to 7_200L)
        val aggregated = RealDurationCalculator.aggregate(items)
        assertNull(aggregated.walkingSeconds)
    }

    @Test
    fun laSecondeLigneEstMasqueeSiUnJourHorodateNAPasEncorePausedSeconds() {
        // Une seule rando horodatée du total sans temps de marche connu suffit à masquer la ligne
        // entière (brief §Règles), même si une autre rando du même total, elle, le connaît.
        val items = listOf(
            RealDurationCalculator.RealDuration(3_600L, 3_000L) to 0L,
            RealDurationCalculator.RealDuration(7_200L, null) to 0L,
        )
        val aggregated = RealDurationCalculator.aggregate(items)
        assertNull(aggregated.walkingSeconds)
        // La durée totale, elle, reste connue et réelle : seule la marche est masquée.
        assertEquals(10_800L, aggregated.totalSeconds)
        assertFalse(aggregated.isEstimated)
    }

    @Test
    fun uneListeVideDeRandosDonneUnTotalNulNonEstime() {
        val aggregated = RealDurationCalculator.aggregate(emptyList())
        assertEquals(0L, aggregated.totalSeconds)
        assertFalse(aggregated.isEstimated)
        assertNull(aggregated.walkingSeconds)
    }
}

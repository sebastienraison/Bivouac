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
        // RIC-209 (brief Partie C, lot 9) : part de marche = (élapsé - pauses) / élapsé, en %
        // entier. (7h31 - 2h) / 7h31 = 73,4 % -> 73.
        assertEquals(73, real?.walkingSharePercent)
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
        // La part de marche porte sur la SOMME des deux jours, pas la moyenne de leurs parts
        // individuelles (25 800 / 32 400 = 79,6 % -> 80).
        assertEquals(80, real?.walkingSharePercent)
    }

    @Test
    fun laPartDeMarcheDUnJourEstArrondieAuPourCentEntier() {
        // 200 / 300 = 66,666... % : vérifie l'arrondi au-dessus, distinct du cas 73/80 ci-dessus
        // (arrondi en dessous).
        val days = listOf(day(elapsedSeconds = 300L, pausedSeconds = 100.0))
        val real = RealDurationCalculator.forDays(days)
        assertEquals(67, real?.walkingSharePercent)
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
        // RIC-209 (lot 9) : pas de temps de marche connu, donc pas de pourcentage non plus.
        assertNull(real?.walkingSharePercent)
    }

    @Test
    fun uneRandoSansHorodatageNaAucunePartDeMarche() {
        // forDays() renvoie null pour toute la rando (voir uneRandoSansHorodatageNADAucuneDureeReelle) :
        // aucun RealDuration, donc aucune part de marche à calculer côté appelant (brief §Règles,
        // "sans part de marche, comme aujourd'hui").
        val days = listOf(day(elapsedSeconds = null, pausedSeconds = null))
        assertNull(RealDurationCalculator.forDays(days))
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
        // RIC-209 (lot 9) : 9 000 / 10 800 = 83,33... % -> 83.
        assertEquals(83, aggregated.walkingSharePercent)
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
        // RIC-209 (lot 9, brief §Règles) : la part de marche ne compte QUE la rando horodatée
        // (3 000 / 3 600 = 83,33... % -> 83), jamais l'estimation qui n'a pas de temps de marche.
        assertEquals(83, aggregated.walkingSharePercent)
    }

    @Test
    fun laSecondeLigneEstMasqueeSiAucuneRandoNEstHorodatee() {
        val items = listOf(null to 3_600L, null to 7_200L)
        val aggregated = RealDurationCalculator.aggregate(items)
        assertNull(aggregated.walkingSeconds)
        // RIC-209 (lot 9) : le cartouche retombe alors sur "Durée totale" (brief §Règles).
        assertNull(aggregated.walkingSharePercent)
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
        // RIC-209 (lot 9) : le cartouche retombe alors sur "Durée totale" (brief §Règles).
        assertNull(aggregated.walkingSharePercent)
    }

    @Test
    fun uneListeVideDeRandosDonneUnTotalNulNonEstime() {
        val aggregated = RealDurationCalculator.aggregate(emptyList())
        assertEquals(0L, aggregated.totalSeconds)
        assertFalse(aggregated.isEstimated)
        assertNull(aggregated.walkingSeconds)
        assertNull(aggregated.walkingSharePercent)
    }
}

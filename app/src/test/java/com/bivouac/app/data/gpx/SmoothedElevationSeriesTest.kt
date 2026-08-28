package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RIC-138 : un export GPX réel (TourInSoft notamment) peut avoir des points sans `<ele>` épars
 * dans le fichier plutôt qu'un fichier entièrement dépourvu d'altitude. Avant ce ticket,
 * [TrackStatsCalculator.smoothedElevationSeries] était tout-ou-rien (un seul point sans altitude
 * -> null -> profil vide), alors même que [TrackStatsCalculator.compute] reste correct grâce à son
 * mapNotNull. Ce fichier couvre l'interpolation qui remplace ce comportement, en ciblant
 * [TrackStatsCalculator.interpolateElevations] directement (internal) pour rester indépendant de
 * la fenêtre de lissage de smoothValues (ELEVATION_SMOOTHING_WINDOW = 5) ; une poignée de tests
 * sur [TrackStatsCalculator.smoothedElevationSeries] elle-même vérifie l'assemblage bout en bout,
 * lissage compris.
 */
class SmoothedElevationSeriesTest {

    private fun points(vararg elevations: Double?): List<TrackPoint> =
        elevations.mapIndexed { index, elevation ->
            TrackPoint(latitude = 45.0 + index * 0.001, longitude = 6.0, elevationMeters = elevation, time = null)
        }

    // --- interpolateElevations : la logique de comblement des trous, isolée du lissage ---

    @Test
    fun noGapReturnsElevationsUnchanged() {
        val result = TrackStatsCalculator.interpolateElevations(points(100.0, 150.0, 200.0))

        assertEquals(listOf(100.0, 150.0, 200.0), result)
    }

    @Test
    fun singlePointGapIsLinearlyInterpolatedFromBothNeighbours() {
        val result = TrackStatsCalculator.interpolateElevations(points(100.0, null, 200.0))

        assertEquals(listOf(100.0, 150.0, 200.0), result)
    }

    @Test
    fun multiConsecutivePointGapIsLinearlyInterpolated() {
        // 3 points manquants entre 100 et 400 -> pas régulier de 75 m par point.
        val result = TrackStatsCalculator.interpolateElevations(points(100.0, null, null, null, 400.0))

        assertEquals(listOf(100.0, 175.0, 250.0, 325.0, 400.0), result)
    }

    @Test
    fun dispersedSinglePointGapsAreEachInterpolatedIndependently() {
        val result = TrackStatsCalculator.interpolateElevations(
            points(100.0, null, 200.0, 200.0, null, 300.0, 300.0, null, 400.0),
        )

        assertEquals(listOf(100.0, 150.0, 200.0, 200.0, 250.0, 300.0, 300.0, 350.0, 400.0), result)
    }

    @Test
    fun gapAtVeryStartOfTrackExtendsFlatFromFirstKnownPoint() {
        // Aucun point connu avant le trou : pas de direction vers laquelle interpoler, donc
        // extension à plat depuis le premier point connu plutôt qu'une valeur inventée.
        val result = TrackStatsCalculator.interpolateElevations(points(null, null, 300.0, 400.0))

        assertEquals(listOf(300.0, 300.0, 300.0, 400.0), result)
    }

    @Test
    fun gapAtVeryEndOfTrackExtendsFlatFromLastKnownPoint() {
        val result = TrackStatsCalculator.interpolateElevations(points(300.0, 400.0, null, null))

        assertEquals(listOf(300.0, 400.0, 400.0, 400.0), result)
    }

    @Test
    fun gapsAtBothStartAndEndAreEachExtendedFlatFromTheirOwnSide() {
        val result = TrackStatsCalculator.interpolateElevations(points(null, 300.0, 400.0, null))

        assertEquals(listOf(300.0, 300.0, 400.0, 400.0), result)
    }

    @Test
    fun entireTrackWithoutElevationHasNothingToInterpolateFromAndReturnsNull() {
        // Comportement conservé à l'identique d'avant RIC-138 : une trace sans AUCUNE altitude
        // laisse le profil vide plutôt que d'inventer une série entière.
        val result = TrackStatsCalculator.interpolateElevations(points(null, null, null))

        assertNull(result)
    }

    @Test
    fun emptyTrackReturnsEmptyList() {
        val result = TrackStatsCalculator.interpolateElevations(emptyList())

        assertEquals(emptyList<Double>(), result)
    }

    // --- smoothedElevationSeries : assemblage bout en bout (interpolation puis lissage) ---

    @Test
    fun smoothedElevationSeriesInterpolatesInsteadOfReturningNullOnASingleGap() {
        // Avant RIC-138, un seul point sans altitude sur 1330 (cas réel TourInSoft) faisait
        // échouer toute la série -> profil totalement vide. Ce test fige la régression inverse.
        val result = TrackStatsCalculator.smoothedElevationSeries(points(100.0, null, 200.0))

        assertEquals(listOf(100.0, 150.0, 200.0), result)
    }

    @Test
    fun smoothedElevationSeriesStaysIndexAlignedWithInputPoints() {
        val input = points(100.0, null, 200.0, null, 300.0)

        val result = TrackStatsCalculator.smoothedElevationSeries(input)

        assertEquals("l'alignement d'index (bivouacs, curseur) exige le même nombre d'éléments", input.size, result?.size)
    }

    @Test
    fun smoothedElevationSeriesReturnsNullWhenNoPointHasElevation() {
        val result = TrackStatsCalculator.smoothedElevationSeries(points(null, null, null, null, null))

        assertNull(result)
    }

    @Test
    fun smoothedElevationSeriesSmoothsTheInterpolatedValuesLikeAnyOther() {
        // 6 points (>= ELEVATION_SMOOTHING_WINDOW = 5) pour que le lissage entre bien en jeu :
        // l'altitude interpolée à l'index 2 (200.0) doit participer à la moyenne glissante comme
        // n'importe quelle valeur mesurée, exactement comme l'attend l'IHM (bulle du curseur,
        // courbe) qui ne distingue pas une valeur interpolée d'une valeur mesurée.
        val input = points(100.0, 150.0, null, 250.0, 300.0, 350.0)

        val result = TrackStatsCalculator.smoothedElevationSeries(input)

        // Interpolé : [100, 150, 200, 250, 300, 350]. Moyenne glissante fenêtre 5 (2 de chaque
        // côté, tronquée aux bords) sur cette rampe régulière -> calcul à la main ci-dessous.
        assertEquals(listOf(150.0, 175.0, 200.0, 250.0, 275.0, 300.0), result)
    }
}

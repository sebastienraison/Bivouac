package com.bivouac.app.ui.map

import com.bivouac.app.data.gpx.TrackGeometry
import com.bivouac.app.data.model.TrackPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-139 (retouche recette) : reproduit puis verrouille le bug remonté en recette : « aucune
 * flèche de direction sur le détail Journal d'un trek multi-jours ».
 *
 * L'hypothèse de départ pointait le chemin de rendu multi-jours du Journal (polylignes par jour,
 * façon dont la projection reçoit les points). Elle ne tient pas : renderTrack (HikeMapView.kt)
 * appelle directionArrowMarkers avec `points`/`geoPoints` déjà concaténés sur TOUTE la trace, jour
 * après jour (voir LoggedTrackRepository.openDetail : `dayTracks.flatMap { it.points }`), et c'est
 * exactement le même appel, avec les mêmes listes, que celui utilisé pour la trace fusionnée de la
 * Planification. Rien dans ce chemin ne distingue mono-jour de multi-jours.
 *
 * La vraie cause : directionArrowMarkers ne posait des flèches que sur les traces où
 * TrackGeometry.isLoop répondait vrai (départ et arrivée à moins de 50 m l'un de l'autre),
 * restriction héritée de BIV-46. Un trek multi-jours point-à-point (qui ne revient pas à son point
 * de départ, le cas le plus courant sur plusieurs jours) tombait donc TOUJOURS hors champ, et un
 * mono-jour point-à-point aurait exactement le même sort : la césure observée en recette
 * (mono-jour OK, multi-jours KO) reflète les traces que Seb avait sous la main pour chaque cas,
 * pas une différence de code.
 *
 * Ce fichier prouve les deux faces :
 *  - [pointToPointMultiDayTrackNowGetsArrows] et [pointToPointMonoDayTrackNowGetsArrows] : avant la
 *    retouche, `directionArrowsEligible` valait `points.size >= 3 && TrackGeometry.isLoop(...)` et
 *    les DEUX étaient rejetées (le second exactement comme le premier, la preuve que ce n'est pas
 *    spécifique au multi-jours) ; elles passent la première assertion, tirée directement du symptôme
 *    signalé.
 *  - [loopTracksStayEligibleMonoOrMultiDay] : verrou de non-régression, le cas boucle (mono ou
 *    multi-jours) qui fonctionnait déjà continue de fonctionner.
 */
class DirectionArrowEligibilityTest {

    // ~111 320 m par degré de latitude à l'équateur : suffisant pour construire des distances
    // franchement au-dessus ou en dessous du seuil de boucle (50 m) sans viser l'exactitude
    // géodésique, hors de propos ici.
    private val metersPerDegreeLat = 111_320.0

    private fun point(latDeg: Double, lonDeg: Double) =
        TrackPoint(latitude = latDeg, longitude = lonDeg, elevationMeters = null, time = null)

    /** Une ligne droite de [count] points entre deux latitudes, même longitude. */
    private fun straightLine(fromLat: Double, toLat: Double, count: Int): List<TrackPoint> =
        (0 until count).map { i ->
            val t = i.toDouble() / (count - 1)
            point(fromLat + (toLat - fromLat) * t, 6.0)
        }

    @Test
    fun pointToPointMultiDayTrackNowGetsArrows() {
        // Jour 1 : parking (44.000) -> refuge (44.050), environ 5,6 km. Jour 2 : refuge -> un
        // second trailhead (44.090), environ 4,5 km plus loin -- un trek qui NE revient PAS à son
        // point de départ, le cas le plus courant sur plusieurs jours. Concaténation identique à
        // LoggedTrackRepository.openDetail : dayTracks.flatMap { it.points }.
        val day1 = straightLine(fromLat = 44.000, toLat = 44.050, count = 50)
        val day2 = straightLine(fromLat = 44.050, toLat = 44.090, count = 50)
        val multiDayTrack = day1 + day2

        val distanceStartToFinishMeters =
            (multiDayTrack.last().latitude - multiDayTrack.first().latitude) * metersPerDegreeLat
        assertTrue(
            "ce trek doit être franchement point-à-point pour reproduire le symptôme de recette",
            distanceStartToFinishMeters > 500.0,
        )
        assertFalse(
            "confirme la cause : ce trek n'est pas une boucle",
            TrackGeometry.isLoop(multiDayTrack, thresholdMeters = 50.0),
        )

        assertTrue(
            "un trek multi-jours point-à-point doit porter des flèches sur toute sa longueur",
            directionArrowsEligible(multiDayTrack),
        )
    }

    @Test
    fun pointToPointMonoDayTrackNowGetsArrows() {
        // Même geométrie que le jour 1 du test précédent, mais en un seul jour : preuve que le
        // défaut n'a jamais été spécifique au multi-jours, seulement à l'absence de boucle.
        val monoDayTrack = straightLine(fromLat = 44.000, toLat = 44.050, count = 50)

        assertFalse(TrackGeometry.isLoop(monoDayTrack, thresholdMeters = 50.0))
        assertTrue(
            "une trace mono-jour point-à-point doit, tout autant, porter des flèches",
            directionArrowsEligible(monoDayTrack),
        )
    }

    @Test
    fun loopTracksStayEligibleMonoOrMultiDay() {
        // Boucle mono-jour : va et vient au même point de départ.
        val monoDayLoop = straightLine(44.000, 44.030, 25) + straightLine(44.030, 44.0002, 25)
        assertTrue(TrackGeometry.isLoop(monoDayLoop, thresholdMeters = 50.0))
        assertTrue(directionArrowsEligible(monoDayLoop))

        // Boucle multi-jours (aller-retour sur deux jours, même trailhead) : c'est le cas qui
        // fonctionnait déjà avant la retouche, non-régression.
        val day1 = straightLine(44.000, 44.040, 30)
        val day2 = straightLine(44.040, 44.0003, 30)
        val multiDayLoop = day1 + day2
        assertTrue(TrackGeometry.isLoop(multiDayLoop, thresholdMeters = 50.0))
        assertTrue(directionArrowsEligible(multiDayLoop))
    }

    @Test
    fun tooFewPointsIsStillIneligible() {
        val twoPoints = straightLine(44.000, 44.001, 2)
        assertFalse(directionArrowsEligible(twoPoints))
    }
}

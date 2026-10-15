package com.bivouac.app.data.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * RIC-231 : le fichier réel qui a motivé le ticket, un GPX planifié exporté de Decathlon Outdoor
 * (« Balade au Refuge de Balaigt », 419 points, 12,2 km) qui ne porte aucune balise `<ele>` ni
 * `<time>`.
 *
 * Le fichier vit hors du dépôt (jamais copié ici). Son chemin est lu dans la variable
 * d'environnement `BIVOUAC_TEST_GPX_NO_ELEVATION` (ou, à défaut, la propriété système du même
 * nom) ; absente, ou pointant vers un fichier inexistant, le test s'ignore proprement (Assume),
 * comme GeneratedStringsUpToDateTest sans inventaire. Exemple :
 * `BIVOUAC_TEST_GPX_NO_ELEVATION=/chemin/vers/le.gpx ./gradlew testDebugUnitTest --tests "*DecathlonNoElevationTrackTest"`.
 */
@RunWith(RobolectricTestRunner::class)
class DecathlonNoElevationTrackTest {

    private fun gpxFile(): File? {
        val path = System.getenv(ENV_NAME) ?: System.getProperty(ENV_NAME)
        assumeNotNull("$ENV_NAME non défini : test ignoré", path)
        val file = File(path!!)
        assumeTrue("fichier absent : test ignoré", file.isFile)
        return file
    }

    @Test
    fun theDecathlonFileHasNoElevationAndIsMarkedUnavailable() {
        val file = gpxFile()!!
        val track = file.inputStream().use { GpxParser.parse(it) }
        val points = track.points

        assertEquals(419, points.size)
        assertTrue(points.all { it.elevationMeters == null })
        assertFalse(ElevationAvailability.of(points))

        val stats = TrackStatsCalculator.compute(points)

        assertFalse(stats.elevationAvailable)
        assertEquals("D+ à 0, marqué indisponible", 0.0, stats.elevationGainMeters, 0.0)
        assertEquals("D- à 0, marqué indisponible", 0.0, stats.elevationLossMeters, 0.0)
        assertTrue("distance d'environ 12 km", stats.distanceMeters in 11_000.0..13_500.0)
        assertTrue("durée estimée (à plat) non nulle", stats.estimatedDurationMinutes > 0)
        assertNull(TrackStatsCalculator.series(points).smoothedElevationMeters)
    }

    private companion object {
        const val ENV_NAME = "BIVOUAC_TEST_GPX_NO_ELEVATION"
    }
}

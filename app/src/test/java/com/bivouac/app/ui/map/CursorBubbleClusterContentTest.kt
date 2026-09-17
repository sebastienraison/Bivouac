package com.bivouac.app.ui.map

import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import com.bivouac.app.data.model.TrackPoint
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RIC-181 : un tap sur un groupe de photos (cluster, voir photoClusterMarker) doit ouvrir la bulle
 * avec le carrousel du GROUPE ENTIER, pas seulement les photos tombées dans le rayon réel de
 * CURSOR_BUBBLE_PHOTO_RADIUS_METERS autour de la première comme avant ce ticket : un cluster est un
 * regroupement en PIXELS D'ÉCRAN (voir clusterPhotos), donc à un zoom éloigné ses membres peuvent
 * être éloignés de bien plus que 20 m sur le terrain réel, c'était le bug remonté (RIC-43/RIC-139
 * l'avaient recette sans ce cas).
 *
 * cursorBubbleContent est internal pour ce test, sur le même principe que directionArrowIndices
 * (DirectionArrowIndicesTest) : la règle de construction du contenu de la bulle mérite d'être
 * vérifiable sans monter tout HikeMapView. RobolectricTestRunner seulement parce que la fonction
 * prend un android.content.Context (LoggedTrackPhotoStore.resolve en a besoin) ; aucun MapView
 * n'est impliqué.
 */
@RunWith(RobolectricTestRunner::class)
class CursorBubbleClusterContentTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    // Trace assez longue pour que deux points du "même cluster écran" soient loin l'un de l'autre
    // en mètres réels : c'est exactement le cas qui casse le rayon de 20 m.
    private val points = (0..100).map { i ->
        TrackPoint(45.0 + i * 0.001, 6.0, 1000.0, time = Instant.parse("2026-06-12T08:00:00Z").plusSeconds(i * 10L))
    }
    private val distanceCache = TrackDistanceCache()

    private fun photo(id: Long, pointIndex: Int, takenAtMillis: Long, shownOnMap: Boolean = true) =
        LoggedTrackPhotoEntity(
            id = id,
            trackId = "trace",
            filePath = "photos/photo-$id.jpg",
            addedAtMillis = takenAtMillis,
            takenAtMillis = takenAtMillis,
            positionPointIndex = pointIndex,
            shownOnMap = shownOnMap,
        )

    /**
     * Le cœur du bug : deux photos du même cluster écran, à 50 points de trace l'une de l'autre
     * (bien au-delà des 20 m du rayon habituel, voir CURSOR_BUBBLE_PHOTO_RADIUS_METERS), doivent
     * TOUTES LES DEUX apparaître dans le carrousel dès lors que le tap vient d'un cluster.
     */
    @Test
    fun unClusterMontreTousSesMembresMemeHorsDuRayonReel() {
        val near = photo(id = 1, pointIndex = 10, takenAtMillis = 1_000)
        val far = photo(id = 2, pointIndex = 60, takenAtMillis = 2_000)
        val content = cursorBubbleContent(
            context, points, index = 10, distanceCache,
            photos = listOf(near, far), missingPhotoIds = emptySet(),
            explicitPhotoIds = setOf(1L, 2L),
        )

        assertEquals(2, content.photoFiles.size)
    }

    /** L'ordre du carrousel suit PhotoDisplayOrder (chronologique), pas l'ordre du tap. */
    @Test
    fun lOrdreDuCarrouselSuitPhotoDisplayOrder() {
        val later = photo(id = 1, pointIndex = 10, takenAtMillis = 5_000)
        val earlier = photo(id = 2, pointIndex = 20, takenAtMillis = 1_000)
        val content = cursorBubbleContent(
            context, points, index = 10, distanceCache,
            photos = listOf(later, earlier), missingPhotoIds = emptySet(),
            explicitPhotoIds = setOf(1L, 2L),
        )

        assertEquals(listOf(2L, 1L), content.photoFiles.map { it.name.substringAfter("photo-").substringBefore(".jpg").toLong() })
    }

    /**
     * La photo affichée en premier est celle qui correspond au curseur ACTUEL (index), pas
     * forcément la première du groupe : après un balayage, c'est elle qui doit rester visible.
     */
    @Test
    fun laPhotoInitialeEstCelleDuCurseurActuel() {
        val first = photo(id = 1, pointIndex = 10, takenAtMillis = 1_000)
        val second = photo(id = 2, pointIndex = 20, takenAtMillis = 2_000)
        val third = photo(id = 3, pointIndex = 30, takenAtMillis = 3_000)
        val content = cursorBubbleContent(
            context, points, index = 20, distanceCache,
            photos = listOf(first, second, third), missingPhotoIds = emptySet(),
            explicitPhotoIds = setOf(1L, 2L, 3L),
        )

        assertEquals(1, content.initialPhotoIndex)
    }

    /** Les photos retirées de la carte (shownOnMap = false) n'ont jamais rejoint le cluster en amont. */
    @Test
    fun unePhotoRetireeDeLaCarteEstAbsenteDuGroupe() {
        // Simule ce que renderTrack fait déjà en amont (mapVisiblePhotos = photos.filter { it.shownOnMap }) :
        // seule la photo visible est passée ici, alors que explicitPhotoIds (capturé au tap) pouvait
        // encore porter l'ancien id si le retrait a eu lieu après coup.
        val visible = photo(id = 1, pointIndex = 10, takenAtMillis = 1_000, shownOnMap = true)
        val content = cursorBubbleContent(
            context, points, index = 10, distanceCache,
            photos = listOf(visible), missingPhotoIds = emptySet(),
            explicitPhotoIds = setOf(1L, 2L), // 2L n'existe plus dans `photos`
        )

        assertEquals(1, content.photoFiles.size)
    }

    /** Toutes les photos du groupe ont leur copie locale disparue : la bulle le dit, pas de silence. */
    @Test
    fun grroupeIntegralementAbsentLocalementDitPhotoAbsente() {
        val gone = photo(id = 1, pointIndex = 10, takenAtMillis = 1_000)
        val content = cursorBubbleContent(
            context, points, index = 10, distanceCache,
            photos = listOf(gone), missingPhotoIds = setOf(1L),
            explicitPhotoIds = setOf(1L),
        )

        assertTrue(content.photoMissing)
        assertEquals(0, content.photoFiles.size)
    }

    /**
     * RIC-181 : photoPointIndices, ce qui permet au balayage de déplacer le curseur, n'est peuplé
     * QUE pour un cluster. Le tap sur un marqueur simple (explicitPhotoIds = null) garde le
     * comportement d'avant : le balayage n'y déplace jamais le curseur (voir HikeMapView.installSwipeGesture).
     */
    @Test
    fun photoPointIndicesResteVideHorsCluster() {
        val a = photo(id = 1, pointIndex = 10, takenAtMillis = 1_000)
        val b = photo(id = 2, pointIndex = 11, takenAtMillis = 1_010)
        val content = cursorBubbleContent(
            context, points, index = 10, distanceCache,
            photos = listOf(a, b), missingPhotoIds = emptySet(),
            explicitPhotoIds = null,
        )

        assertTrue(
            "un carrousel de marqueur simple ne doit jamais déplacer le curseur au balayage",
            content.photoPointIndices.isEmpty(),
        )
    }

    @Test
    fun photoPointIndicesSuitLOrdreDuCarrouselPourUnCluster() {
        val a = photo(id = 1, pointIndex = 10, takenAtMillis = 2_000)
        val b = photo(id = 2, pointIndex = 20, takenAtMillis = 1_000)
        val content = cursorBubbleContent(
            context, points, index = 10, distanceCache,
            photos = listOf(a, b), missingPhotoIds = emptySet(),
            explicitPhotoIds = setOf(1L, 2L),
        )

        // b (id 2) est chronologiquement avant a (id 1) : elle doit être en premier, et son index
        // de trace (20) doit être le premier de photoPointIndices, dans le même ordre.
        assertEquals(listOf(20, 10), content.photoPointIndices)
    }
}

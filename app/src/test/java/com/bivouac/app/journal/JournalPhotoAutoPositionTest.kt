package com.bivouac.app.journal

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.db.LoggedTrackGpxStore
import com.bivouac.app.data.db.LoggedTrackPhotoStore
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.db.PreparedDay
import com.bivouac.app.data.db.PreparedImport
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.operations.ExclusiveOperations
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-178 : « Replacer à la position GPS » / « Replacer selon l'heure de prise de vue », option C
 * du menu Position. Même patron que JournalPhotoPlacementEditsTest (RIC-166), dont ce fichier
 * reprend la mise en place : le brouillon (`_pendingPhotoPositions`) est le même pour les deux
 * chemins (glissement manuel, retour automatique), Annuler restaure, la disquette rend définitif.
 *
 * Les photos importées par ce fixture n'ont pas d'EXIF exploitable (octets de test arbitraires,
 * voir photoUri) : leurs métadonnées d'origine (latitude/longitude/takenAtMillis) sont donc posées
 * ici à la main par .copy(), pour simuler ce qu'un EXIF réel aurait donné à l'import, exactement
 * comme PhotoPositionCorrelatorTest le fait sur des TrackPoint synthétiques.
 */
@RunWith(RobolectricTestRunner::class)
class JournalPhotoAutoPositionTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository
    private lateinit var viewModel: JournalViewModel

    private val trackId = "ric178-auto-position"

    // Trois points bien séparés : de quoi distinguer un placement manuel (index 0) d'un retour
    // automatique (index 2) sans ambiguïté de plus proche voisin.
    private val trackPoints = listOf(
        TrackPoint(45.00, 6.00, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
        TrackPoint(45.01, 6.01, 1050.0, Instant.parse("2026-06-12T08:30:00Z")),
        TrackPoint(45.02, 6.02, 1100.0, Instant.parse("2026-06-12T09:00:00Z")),
    )

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.transitDir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
        ExclusiveOperations.resetForTests()
        repository = LoggedTrackRepository(application)
    }

    @After
    fun tearDown() {
        ExclusiveOperations.resetForTests()
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.transitDir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
    }

    /** Le cas GPS : replacer une photo déplacée manuellement retrouve son point d'origine, certain. */
    @Test
    fun replacerALaPositionGpsRetrouveLIndexEtNEstJamaisApproximatif() {
        val entry = openTrack()
        val photoId = importAndSave(entry)

        // D'abord un placement manuel ailleurs, comme si l'utilisateur avait glissé le marqueur :
        // c'est ce que « replacer » doit défaire.
        viewModel.updatePhotoPlacementPosition(photoId, 0)
        settle()
        assertEquals(0, viewModel.currentPhotos.value.single().positionPointIndex)

        val photoWithGps = viewModel.currentPhotos.value.single()
            .copy(latitude = trackPoints[2].latitude, longitude = trackPoints[2].longitude, takenAtMillis = null)
        viewModel.restorePhotoAutoPosition(photoWithGps)
        settle()

        val current = viewModel.currentPhotos.value.single()
        assertEquals(2, current.positionPointIndex)
        assertFalse("un retour par GPS n'est jamais approximatif", current.positionApproximate)
        assertTrue(viewModel.photosDirty.value)
    }

    /** Le cas horodatage : sans GPS, la corrélation temporelle rend une position approximative. */
    @Test
    fun replacerSelonLHeureDePriseDeVueEstApproximatif() {
        val entry = openTrack()
        val photoId = importAndSave(entry)

        val photoWithTimestamp = viewModel.currentPhotos.value.single().copy(
            latitude = null,
            longitude = null,
            takenAtMillis = trackPoints[1].time!!.plusSeconds(60).toEpochMilli(),
        )
        viewModel.restorePhotoAutoPosition(photoWithTimestamp)
        settle()

        val current = viewModel.currentPhotos.value.single()
        assertEquals(1, current.positionPointIndex)
        assertTrue("un retour par horodatage est approximatif", current.positionApproximate)
        assertTrue(viewModel.photosDirty.value)
    }

    /** Aucune position trouvée (ni GPS, ni horodatage dans la tolérance) : aucun brouillon posé. */
    @Test
    fun aucunePositionTrouveeNeTouchePasAuBrouillon() {
        val entry = openTrack()
        val photoId = importAndSave(entry)

        val photoSansMetadata = viewModel.currentPhotos.value.single()
            .copy(latitude = null, longitude = null, takenAtMillis = null)
        viewModel.restorePhotoAutoPosition(photoSansMetadata)
        settle()

        assertFalse(viewModel.photosDirty.value)
        assertEquals(null, viewModel.currentPhotos.value.single().positionPointIndex)
    }

    /** La disquette écrit l'index ET le marquage approximatif en base, pas seulement l'index. */
    @Test
    fun laDisquetteEcritLIndexEtLApproximationEnBase() {
        val entry = openTrack()
        importAndSave(entry)

        val photoWithTimestamp = viewModel.currentPhotos.value.single().copy(
            latitude = null,
            longitude = null,
            takenAtMillis = trackPoints[1].time!!.plusSeconds(60).toEpochMilli(),
        )
        viewModel.restorePhotoAutoPosition(photoWithTimestamp)
        saveAndWait { storedPosition().first == 1 }

        val (index, approximate) = storedPosition()
        assertEquals(1, index)
        assertTrue("l'approximation doit survivre à l'enregistrement, pas seulement l'index", approximate)
    }

    /** Abandonner l'édition oublie le retour automatique, comme un glissement manuel (RIC-166). */
    @Test
    fun abandonnerLEditionOublieLeRetourAutomatique() {
        val entry = openTrack()
        importAndSave(entry)
        val positionAvant = storedPosition()

        val photoWithGps = viewModel.currentPhotos.value.single()
            .copy(latitude = trackPoints[2].latitude, longitude = trackPoints[2].longitude, takenAtMillis = null)
        viewModel.restorePhotoAutoPosition(photoWithGps)
        viewModel.discardPhotoEdits()
        settle()

        assertFalse(viewModel.photosDirty.value)
        assertEquals(positionAvant, storedPosition())
        assertEquals(positionAvant.first, viewModel.currentPhotos.value.single().positionPointIndex)
    }

    // --- Mise en place -------------------------------------------------------------------------

    private fun openTrack(): LoggedTrackEntity {
        val entry = runBlocking { createTrack() }
        viewModel = JournalViewModel(application)
        viewModel.openTrack(entry)
        waitUntil("la trace ne s'est pas ouverte") { viewModel.uiState.value is JournalUiState.Detail }
        return entry
    }

    private fun importAndSave(entry: LoggedTrackEntity): Long {
        viewModel.addPhotos(listOf(photoUri(1)))
        waitUntil("le lot n'est pas arrivé en transit") {
            viewModel.photoOperationProgress.value == null && viewModel.photosDirty.value
        }
        saveAndWait { runBlocking { repository.listPhotos(entry.id) }.size == 1 }
        return runBlocking { repository.listPhotos(entry.id) }.single().id
    }

    private fun saveAndWait(done: () -> Boolean) {
        viewModel.saveDetails(tags = emptySet(), note = "")
        waitUntil("l'enregistrement ne s'est jamais terminé", condition = done)
        waitUntil("le verrou d'exclusion n'a jamais été rendu") {
            ExclusiveOperations.current.value == null
        }
        waitUntil("le brouillon photo n'a pas été vidé") { !viewModel.photosDirty.value }
    }

    private fun storedPosition(): Pair<Int?, Boolean> =
        runBlocking { repository.listPhotos(trackId) }.single().let { it.positionPointIndex to it.positionApproximate }

    private suspend fun createTrack(): LoggedTrackEntity {
        val gpx = GpxWriter.write(trackPoints, "Trace test RIC-178")
        val entry = LoggedTrackEntity(
            id = trackId,
            name = "Trace test RIC-178",
            startedAt = 0L,
            contentHash = "hash-trace",
            distanceMeters = 1.0,
            elevationGainMeters = 2.0,
            elevationLossMeters = 3.0,
            pointCount = trackPoints.size,
            estimatedDurationMinutes = 4,
        )
        repository.commitImport(
            PreparedImport(
                entry,
                listOf(
                    PreparedDay(
                        rawGpx = gpx,
                        contentHash = "hash-jour",
                        startedAtMillis = 0L,
                        elapsedSeconds = null,
                        segmentAggregate = DaySegmentAggregate.EMPTY,
                    ),
                ),
            ),
        )
        return entry
    }

    private fun photoUri(index: Int): Uri {
        val uri = Uri.parse("content://test/photo-$index")
        val bytes = ByteArray(2_048) { (index * 13 + it).toByte() }
        shadowOf(application.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    private fun settle() {
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitUntil(message: String, timeoutMillis: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        if (!condition()) org.junit.Assert.fail(message)
    }
}

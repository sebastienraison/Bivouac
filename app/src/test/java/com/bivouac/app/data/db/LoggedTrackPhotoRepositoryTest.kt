package com.bivouac.app.data.db

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.model.TrackPoint
import java.io.ByteArrayInputStream
import java.io.IOException
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
 * RIC-43 : ce que le socle photos garantit côté repository, sur la même infrastructure Robolectric
 * que RepositoryBackupCycleTest (vrai Context, vraie base SQLite, vrais fichiers, sans appareil).
 *
 * Les octets « photo » sont des suites d'octets quelconques : rien ici ne décode d'image, ni le
 * repository (copie brute) ni la déduplication (SHA-256 des octets). Une vraie photo n'apporterait
 * que du bruit — la vérification sur de vraies photos se fait en session device.
 */
@RunWith(RobolectricTestRunner::class)
class LoggedTrackPhotoRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository

    // Même raison que RepositoryBackupCycleTest : le singleton de base survit d'un test à l'autre
    // alors que Robolectric change de sandbox.
    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        context.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(context).deleteRecursively()
        LoggedTrackPhotoStore.transitDir(context).deleteRecursively()
        LoggedTrackGpxStore.dir(context).deleteRecursively()
        repository = LoggedTrackRepository(context)
    }

    @After
    fun tearDown() {
        BivouacDatabase.closeAndReset()
        context.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(context).deleteRecursively()
        LoggedTrackPhotoStore.transitDir(context).deleteRecursively()
        LoggedTrackGpxStore.dir(context).deleteRecursively()
    }

    private val trackId = "ric43-track"

    private suspend fun createTrack() {
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test photos",
        )
        repository.commitImport(
            PreparedImport(
                LoggedTrackEntity(
                    id = trackId,
                    name = "Trace test photos",
                    startedAt = 0L,
                    contentHash = "hash-trace",
                    distanceMeters = 1.0,
                    elevationGainMeters = 2.0,
                    elevationLossMeters = 3.0,
                    pointCount = 2,
                    estimatedDurationMinutes = 4,
                ),
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
    }

    // registerInputStreamSupplier et non registerInputStream : addPhotosFromPicker ouvre chaque Uri
    // deux fois (empreinte, puis copie), un flux unique serait déjà épuisé au deuxième passage.
    private fun registerPhoto(name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/$name")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    private fun registerUnreadablePhoto(name: String): Uri {
        val uri = Uri.parse("content://test/$name")
        // Ce que fait une Uri de sélecteur révoquée entre la sélection et l'ajout.
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { throw IOException("Uri révoquée") }
        return uri
    }

    private fun photoFiles(): List<String> =
        LoggedTrackPhotoStore.dir(context).listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()

    /**
     * RIC-149 : le cycle complet « sélection puis sauvegarde », en un appel — ce que fait le mode
     * édition quand l'utilisateur ajoute des photos puis tape la disquette. Les tests ci-dessous
     * portent sur le résultat de ce cycle ; les étapes séparées (transit, abandon, restauration)
     * ont leur propre suite, LoggedTrackPhotoTransactionTest.
     */
    private suspend fun addPhotos(uris: List<Uri>): PhotoAddReport {
        val batch = repository.stagePhotosFromPicker(trackId, context.contentResolver, uris)
        repository.commitPendingPhotos(trackId, batch.staged)
        return batch.report
    }

    @Test
    fun addPhotosFromPicker_skipsTheSamePhotoTwiceInOneBatch() = runBlocking {
        createTrack()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val first = registerPhoto("a", bytes)
        // Uri différente, contenu identique : exactement ce que MediaStore peut présenter pour
        // la même photo touchée deux fois, d'où la déduplication par contenu et non par Uri.
        val second = registerPhoto("b", bytes.copyOf())

        val report = addPhotos(listOf(first, second))

        assertEquals(1, report.added)
        assertEquals(1, report.duplicatesSkipped)
        assertEquals(0, report.failed)
        assertEquals(1, repository.listPhotos(trackId).size)
        assertEquals(1, photoFiles().size)
    }

    @Test
    fun addPhotosFromPicker_skipsAPhotoAlreadyAddedInAPreviousBatch() = runBlocking {
        createTrack()
        val bytes = byteArrayOf(9, 9, 9)
        addPhotos(listOf(registerPhoto("a", bytes)))

        val report = addPhotos(listOf(registerPhoto("b", bytes.copyOf())))

        assertEquals(0, report.added)
        assertEquals(1, report.duplicatesSkipped)
        assertEquals(1, repository.listPhotos(trackId).size)
        assertEquals(1, photoFiles().size)
    }

    // Le lot ne s'arrête pas au premier échec : c'est ce qui distingue une photo perdue d'une
    // sélection entière perdue.
    @Test
    fun addPhotosFromPicker_countsAnUnreadableUriAsFailedAndKeepsGoing() = runBlocking {
        createTrack()
        val uris = listOf(
            registerPhoto("a", byteArrayOf(1)),
            registerUnreadablePhoto("boom"),
            registerPhoto("c", byteArrayOf(3)),
        )

        val report = addPhotos(uris)

        assertEquals(2, report.added)
        assertEquals(0, report.duplicatesSkipped)
        assertEquals(1, report.failed)
        assertEquals(2, repository.listPhotos(trackId).size)
        assertEquals("aucun fichier orphelin ne doit rester", 2, photoFiles().size)
    }

    /**
     * RIC-149 : la progression de l'import, celle qui alimente le compteur du dialogue bloquant.
     *
     * Une photo traitée est une avancée, quel qu'en soit le sort : le lot contient donc à dessein
     * une photo ordinaire, un doublon (écarté) et une Uri illisible (échec), et le compteur doit
     * passer par 1, 2 puis 3 malgré cela. Compter les seules photos retenues ferait stagner le
     * dialogue sur une sélection pleine de doublons, alors que le travail coûteux (l'empreinte)
     * a bel et bien été fait pour chacune.
     */
    @Test
    fun stagePhotosFromPicker_reportsOneStepPerPhotoIncludingDuplicatesAndFailures() = runBlocking {
        createTrack()
        val bytes = byteArrayOf(7, 7, 7, 7)
        val uris = listOf(
            registerPhoto("a", bytes),
            registerPhoto("a-bis", bytes.copyOf()),
            registerUnreadablePhoto("boom"),
        )
        val steps = mutableListOf<Int>()

        val batch = repository.stagePhotosFromPicker(
            trackId,
            context.contentResolver,
            uris,
            onProgress = { done -> steps += done },
        )

        assertEquals("un pas par photo du lot, dans l'ordre", listOf(1, 2, 3), steps)
        assertEquals(1, batch.report.added)
        assertEquals(1, batch.report.duplicatesSkipped)
        assertEquals(1, batch.report.failed)
    }

    /**
     * L'ordre « fichier d'abord, ligne ensuite » et son rollback, vérifiés ensemble : l'insert est
     * mis en échec par une contrainte de clé étrangère (trace inexistante), et ce qui doit rester
     * derrière est exactement rien.
     *
     * C'est aussi ce qui prouve l'ordre : si la ligne était écrite avant le fichier, il n'y aurait
     * rien à retirer ici, et l'échec inverse (fichier impossible à écrire) laisserait une ligne
     * pointant vers un fichier absent.
     */
    @Test
    fun commitPendingPhotos_removesTheJustWrittenFileWhenTheInsertFails() = runBlocking {
        createTrack()
        val staged = repository.stagePhotosFromPicker(
            trackId,
            context.contentResolver,
            listOf(registerPhoto("orpheline", byteArrayOf(7, 7, 7))),
        ).staged

        // Le commit vise une trace qui n'existe pas : la contrainte de clé étrangère refuse
        // l'insert, après que le fichier a déjà rejoint filesDir.
        val failures = repository.commitPendingPhotos("trace-qui-n-existe-pas", staged)

        assertEquals("l'insert doit échouer sur la clé étrangère", 1, failures)
        assertEquals("le fichier tout juste écrit doit être retiré", emptyList<String>(), photoFiles())
        assertEquals(0, repository.listPhotos(trackId).size)
    }

    @Test
    fun addPhoto_writesTheFileBeforeTheRowSoEveryStoredRowHasItsFile() = runBlocking {
        createTrack()
        val uri = registerPhoto("a", byteArrayOf(4, 5, 6))

        addPhotos(listOf(uri))

        val photo = repository.listPhotos(trackId).single()
        val file = LoggedTrackPhotoStore.resolve(context, photo.filePath)
        assertTrue("la ligne stockée doit pointer vers un fichier existant", file.exists())
        assertTrue(byteArrayOf(4, 5, 6).contentEquals(file.readBytes()))
        assertEquals(emptySet<Long>(), repository.missingPhotoFileIds(listOf(photo)))
    }

    // La FK CASCADE emporte les lignes ; les fichiers, eux, ne se suppriment pas tout seuls — c'est
    // delete() qui relève les chemins AVANT le DELETE pour pouvoir le faire.
    @Test
    fun deletingATrackAlsoDeletesItsPhotoFiles() = runBlocking {
        createTrack()
        addPhotos(listOf(registerPhoto("a", byteArrayOf(1)), registerPhoto("b", byteArrayOf(2))))
        assertEquals(2, photoFiles().size)

        repository.delete(trackId)

        assertEquals(0, repository.listPhotos(trackId).size)
        assertEquals("les fichiers doivent partir avec la trace", emptyList<String>(), photoFiles())
    }

    // Une ligne dont le fichier a disparu doit être détectée, et surtout pas supprimée : ses
    // métadonnées serviront à la re-acquisition depuis la galerie (RIC-151).
    @Test
    fun missingPhotoFileIds_reportsRowsWhoseFileIsGoneWithoutRemovingThem() = runBlocking {
        createTrack()
        addPhotos(listOf(registerPhoto("a", byteArrayOf(1))))
        val photo = repository.listPhotos(trackId).single()
        assertTrue(LoggedTrackPhotoStore.resolve(context, photo.filePath).delete())

        val missing = repository.missingPhotoFileIds(repository.listPhotos(trackId))

        assertEquals(setOf(photo.id), missing)
        assertFalse("la ligne doit survivre à la disparition de son fichier", repository.listPhotos(trackId).isEmpty())
    }
}

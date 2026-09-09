package com.bivouac.app.data.db

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.photo.PhotoContentHash
import com.bivouac.app.data.photo.PhotoOriginalResolution
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-157 : la résolution de l'original en deux temps, exercée de bout en bout sur une vraie base,
 * un vrai ContentResolver (celui de Robolectric) et de vrais octets.
 *
 * Ce qui compte ici et qu'aucun test pur ne peut montrer : c'est l'EMPREINTE qui tranche, jamais
 * l'URI ni les métadonnées. Un URI qui pointe désormais sur une autre photo doit être rejeté, et un
 * candidat qui porte le bon nom mais pas le bon contenu aussi.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoOriginalResolverTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository

    private val trackId = "ric157-resolution"
    private val originalBytes = ByteArray(1_024) { (it * 3).toByte() }
    private val otherBytes = ByteArray(1_024) { (it * 5 + 1).toByte() }

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackGpxStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        repository = LoggedTrackRepository(application)
        grantGalleryPermission()
    }

    @After
    fun tearDown() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackGpxStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
    }

    /**
     * Premier temps : l'URI mémorisé est encore bon. Une seule ouverture, aucune requête, et rien
     * n'est réécrit en base puisque rien n'a changé.
     */
    @Test
    fun theRememberedUriIsUsedFirstAndConfirmedByItsHash() = runBlocking {
        val photo = insertPhoto(lastResolvedUri = "content://test/original")
        registerBytes("content://test/original", originalBytes)

        val resolution = repository.resolvePhotoOriginal(photo)

        assertEquals(
            PhotoOriginalResolution.Found(Uri.parse("content://test/original"), uriRefreshed = false),
            resolution,
        )
        assertEquals("content://test/original", storedUri())
    }

    /**
     * L'URI mémorisé désigne désormais une AUTRE photo, ce qu'une réindexation de la galerie produit
     * pour de bon. Les métadonnées seules diraient « c'est elle » ; l'empreinte dit non, et la
     * recherche profonde retrouve la vraie, dont l'URI est alors mémorisé.
     */
    @Test
    fun aStaleUriPointingAtAnotherPhotoIsRejectedAndTheDeepSearchTakesOver() = runBlocking {
        val photo = insertPhoto(lastResolvedUri = "content://test/original")
        registerBytes("content://test/original", otherBytes)
        // Deux entrées galerie portant le MÊME nom que la photo cherchée : les métadonnées ne
        // peuvent pas les départager, seule l'empreinte le peut.
        insertGalleryPhoto("IMG_0001.jpg", 1780300850000L, otherBytes)
        val real = insertGalleryPhoto("IMG_0001.jpg", 1780300850000L, originalBytes)

        val resolution = repository.resolvePhotoOriginal(photo)

        assertEquals(PhotoOriginalResolution.Found(real, uriRefreshed = true), resolution)
        assertEquals(
            "l'URI retrouvé doit être mémorisé, pour que la fois suivante s'arrête au premier temps",
            real.toString(),
            storedUri(),
        )
    }

    /** URI mort (l'entrée MediaStore a disparu) : même chemin, la recherche profonde rattrape. */
    @Test
    fun aDeadUriFallsThroughToTheDeepSearch() = runBlocking {
        val photo = insertPhoto(lastResolvedUri = "content://test/disparue")
        shadowOf(application.contentResolver)
            .registerInputStreamSupplier(Uri.parse("content://test/disparue")) { throw IOException("URI révoquée") }
        // Renommée depuis l'import : c'est la date de prise de vue, seconde requête, qui la
        // retrouve. La première (par nom) ne rend rien, et c'est bien pour ça qu'il y en a deux.
        val real = insertGalleryPhoto("IMG_0001_copie.jpg", 1780300850000L, originalBytes)

        val resolution = repository.resolvePhotoOriginal(photo)

        assertEquals(PhotoOriginalResolution.Found(real, uriRefreshed = true), resolution)
        assertEquals(real.toString(), storedUri())
    }

    /**
     * Aucun candidat ne porte le bon contenu : « pas trouvée », et surtout rien n'est rendu au
     * hasard. C'est ce qui distingue ce composant d'une recherche par métadonnées.
     */
    @Test
    fun candidatesThatDoNotHashRightAreNeverReturned() = runBlocking {
        val photo = insertPhoto(lastResolvedUri = null)
        insertGalleryPhoto("IMG_0001.jpg", 1780300850000L, otherBytes)
        insertGalleryPhoto("IMG_0001.jpg", 1780300850000L, otherBytes)

        assertEquals(PhotoOriginalResolution.NotFound, repository.resolvePhotoOriginal(photo))
        assertEquals("rien ne doit être mémorisé sur un échec", null, storedUri())
    }

    /**
     * Sans permission galerie : « indisponible », et aucune demande n'est déclenchée.
     *
     * La permission photos est opt-in dans cette app : une résolution qui la réclamerait d'elle-même
     * la transformerait en permission subie, alors même que ce composant sera appelé depuis des
     * chemins où l'utilisateur ne demandait rien de tel.
     */
    @Test
    fun withoutTheGalleryPermission_itAnswersUnavailableWithoutAsking() = runBlocking {
        revokeGalleryPermission()
        val photo = insertPhoto(lastResolvedUri = "content://test/original")
        registerBytes("content://test/original", originalBytes)

        assertEquals(PhotoOriginalResolution.Unavailable, repository.resolvePhotoOriginal(photo))
    }

    /**
     * Ni URI mémorisé, ni métadonnée d'origine : rien à interroger, donc « pas trouvée ». Aucun
     * parcours de pellicule, même en dernier recours : c'est la garantie testée en détail par
     * PhotoOriginalLookupTest, vérifiée ici de bout en bout.
     */
    @Test
    fun withNothingToSearchOn_itGivesUpRatherThanScanningTheGallery() = runBlocking {
        val photo = insertPhoto(lastResolvedUri = null, displayName = null, dateTakenMillis = null)
        insertGalleryPhoto("IMG_0001.jpg", 1780300850000L, originalBytes)

        assertTrue(
            "la photo est pourtant là, mais rien ne permettait de la cibler",
            repository.resolvePhotoOriginal(photo) == PhotoOriginalResolution.NotFound,
        )
    }

    // --- Mise en place -------------------------------------------------------------------------

    private suspend fun insertPhoto(
        lastResolvedUri: String?,
        displayName: String? = "IMG_0001.jpg",
        dateTakenMillis: Long? = 1780300850000L,
    ): LoggedTrackPhotoEntity {
        createTrack()
        val dao = BivouacDatabase.getInstance(application).loggedTrackDao()
        dao.insertPhoto(
            LoggedTrackPhotoEntity(
                trackId = trackId,
                filePath = "photos/$trackId-copie.jpg",
                addedAtMillis = 1780300900000L,
                contentHash = PhotoContentHash.of(ByteArrayInputStream(originalBytes)),
                sourceDisplayName = displayName,
                sourceDateTakenMillis = dateTakenMillis,
                lastResolvedUri = lastResolvedUri,
            ),
        )
        return repository.listPhotos(trackId).single()
    }

    private fun storedUri(): String? = runBlocking { repository.listPhotos(trackId) }.single().lastResolvedUri

    private fun registerBytes(uri: String, bytes: ByteArray) {
        shadowOf(application.contentResolver)
            .registerInputStreamSupplier(Uri.parse(uri)) { ByteArrayInputStream(bytes) }
    }

    /**
     * Une vraie entrée dans le MediaStore de Robolectric, qui est un fournisseur SQLite complet :
     * les sélections de la recherche profonde sont donc réellement évaluées, pas simulées par un
     * curseur qui rendrait tout quoi qu'on lui demande.
     */
    private fun insertGalleryPhoto(displayName: String?, dateTakenMillis: Long?, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            displayName?.let { put(MediaStore.MediaColumns.DISPLAY_NAME, it) }
            dateTakenMillis?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
        }
        val uri = requireNonNull(application.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        registerBytes(uri.toString(), bytes)
        return uri
    }

    private fun requireNonNull(uri: Uri?): Uri = uri ?: error("MediaStore n'a pas accepté l'insertion")

    private fun grantGalleryPermission() {
        shadowOf(application).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
    }

    private fun revokeGalleryPermission() {
        shadowOf(application).denyPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
    }

    private suspend fun createTrack() {
        if (repository.list().any { it.id == trackId }) return
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test résolution",
        )
        repository.commitImport(
            PreparedImport(
                LoggedTrackEntity(
                    id = trackId,
                    name = "Trace test résolution",
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
}

package com.bivouac.app.data.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.db.LoggedTrackGpxStore
import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import com.bivouac.app.data.db.LoggedTrackPhotoStore
import com.bivouac.app.data.db.PlanificationGpxStore
import com.bivouac.app.data.photo.PhotoStorageMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RIC-140 : le relevé d'espace, sur de vrais fichiers dans un vrai filesDir (Robolectric), parce
 * que c'est exactement ce qu'il mesure.
 *
 * Les lignes photo sont construites à la main plutôt que passées par le repository : ce composant
 * ne lit pas la base, il reçoit les lignes. Ce qui l'intéresse d'elles, c'est leur chemin et leur
 * mode de stockage, et rien d'autre.
 */
@RunWith(RobolectricTestRunner::class)
class AppStorageUsageCalculatorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = cleanDirectories()

    @After
    fun tearDown() = cleanDirectories()

    private fun cleanDirectories() {
        LoggedTrackGpxStore.dir(context).deleteRecursively()
        PlanificationGpxStore.dir(context).deleteRecursively()
        LoggedTrackPhotoStore.dir(context).deleteRecursively()
    }

    private fun writeFile(directory: File, name: String, size: Int) {
        directory.mkdirs()
        File(directory, name).writeBytes(ByteArray(size))
    }

    private fun photoRow(
        id: Long,
        fileName: String,
        storageMode: PhotoStorageMode,
        lastResolvedUri: String? = "content://media/external/images/media/$id",
    ) = LoggedTrackPhotoEntity(
        id = id,
        trackId = "trace",
        filePath = "${LoggedTrackPhotoStore.DIR_NAME}/$fileName",
        addedAtMillis = 0L,
        contentHash = "hash-$id",
        storageMode = storageMode,
        lastResolvedUri = lastResolvedUri,
    )

    @Test
    fun eachPostIsMeasuredOnItsOwnDirectory() = runBlocking {
        writeFile(LoggedTrackGpxStore.dir(context), "trace-day0.gpx", 4_000)
        writeFile(LoggedTrackGpxStore.dir(context), "trace-day1.gpx", 6_000)
        writeFile(PlanificationGpxStore.dir(context), "banked-a.gpx", 1_000)

        val usage = AppStorageUsageCalculator.compute(context, photos = emptyList())

        assertEquals(10_000L, usage.gpxJournalBytes)
        assertEquals(2, usage.gpxJournalFileCount)
        assertEquals(1_000L, usage.gpxPlanificationBytes)
        assertEquals(1, usage.gpxPlanificationFileCount)
        assertEquals(11_000L, usage.gpxBytes)
    }

    @Test
    fun photosAreSplitByStorageMode() = runBlocking {
        writeFile(LoggedTrackPhotoStore.dir(context), "archive.jpg", 4_000_000)
        writeFile(LoggedTrackPhotoStore.dir(context), "reduite.jpg", 500_000)
        val photos = listOf(
            photoRow(1L, "archive.jpg", PhotoStorageMode.FULL),
            photoRow(2L, "reduite.jpg", PhotoStorageMode.REDUCED),
        )

        val usage = AppStorageUsageCalculator.compute(context, photos)

        assertEquals(1, usage.photos.fullCount)
        assertEquals(4_000_000L, usage.photos.fullBytes)
        assertEquals(1, usage.photos.reducedCount)
        assertEquals(500_000L, usage.photos.reducedBytes)
        assertEquals(2, usage.photos.count)
        assertEquals(0, usage.photos.missingCount)
    }

    /**
     * Une ligne dont le fichier a disparu pèse zéro octet, ce qui est exact, et elle est comptée à
     * part : c'est ce qui empêche l'écran d'annoncer moins de photos que le Journal n'en montre.
     */
    @Test
    fun aRowWhoseFileIsGoneCountsAsMissingAndWeighsNothing() = runBlocking {
        val photos = listOf(photoRow(1L, "disparue.jpg", PhotoStorageMode.FULL))

        val usage = AppStorageUsageCalculator.compute(context, photos)

        assertEquals(1, usage.photos.missingCount)
        assertEquals(0L, usage.photos.fullBytes)
        assertNull("une photo sans fichier n'a rien à recompresser", usage.recompression)
    }

    /**
     * Le poste « Photos » part du dossier et non de la somme des lignes : un fichier orphelin
     * occupe bel et bien de la place, et l'écran ne doit pas annoncer moins que ce que le système
     * facture à l'app.
     */
    @Test
    fun theTotalCountsAnOrphanFileThatNoRowClaims() = runBlocking {
        writeFile(LoggedTrackPhotoStore.dir(context), "reduite.jpg", 500_000)
        writeFile(LoggedTrackPhotoStore.dir(context), "orpheline.jpg", 300_000)
        val photos = listOf(photoRow(1L, "reduite.jpg", PhotoStorageMode.REDUCED))

        val usage = AppStorageUsageCalculator.compute(context, photos)

        assertEquals(800_000L, usage.photos.directoryBytes)
        assertEquals(500_000L, usage.photos.reducedBytes)
    }

    @Test
    fun theEstimateOnlyCountsFullCopiesThatHaveSomethingToSearchWith() = runBlocking {
        writeFile(LoggedTrackPhotoStore.dir(context), "avec-uri.jpg", 4_000_000)
        writeFile(LoggedTrackPhotoStore.dir(context), "sans-rien.jpg", 4_000_000)
        val photos = listOf(
            photoRow(1L, "avec-uri.jpg", PhotoStorageMode.FULL),
            photoRow(2L, "sans-rien.jpg", PhotoStorageMode.FULL, lastResolvedUri = null),
        )

        val usage = AppStorageUsageCalculator.compute(context, photos)

        assertEquals("une seule des deux est retrouvable", 1, usage.recompression?.photoCount)
        assertNotNull(usage.recompression)
    }

    // Le total est la somme des postes affichés : sans cette égalité, l'écran présenterait une
    // décomposition qui ne décompose rien.
    @Test
    fun theTotalIsExactlyTheSumOfTheDisplayedPosts() = runBlocking {
        writeFile(LoggedTrackGpxStore.dir(context), "trace-day0.gpx", 4_000)
        writeFile(LoggedTrackPhotoStore.dir(context), "reduite.jpg", 500_000)

        val usage = AppStorageUsageCalculator.compute(context, listOf(photoRow(1L, "reduite.jpg", PhotoStorageMode.REDUCED)))

        assertEquals(
            usage.gpxBytes + usage.photos.directoryBytes + usage.databaseBytes + usage.otherBytes,
            usage.totalBytes,
        )
        assertTrue("le total doit au moins porter ce qu'on vient d'écrire", usage.totalBytes >= 504_000L)
    }
}

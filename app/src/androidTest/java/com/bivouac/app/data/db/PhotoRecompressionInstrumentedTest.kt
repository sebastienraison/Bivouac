package com.bivouac.app.data.db

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.photo.PhotoContentHash
import com.bivouac.app.data.photo.PhotoStoragePolicy
import com.bivouac.app.data.photo.PhotoStorageMode
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RIC-157 : la recompression du stock, pixels compris et fichiers échangés pour de bon.
 *
 * Instrumenté et non JVM, pour la même raison que PhotoReducerInstrumentedTest : Robolectric ne
 * décode aucune image, donc la branche qui compte ici, celle qui réencode et remplace le fichier,
 * ne peut être atteinte que sur un vrai décodeur. PhotoRecompressionPassTest couvre le reste de la
 * mécanique sans appareil.
 *
 * Données synthétiques uniquement, comme BackupManagerTest : ce test écrase la base de
 * l'application sur l'appareil de test, il n'a rien à faire ailleurs que sur l'émulateur jetable
 * des Gradle Managed Devices.
 */
@RunWith(AndroidJUnit4::class)
class PhotoRecompressionInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var repository: LoggedTrackRepository
    private val workDir = File(context.cacheDir, "recompression-test")

    private val trackId = "ric157-recompression-device"

    @Before
    fun setUp() {
        grantGalleryPermission()
        BivouacDatabase.closeAndReset()
        context.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackGpxStore.dir(context).deleteRecursively()
        LoggedTrackPhotoStore.dir(context).deleteRecursively()
        workDir.deleteRecursively()
        workDir.mkdirs()
        repository = LoggedTrackRepository(context)
    }

    @After
    fun tearDown() {
        BivouacDatabase.closeAndReset()
        context.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackGpxStore.dir(context).deleteRecursively()
        LoggedTrackPhotoStore.dir(context).deleteRecursively()
        workDir.deleteRecursively()
    }

    /**
     * Le cas nominal, et le seul qui libère de la place : l'original est retrouvé, confirmé par son
     * empreinte, et la copie intégrale locale est remplacée par une copie réduite.
     *
     * Ce qui est vérifié au-delà du gain : la ligne pointe vers un NOUVEAU fichier qui existe,
     * l'ancien a disparu, et le mode est passé REDUCED. Autrement dit, à aucun moment la ligne
     * n'aura désigné un fichier absent.
     */
    @Test
    fun aFullCopyWhoseOriginalIsConfirmedIsReplacedByAReducedOneAndFreesSpace() = runBlocking {
        val original = writeSourceJpeg("original.jpg", 4032, 3024)
        val photo = insertPhoto(original)
        val oldFile = LoggedTrackPhotoStore.resolve(context, photo.filePath)
        val sizeBefore = oldFile.length()

        val report = repository.recompressFullPhotos()

        assertEquals(1, report.recompressed)
        assertEquals(0, report.kept)
        assertTrue("la recompression doit libérer de la place", report.freedBytes > 0L)

        val after = repository.listPhotos(trackId).single()
        assertEquals(PhotoStorageMode.REDUCED, after.storageMode)
        assertNotEquals("le fichier doit être un nouveau, l'extension pouvant changer", photo.filePath, after.filePath)
        val newFile = LoggedTrackPhotoStore.resolve(context, after.filePath)
        assertTrue("la ligne doit désigner un fichier existant", newFile.isFile)
        assertFalse("l'ancien fichier doit être retiré une fois la ligne basculée", oldFile.exists())
        assertEquals(sizeBefore - newFile.length(), report.freedBytes)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        newFile.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        assertEquals(PhotoStoragePolicy.REDUCED_LONG_SIDE_PX, bounds.outWidth)

        // L'empreinte porte toujours les octets d'ORIGINE : c'est elle qui permettra de retrouver
        // l'original la prochaine fois (visionneuse, RIC-151). La recalculer sur la copie réduite
        // couperait ce lien pour toujours.
        assertEquals(photo.contentHash, after.contentHash)
    }

    /**
     * L'original a changé depuis l'import (recompressé par un service de sauvegarde photo, cas
     * réel) : l'empreinte ne correspond plus, et la copie intégrale locale reste intacte, jusqu'à
     * son dernier octet.
     */
    @Test
    fun aModifiedOriginalLeavesTheArchiveCopyStrictlyIntact() = runBlocking {
        val original = writeSourceJpeg("original.jpg", 4032, 3024)
        val photo = insertPhoto(original)
        val localFile = LoggedTrackPhotoStore.resolve(context, photo.filePath)
        val bytesBefore = localFile.readBytes()
        // L'URI mémorisé désigne toujours quelque chose, mais ce n'est plus la même photo.
        writeSourceJpeg("original.jpg", 3000, 2000)

        val report = repository.recompressFullPhotos()

        assertEquals(0, report.recompressed)
        assertEquals(1, report.kept)
        val after = repository.listPhotos(trackId).single()
        assertEquals(PhotoStorageMode.FULL, after.storageMode)
        assertEquals(photo.filePath, after.filePath)
        assertTrue(bytesBefore.contentEquals(localFile.readBytes()))
    }

    // --- Mise en place -------------------------------------------------------------------------

    private fun writeSourceJpeg(name: String, width: Int, height: Int): File {
        val file = File(workDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    /**
     * Une ligne en copie intégrale dont le fichier local est l'original octet pour octet : c'est
     * exactement ce que produisait l'import avant RIC-157, et c'est le stock que cette passe existe
     * pour reprendre.
     */
    private suspend fun insertPhoto(original: File): LoggedTrackPhotoEntity {
        createTrack()
        val relativePath = LoggedTrackPhotoStore.relativePath(trackId, "jpg")
        val localFile = LoggedTrackPhotoStore.resolve(context, relativePath)
        localFile.parentFile?.mkdirs()
        original.copyTo(localFile, overwrite = true)
        val dao = BivouacDatabase.getInstance(context).loggedTrackDao()
        dao.insertPhoto(
            LoggedTrackPhotoEntity(
                trackId = trackId,
                filePath = relativePath,
                addedAtMillis = 1780300900000L,
                contentHash = original.inputStream().use { PhotoContentHash.of(it) },
                sourceDisplayName = original.name,
                sourceDateTakenMillis = 1780300850000L,
                storageMode = PhotoStorageMode.FULL,
                lastResolvedUri = Uri.fromFile(original).toString(),
            ),
        )
        return repository.listPhotos(trackId).single()
    }

    // grantRuntimePermission plutôt qu'une GrantPermissionRule : la règle vit dans
    // androidx.test:rules, que ce module n'embarque pas, et l'automation d'instrumentation fait
    // exactement la même chose sans dépendance supplémentaire.
    private fun grantGalleryPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        runCatching { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission) }
    }

    private suspend fun createTrack() {
        if (repository.list().any { it.id == trackId }) return
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test recompression",
        )
        repository.commitImport(
            PreparedImport(
                LoggedTrackEntity(
                    id = trackId,
                    name = "Trace test recompression",
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

package com.bivouac.app.journal

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.LoggedTrackGpxStore
import com.bivouac.app.data.db.LoggedTrackPhotoStore
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.operations.ExclusiveOperation
import com.bivouac.app.data.operations.ExclusiveOperations
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-158 : l'import Journal (mono-fichier, multi-fichiers « sorties séparées », et la reprise
 * après avertissement de doublon) entre au registre d'exclusion mutuelle : jusqu'ici seules les
 * opérations photo et la sauvegarde/restauration (RIC-156) en faisaient partie, alors que l'import
 * Journal écrit dans gpx/ comme les deux : même risque de chevauchement avec une sauvegarde en
 * cours de zip ou une restauration qui remplace le répertoire en bloc.
 *
 * Même infrastructure Robolectric que JournalPhotoCommitRaceTest (vrai Context, vraie base, vrais
 * fichiers), avec pilotage du looper principal.
 */
@RunWith(RobolectricTestRunner::class)
class JournalGpxImportExclusionTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository
    private lateinit var viewModel: JournalViewModel

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackPhotoStore.transitDir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
        ExclusiveOperations.resetForTests()
        repository = LoggedTrackRepository(application)
        viewModel = JournalViewModel(application)
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

    /**
     * Le refus doit être propre et ne rien consommer : aucune ligne, aucun fichier écrit, et le
     * verrou de l'opération déjà en vol reste tel quel : un refus tardif d'une coroutine ne doit
     * jamais libérer le verrou de quelqu'un d'autre (même garantie que ExclusiveOperationsTest,
     * exercée ici depuis un vrai point d'entrée d'écran).
     */
    @Test
    fun monoFileImportIsRefusedCleanlyWhileAnotherOperationIsInFlight() {
        assertTrue(ExclusiveOperations.tryStart(ExclusiveOperation.BACKUP))

        viewModel.importTracks(listOf(registerGpx("solo")))
        idle()

        assertEquals(
            "le refus doit être annoncé à l'écran",
            true,
            viewModel.importError.value?.contains("une sauvegarde") ?: false,
        )
        assertEquals("rien n'a dû être importé", 0, runBlocking { repository.list() }.size)
        assertEquals(
            "le verrou de l'opération déjà en vol ne doit pas bouger",
            ExclusiveOperation.BACKUP,
            ExclusiveOperations.current.value,
        )
    }

    /** Une fois l'obstacle levé, l'import mono-fichier doit repasser normalement. */
    @Test
    fun monoFileImportSucceedsAndReleasesTheLockOnceDone() {
        viewModel.importTracks(listOf(registerGpx("solo")))
        idle()

        assertNull("le refus ne doit rien avoir posé", viewModel.importError.value)
        assertEquals("la sortie doit être importée", 1, runBlocking { repository.list() }.size)
        assertNull(
            "le verrou doit être relâché une fois l'import terminé",
            ExclusiveOperations.current.value,
        )
    }

    /**
     * RIC-158 : le verrou du lot « sorties séparées » est pris une seule fois pour tout le lot
     * (chooseSeparateImports), pas fichier par fichier : un refus à l'entrée doit donc laisser le
     * lot entier de côté, rien de partiel.
     */
    @Test
    fun separateImportsBatchIsRefusedCleanlyWhileAnotherOperationIsInFlight() {
        assertTrue(ExclusiveOperations.tryStart(ExclusiveOperation.RESTORE))

        viewModel.importTracks(listOf(registerGpx("un"), registerGpx("deux")))
        viewModel.chooseSeparateImports()
        idle()

        assertEquals(
            "le refus doit être annoncé à l'écran",
            true,
            viewModel.importError.value?.contains("une restauration") ?: false,
        )
        assertEquals("rien n'a dû être importé", 0, runBlocking { repository.list() }.size)
        assertEquals(
            ExclusiveOperation.RESTORE,
            ExclusiveOperations.current.value,
        )
    }

    @Test
    fun separateImportsBatchSucceedsAndReleasesTheLockOnceDone() {
        viewModel.importTracks(listOf(registerGpx("un"), registerGpx("deux")))
        viewModel.chooseSeparateImports()
        idle()

        assertNull(viewModel.importError.value)
        assertEquals(2, runBlocking { repository.list() }.size)
        assertNull(
            "le verrou doit être relâché une fois le lot terminé",
            ExclusiveOperations.current.value,
        )
    }

    // --- Mise en place -------------------------------------------------------------------------

    private fun registerGpx(seed: String): Uri {
        val uri = Uri.parse("content://test/gpx-$seed")
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test $seed",
        )
        val bytes = gpx.toByteArray(StandardCharsets.UTF_8)
        shadowOf(application.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    private fun idle(timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (viewModel.importProgress.value == null) {
                shadowOf(Looper.getMainLooper()).idle()
                return
            }
            Thread.sleep(5)
        }
        fail("l'import ne s'est jamais terminé")
    }
}

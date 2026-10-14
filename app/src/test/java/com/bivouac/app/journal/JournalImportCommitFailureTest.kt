package com.bivouac.app.journal

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.R
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.LoggedTrackGpxStore
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.operations.ExclusiveOperations
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-227 (défaut relevé au lot 3) : un échec de [LoggedTrackRepository.commitImport] pendant un
 * import d'une sortie seule, ou pendant la reprise après un avertissement de doublon, est rapporté
 * comme les autres échecs d'import au lieu de remonter non rattrapé dans viewModelScope.
 *
 * L'échec est provoqué sans rien simuler : un fichier ordinaire occupe la place du répertoire gpx/,
 * l'écriture du GPX importé échoue donc pour de bon. Même infrastructure Robolectric que
 * JournalGpxImportExclusionTest.
 */
@RunWith(RobolectricTestRunner::class)
class JournalImportCommitFailureTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository
    private lateinit var viewModel: JournalViewModel

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
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
        LoggedTrackGpxStore.dir(application).deleteRecursively()
    }

    private fun blockGpxDirectory() {
        val dir = LoggedTrackGpxStore.dir(application)
        dir.deleteRecursively()
        dir.writeText("pas un répertoire")
    }

    @Test
    fun aFailedCommitOfASingleFileImportIsReportedNotThrown() {
        blockGpxDirectory()

        viewModel.importTracks(listOf(registerGpx("solo", 45.01)))
        waitUntil { viewModel.importError.value != null && viewModel.importProgress.value == null }

        assertEquals(application.getString(R.string.journal_error_track_unreadable), viewModel.importError.value)
        assertEquals(0, runBlocking { repository.list() }.size)
        assertNull("le verrou doit être relâché", ExclusiveOperations.current.value)
    }

    @Test
    fun aFailedCommitAfterTheDuplicateWarningIsReportedNotThrown() {
        viewModel.importTracks(listOf(registerGpx("premier", 45.01)))
        waitUntil { runBlocking { repository.list() }.size == 1 && viewModel.importProgress.value == null }

        // Même départ, distance à moins de 5 %, contenu différent : doublon probable.
        viewModel.importTracks(listOf(registerGpx("second", 45.0101)))
        waitUntil { viewModel.duplicateWarning.value != null }

        blockGpxDirectory()
        viewModel.confirmImportAnyway()
        waitUntil { viewModel.importError.value != null }

        assertEquals(application.getString(R.string.journal_error_track_unreadable), viewModel.importError.value)
        assertEquals(1, runBlocking { repository.list() }.size)
        assertNull(viewModel.importProgress.value)
        waitUntil { ExclusiveOperations.current.value == null }
    }

    // --- Mise en place -------------------------------------------------------------------------

    private fun registerGpx(seed: String, arrivalLatitude: Double): Uri {
        val uri = Uri.parse("content://test/gpx-$seed")
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(arrivalLatitude, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test $seed",
        )
        val bytes = gpx.toByteArray(StandardCharsets.UTF_8)
        shadowOf(application.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    private fun waitUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                return
            }
            Thread.sleep(5)
        }
        fail("condition jamais atteinte")
    }
}

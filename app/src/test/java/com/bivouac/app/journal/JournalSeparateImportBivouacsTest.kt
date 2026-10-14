package com.bivouac.app.journal

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.LoggedTrackGpxStore
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.gpx.BIVOUAC_REVIEW_VERSION
import com.bivouac.app.data.operations.ExclusiveOperations
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Duration
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
 * RIC-227 (décision de Seb du 15/10) : un import en sorties séparées pose les questions « Bivouac
 * détecté dans « nom » » APRÈS son rapport, pour les randos du lot restées à la version 0, et
 * applique les réponses rando par rando. Vraie base, vrais fichiers (Robolectric), même
 * infrastructure que JournalGpxImportExclusionTest.
 */
@RunWith(RobolectricTestRunner::class)
class JournalSeparateImportBivouacsTest {

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

    @Test
    fun questionsComeAfterTheReportWithTheTrackNameAndAreAppliedTrackByTrack() {
        viewModel.importTracks(
            listOf(
                registerGpx("jour", halfDays = 1, start = Instant.parse("2026-06-01T08:00:00Z")),
                registerGpx("nuit-a", halfDays = 2, start = Instant.parse("2026-06-02T15:00:00Z")),
                registerGpx("nuit-b", halfDays = 3, start = Instant.parse("2026-06-10T15:00:00Z")),
            ),
        )
        viewModel.chooseSeparateImports()
        waitUntil { viewModel.separateImportReport.value != null }

        // Pendant le rapport : aucune question ; la rando sans nuit est déjà examinée.
        assertNull(viewModel.bivouacQuestion.value)
        val byName = runBlocking { repository.list() }.associateBy { it.name }
        assertEquals(BIVOUAC_REVIEW_VERSION, byName.getValue("Trace jour").bivouacReviewVersion)
        assertEquals(0, byName.getValue("Trace nuit-a").bivouacReviewVersion)
        assertEquals(0, byName.getValue("Trace nuit-b").bivouacReviewVersion)

        viewModel.dismissSeparateImportReport()
        val first = viewModel.bivouacQuestion.value!!
        assertEquals("Trace nuit-a", first.trackName)
        assertEquals(1 to 3, first.number to first.total)

        // Dernière (et seule) question de nuit-a : appliquée aussitôt, sans attendre nuit-b.
        viewModel.answerBivouacQuestion(BivouacReview.Answer.SPLIT)
        val second = viewModel.bivouacQuestion.value!!
        assertEquals("Trace nuit-b", second.trackName)
        assertEquals(2 to 3, second.number to second.total)
        val nuitA = byName.getValue("Trace nuit-a").id
        waitUntil { runBlocking { repository.get(nuitA) }!!.bivouacReviewVersion == BIVOUAC_REVIEW_VERSION }
        assertEquals(2, runBlocking { repository.daysWithPoints(nuitA) }.first.size)
        assertEquals(0, runBlocking { repository.get(byName.getValue("Trace nuit-b").id) }!!.bivouacReviewVersion)

        viewModel.answerBivouacQuestion(BivouacReview.Answer.KEEP)
        assertEquals(3, viewModel.bivouacQuestion.value!!.number)
        viewModel.answerBivouacQuestion(BivouacReview.Answer.SPLIT)
        assertNull(viewModel.bivouacQuestion.value)
        val nuitB = byName.getValue("Trace nuit-b").id
        waitUntil { runBlocking { repository.get(nuitB) }!!.bivouacReviewVersion == BIVOUAC_REVIEW_VERSION }
        // Gardée à la première nuit, découpée à la seconde : deux jours.
        assertEquals(2, runBlocking { repository.daysWithPoints(nuitB) }.first.size)
    }

    // --- Mise en place -------------------------------------------------------------------------

    /** [halfDays] demi-journées de quinze points (30 m, 27 s), séparées par une nuit de 15 h. */
    private fun registerGpx(seed: String, halfDays: Int, start: Instant): Uri {
        val gpx = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\"><trk><name>Trace $seed</name><trkseg>\n")
            val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)
            var north = 0.0
            var time = start
            var index = 0
            repeat(halfDays) { half ->
                if (half > 0) {
                    north += 141.0
                    time = time.plus(Duration.ofHours(15))
                }
                repeat(15) { i ->
                    if (i > 0) {
                        north += 30.0
                        time = time.plusSeconds(27)
                    }
                    append("<trkpt lat=\"${45.0 + north * degPerMeter}\" lon=\"6.0\"><ele>${1000 + 2 * index}</ele><time>$time</time></trkpt>\n")
                    index++
                }
            }
            append("</trkseg></trk></gpx>")
        }
        val uri = Uri.parse("content://test/gpx-$seed")
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

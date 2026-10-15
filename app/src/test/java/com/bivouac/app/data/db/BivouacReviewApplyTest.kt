package com.bivouac.app.data.db

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.gpx.BIVOUAC_REVIEW_VERSION
import com.bivouac.app.data.gpx.DetectedBivouac
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
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

/**
 * RIC-227 : écriture du crible des bivouacs. [LoggedTrackRepository.commitImportReviewed] (import
 * puis découpage, tout-ou-rien) et [LoggedTrackRepository.applyBivouacReview] (crible du premier
 * lancement et action de menu). Fichier de trois demi-journées séparées par deux nuits de 15 h.
 */
@RunWith(RobolectricTestRunner::class)
class BivouacReviewApplyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dao get() = BivouacDatabase.getInstance(context).loggedTrackDao()
    private val repository get() = LoggedTrackRepository(context)
    private lateinit var source: File

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        source = File(context.cacheDir, "deux-nuits.gpx").apply { writeText(gpx(3, Instant.parse("2026-06-02T15:00:00Z"))) }
    }

    @After
    fun tearDown() {
        source.delete()
        LoggedTrackGpxStore.dir(context).deleteRecursively()
        BivouacDatabase.closeAndReset()
    }

    /** [halfDays] demi-journées de quinze points (30 m, 27 s), séparées par une nuit de 15 h reprise à 141 m. */
    private fun gpx(halfDays: Int, start: Instant): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\"><trk><name>Deux nuits</name><trkseg>\n")
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

    private suspend fun prepare(): PreparedImport =
        repository.prepareImport(context.contentResolver, listOf(Uri.fromFile(source)))

    @Test
    fun theImportCarriesTheBivouacsOfItsFile() = runBlocking {
        val prepared = prepare()
        assertEquals(listOf(14, 29), prepared.days.single().bivouacs.map { it.stopIndex })
    }

    @Test
    fun splitAtTheFirstNightOnlyGivesTwoDaysAndTheReviewVersion() = runBlocking {
        val prepared = prepare()
        val first = prepared.days.single().bivouacs.first()
        val id = repository.commitImportReviewed(prepared, mapOf(0 to listOf(first)), BIVOUAC_REVIEW_VERSION)

        val days = dao.getDays(id)
        assertEquals(listOf(0 to 14, 15 to 44), days.map { it.firstPointIndex to it.lastPointIndex })
        assertEquals(BIVOUAC_REVIEW_VERSION, dao.get(id)!!.bivouacReviewVersion)
        assertEquals(1, days.map { it.rawGpxFilePath }.distinct().size)
    }

    @Test
    fun noSplitStoresTheVersionWithTheImport() = runBlocking {
        val id = repository.commitImportReviewed(prepare(), emptyMap(), BIVOUAC_REVIEW_VERSION)
        val days = dao.getDays(id)
        assertEquals(1, days.size)
        assertNull(days.single().firstPointIndex)
        assertEquals(BIVOUAC_REVIEW_VERSION, dao.get(id)!!.bivouacReviewVersion)
    }

    @Test
    fun aFailedSplitRemovesTheImportEntirely() = runBlocking {
        val prepared = prepare()
        val outside = DetectedBivouac(400, 401, Instant.EPOCH, Instant.EPOCH.plusSeconds(20_000), ZoneId.of("UTC"))
        try {
            repository.commitImportReviewed(prepared, mapOf(0 to listOf(outside)), BIVOUAC_REVIEW_VERSION)
            fail("Un bivouac hors du jour doit faire échouer l'import")
        } catch (expected: IllegalArgumentException) {
            // attendu
        }
        assertNull(dao.get(prepared.entity.id))
        assertTrue(dao.getDays(prepared.entity.id).isEmpty())
        assertTrue(LoggedTrackGpxStore.dir(context).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun applyReviewSplitsByDayIdAndMarksEvenWithoutSplit() = runBlocking {
        val id = repository.commitImport(prepare())
        assertEquals(0, dao.get(id)!!.bivouacReviewVersion)
        assertEquals(1, repository.countTracksNeedingBivouacReview(BIVOUAC_REVIEW_VERSION))

        val (days, points) = repository.daysWithPoints(id)
        val bivouacs = com.bivouac.app.journal.BivouacReview.detectDays(points).single()
        repository.applyBivouacReview(id, mapOf(days.single().id to bivouacs), BIVOUAC_REVIEW_VERSION)

        assertEquals(3, dao.getDays(id).size)
        assertEquals(BIVOUAC_REVIEW_VERSION, dao.get(id)!!.bivouacReviewVersion)
        assertEquals(0, repository.countTracksNeedingBivouacReview(BIVOUAC_REVIEW_VERSION))

        val other = repository.commitImport(prepare().let { it.copy(entity = it.entity.copy(contentHash = "autre")) })
        repository.markBivouacReviewed(other, BIVOUAC_REVIEW_VERSION)
        assertEquals(1, dao.getDays(other).size)
        assertEquals(BIVOUAC_REVIEW_VERSION, dao.get(other)!!.bivouacReviewVersion)
    }

    // --- RIC-229 : réponse « Pause » mémorisée, chiffres recalculés -------------------------------------

    @Test
    fun aPauseAnswerAtImportIsStoredAndTheDayFiguresIncludeTheNight() = runBlocking {
        val prepared = prepare()
        val (first, second) = prepared.days.single().bivouacs
        val asBivouacs = prepared.days.single()
        val id = repository.commitImportReviewed(prepared, emptyMap(), BIVOUAC_REVIEW_VERSION, mapOf(0 to listOf(first)))

        val day = dao.getDays(id).single()
        assertEquals("14", day.refusedBivouacStops)
        assertEquals(BIVOUAC_REVIEW_VERSION, dao.get(id)!!.bivouacReviewVersion)
        // La première nuit (15 h) passe dans l'écoulé et dans la pause ; la seconde reste un bivouac.
        assertEquals(asBivouacs.elapsedSeconds!! + 15 * 3_600, day.elapsedSeconds)
        assertTrue(day.pausedSeconds!! >= 15 * 3_600.0)
        assertTrue(asBivouacs.pausedSeconds!! < 3_600.0)
        // Distance du trou comptée (141 m), plus de coupure de série à cet arrêt.
        assertEquals(asBivouacs.distanceMeters!! + 141.0, day.distanceMeters!!, 0.5)
        assertEquals(day.distanceMeters!!, dao.get(id)!!.distanceMeters, 1e-6)
        // L'écran ne voit plus que le second arrêt comme bivouac (carte, duplication).
        assertEquals(listOf(second.stopIndex), repository.openDetail(id)!!.bivouacStopIndices)
    }

    @Test
    fun aPauseIsKeptWhenAnotherStopOfTheSameDayIsSplit() = runBlocking {
        val id = repository.commitImport(prepare())
        val day = dao.getDays(id).single()
        val (first, second) = repository.daysWithPoints(id).second.let { com.bivouac.app.journal.BivouacReview.detectDays(it).single() }
        // Crible : « Pause » à la seconde nuit, puis, plus tard, « Bivouac » à la première.
        assertTrue(repository.applyBivouacReview(id, emptyMap(), BIVOUAC_REVIEW_VERSION, mapOf(day.id to listOf(second))))
        assertEquals(setOf(29), dao.getDays(id).single().refusedStops())

        assertTrue(repository.applyBivouacReview(id, mapOf(day.id to listOf(first)), BIVOUAC_REVIEW_VERSION))
        val days = dao.getDays(id).sortedBy { it.dayIndex }
        assertEquals(listOf(0 to 14, 15 to 44), days.map { it.firstPointIndex to it.lastPointIndex })
        assertEquals(listOf(emptySet(), setOf(14)), days.map { it.refusedStops() })
        assertTrue(days[1].pausedSeconds!! >= 15 * 3_600.0)
    }

    @Test
    fun answeringBivouacFromTheMenuToARefusedNightRemovesTheRefusalAndSplits() = runBlocking {
        val id = repository.commitImport(prepare())
        val day = dao.getDays(id).single()
        val both = com.bivouac.app.journal.BivouacReview.detectDays(repository.daysWithPoints(id).second).single()
        repository.applyBivouacReview(id, emptyMap(), BIVOUAC_REVIEW_VERSION, mapOf(day.id to both))
        assertEquals(setOf(14, 29), dao.getDays(id).single().refusedStops())

        // Action de menu : les deux nuits reproposées, marquées refusées ; « Bivouac » à la première,
        // « Pause » à la seconde.
        val (days, points) = repository.daysWithPoints(id)
        val result = com.bivouac.app.journal.BivouacReview.menuResult(points, refusedStopsByDay = days.map { it.refusedStops() })
        val questions = (result as com.bivouac.app.journal.BivouacReview.MenuResult.Ask).questions
        assertEquals(listOf(true, true), questions.map { it.wasRefused })
        val outcome = com.bivouac.app.journal.BivouacReview.outcome(
            questions,
            listOf(com.bivouac.app.journal.BivouacReview.Answer.SPLIT, com.bivouac.app.journal.BivouacReview.Answer.PAUSE),
        )
        val ids = days.map { it.id }
        repository.applyBivouacReview(
            id,
            outcome.splits.mapKeys { (position, _) -> ids[position] },
            outcome.reviewVersion,
            outcome.pauses.mapKeys { (position, _) -> ids[position] },
        )

        val after = dao.getDays(id).sortedBy { it.dayIndex }
        assertEquals(2, after.size)
        assertEquals(listOf(emptySet(), setOf(14)), after.map { it.refusedStops() })
        // Le premier jour, découpé, n'a plus la nuit : ni écoulée ni en pause.
        assertTrue(after[0].elapsedSeconds!! < 3_600)
    }

    @Test
    fun noChangeWritesNothingButTheVersion() = runBlocking {
        val id = repository.commitImport(prepare())
        val before = dao.getDays(id).single()
        val second = com.bivouac.app.journal.BivouacReview.detectDays(repository.daysWithPoints(id).second).single()[1]
        repository.applyBivouacReview(id, emptyMap(), BIVOUAC_REVIEW_VERSION, mapOf(before.id to listOf(second)))
        val refused = dao.getDays(id).single()
        // Même réponse une seconde fois : rien ne change, rien n'est réécrit.
        assertTrue(!repository.applyBivouacReview(id, emptyMap(), BIVOUAC_REVIEW_VERSION, mapOf(before.id to listOf(second))))
        assertEquals(refused, dao.getDays(id).single())
    }
}

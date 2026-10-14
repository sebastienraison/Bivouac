package com.bivouac.app.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.gpx.DayBivouacs
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.DetectedBivouac
import com.bivouac.app.data.gpx.TrackStatsParameters
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RIC-226 : [LoggedTrackRepository.splitDay], le découpage transactionnel d'un jour à ses bivouacs.
 * Plages contiguës du même fichier, rangs renumérotés, colonnes et rythme recalculés par le même code
 * que le rattrapage, totaux de la rando = somme des jours, photos intactes, refus sans écriture.
 *
 * Rando de trois fichiers : un jour ordinaire, un fichier qui contient [NIGHTS] nuits au bivouac
 * (reprises à 141 m), un second jour ordinaire. Rangée puis rattrapée comme une rando réelle d'avant
 * ce ticket (LoggedTrackBackfill), puis découpée.
 */
@RunWith(RobolectricTestRunner::class)
class DaySplitTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dao get() = BivouacDatabase.getInstance(context).loggedTrackDao()
    private val repository get() = LoggedTrackRepository(context)

    @Before
    fun resetSingleton() {
        BivouacDatabase.closeAndReset()
    }

    @After
    fun tearDown() {
        LoggedTrackGpxStore.dir(context).deleteRecursively()
        BivouacDatabase.closeAndReset()
    }

    /**
     * [halfDays] demi-journées de quinze points (30 m, 27 s), chacune séparée de la suivante par une
     * nuit de 15 h reprise à 141 m ; altitude montant de 2 m par point. Point de départ décalé de
     * [latOffsetDeg] et date [start] pour que chaque fichier ait son propre contenu.
     */
    private fun gpx(halfDays: Int, start: Instant, latOffsetDeg: Double): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\"><trk><name>t</name><trkseg>\n")
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
                append("<trkpt lat=\"${45.0 + latOffsetDeg + north * degPerMeter}\" lon=\"6.0\"><ele>${1000 + 2 * index}</ele><time>$time</time></trkpt>\n")
                index++
            }
        }
        append("</trkseg></trk></gpx>")
    }

    private val firstGpx = gpx(1, Instant.parse("2026-06-01T08:00:00Z"), 0.0)
    private val nightGpx = gpx(NIGHTS + 1, Instant.parse("2026-06-02T15:00:00Z"), 0.01)
    private val lastGpx = gpx(1, Instant.parse("2026-06-06T08:00:00Z"), 0.02)

    /** Rando de trois fichiers, rattrapée jusqu'à la version courante comme au premier lancement. */
    private suspend fun insertTrek(trackId: String = "trek") {
        dao.insertTrack(
            LoggedTrackEntity(
                id = trackId, name = "Trek", startedAt = 1234L, contentHash = "hash-$trackId",
                distanceMeters = 0.0, elevationGainMeters = 0.0, elevationLossMeters = 0.0,
                pointCount = 15 * (NIGHTS + 3), estimatedDurationMinutes = 0,
                statsVersion = TrackStatsParameters.ALGORITHM_VERSION - 1,
            ),
        )
        LoggedTrackGpxStore.dir(context).mkdirs()
        val days = listOf(firstGpx, nightGpx, lastGpx).mapIndexed { index, gpx ->
            val path = LoggedTrackGpxStore.relativePath(trackId, index)
            LoggedTrackGpxStore.resolve(context, path).writeText(gpx, StandardCharsets.UTF_8)
            LoggedTrackDayEntity(trackId = trackId, dayIndex = index, rawGpxFilePath = path)
        }
        dao.insertDays(days)
        LoggedTrackBackfill.run(context, dao)
        LoggedTrackBackfill.runElevation(context, dao)
        LoggedTrackBackfill.runStats(context, dao)
    }

    private suspend fun nightDay(trackId: String = "trek") = dao.getDays(trackId).single { it.dayIndex == 1 }

    private suspend fun detectedOn(day: LoggedTrackDayEntity): List<DetectedBivouac> =
        DayBivouacs.detect(DayPoints.read(context, day))

    @Test
    fun aDayIsReplacedByContiguousRangesOfTheSameFile() = runBlocking {
        insertTrek()
        val before = dao.getDays("trek")
        val night = nightDay()
        val bivouacs = detectedOn(night)
        assertEquals(listOf(14, 29), bivouacs.map { it.stopIndex })
        assertTrue("la nuit est déjà hors de la durée du jour entier", night.elapsedSeconds!! < 4 * 3_600)

        repository.splitDay("trek", night.id, bivouacs)

        val after = dao.getDays("trek")
        assertEquals(listOf(0, 1, 2, 3, 4), after.map { it.dayIndex })
        val (first, a, b, c, last) = after
        assertEquals(before[0].copy(dayIndex = 0), first)
        assertEquals(before[2].copy(dayIndex = 4), last)
        assertEquals(listOf(0 to 14, 15 to 29, 30 to 44), listOf(a, b, c).map { it.firstPointIndex to it.lastPointIndex })
        for (piece in listOf(a, b, c)) {
            assertEquals(night.rawGpxFilePath, piece.rawGpxFilePath)
            assertEquals(night.contentHash, piece.contentHash)
            assertEquals(14 * 27L, piece.elapsedSeconds)
            assertEquals(14 * 30.0, piece.distanceMeters!!, 0.5)
            assertTrue(piece.elevationBackfilled)
            assertTrue(piece.stoppedHours != null && piece.pausedSeconds != null)
            assertTrue("chaque nouveau jour a son rythme", dao.getDayPaces(piece.id).isNotEmpty())
        }
        assertTrue("le rythme du jour découpé est parti avec lui", dao.getDayPaces(night.id).isEmpty())
        assertEquals(Instant.parse("2026-06-02T15:00:00Z").plusSeconds(14 * 27L).plus(Duration.ofHours(15)).toEpochMilli(), b.startedAtMillis)
        assertEquals(1000.0 + 2 * 29, b.maxElevationMeters!!, 0.0)
        assertEquals(1000.0 + 2 * 29, b.lastPointElevationMeters!!, 0.0)
    }

    @Test
    fun theSplitDaysHaveTheFiguresOfTheSameFileImportedAsSeparateDays() = runBlocking {
        insertTrek()
        val night = nightDay()
        repository.splitDay("trek", night.id, detectedOn(night))

        // Mêmes colonnes que le rattrapage d'un jour qui serait cette plage : même calcul.
        val pieces = dao.getDays("trek").filter { it.rawGpxFilePath == night.rawGpxFilePath }
        val expectedEach = com.bivouac.app.data.gpx.JournalDayStats.of(DayPoints.read(context, pieces[1]))
        assertEquals(expectedEach.elapsedSeconds, pieces[1].elapsedSeconds)
        assertEquals(expectedEach.sums.pausedSeconds, pieces[1].pausedSeconds!!, 1e-12)
        assertEquals(expectedEach.sums.aggregate.flatHours, pieces[1].flatHours!!, 1e-12)
        assertEquals(expectedEach.stats.elevationGainMeters, pieces[1].elevationGainMeters!!, 1e-12)
        assertEquals(expectedEach.sums.paceBands, dao.getDayPaces(pieces[1].id).map { com.bivouac.app.data.gpx.PaceBandSum(it.band, it.segmentCount, it.distanceMeters, it.movingSeconds) })
    }

    @Test
    fun trackTotalsAreTheSumOfTheDaysAndStartAndPointCountDoNotMove() = runBlocking {
        insertTrek()
        val trackBefore = dao.get("trek")!!
        val night = nightDay()
        repository.splitDay("trek", night.id, detectedOn(night))

        val track = dao.get("trek")!!
        val days = dao.getDays("trek")
        assertEquals(days.sumOf { it.distanceMeters!! }, track.distanceMeters, 1e-6)
        assertEquals(days.sumOf { it.elevationGainMeters!! }, track.elevationGainMeters, 1e-6)
        assertEquals(days.sumOf { it.elevationLossMeters!! }, track.elevationLossMeters, 1e-6)
        // Les nuits étaient déjà hors des cumuls (reprise à 141 m) : découper ne change pas le total.
        assertEquals(trackBefore.distanceMeters, track.distanceMeters, 1e-6)
        assertEquals(trackBefore.estimatedDurationMinutes, track.estimatedDurationMinutes)
        assertEquals(trackBefore.startedAt, track.startedAt)
        assertEquals(trackBefore.pointCount, track.pointCount)
        assertEquals(trackBefore.contentHash, track.contentHash)
        assertEquals(TrackStatsParameters.ALGORITHM_VERSION, track.statsVersion)
        // La calibration voit les mêmes sommes : la nuit n'y était déjà pas.
        val aggregate = repository.calibrationSamples(setOf("trek")).aggregate
        assertTrue(aggregate != DaySegmentAggregate.EMPTY)
        assertTrue("aucune nuit dans les heures à l'arrêt", aggregate.stoppedHours < 0.5)
    }

    @Test
    fun photosStayOnTheSamePointsBecauseTheConcatenatedTrackIsUnchanged() = runBlocking {
        insertTrek()
        listOf(3, 20, 40, 55).forEachIndexed { i, index ->
            dao.insertPhoto(
                LoggedTrackPhotoEntity(
                    trackId = "trek", filePath = "photos/p$i.jpg", addedAtMillis = i.toLong(),
                    positionPointIndex = index, contentHash = "photo-$i",
                ),
            )
        }
        val pointsBefore = repository.open("trek")!!.points
        val photosBefore = dao.getPhotos("trek")

        val night = nightDay()
        repository.splitDay("trek", night.id, detectedOn(night))

        assertEquals(pointsBefore, repository.open("trek")!!.points)
        assertEquals(photosBefore, dao.getPhotos("trek"))
        val detail = repository.openDetail("trek")!!
        assertEquals(5, detail.daySegments.size)
        assertEquals(pointsBefore, detail.track.points)
        // Plus aucune nuit à l'intérieur d'un jour : chacune est devenue une jonction.
        assertTrue(detail.bivouacStopIndices.isEmpty())
    }

    @Test
    fun aRangeDayCanBeSplitAgainWithIndicesRelativeToThatDay() = runBlocking {
        insertTrek()
        val night = nightDay()
        // Première réponse : découper à la première nuit seulement. Le second jour garde la seconde
        // nuit, hors de sa durée.
        repository.splitDay("trek", night.id, detectedOn(night).take(1))
        val second = dao.getDays("trek").single { it.dayIndex == 2 }
        assertEquals(15 to 44, second.firstPointIndex to second.lastPointIndex)
        assertEquals(2 * 14 * 27L, second.elapsedSeconds)
        val remaining = detectedOn(second)
        assertEquals("index du jour, pas du fichier", listOf(14), remaining.map { it.stopIndex })

        repository.splitDay("trek", second.id, remaining)

        val pieces = dao.getDays("trek").filter { it.rawGpxFilePath == night.rawGpxFilePath }
        assertEquals(listOf(0 to 14, 15 to 29, 30 to 44), pieces.map { it.firstPointIndex to it.lastPointIndex })
        assertEquals(listOf(0, 1, 2, 3, 4), dao.getDays("trek").map { it.dayIndex })
    }

    @Test
    fun invalidBivouacsAreRefusedWithoutWritingAnything() = runBlocking {
        insertTrek()
        val night = nightDay()
        val valid = detectedOn(night)
        val daysBefore = dao.getDays("trek")
        val trackBefore = dao.get("trek")!!
        val zone = valid.first().zone
        fun bivouac(stop: Int, resume: Int) = DetectedBivouac(stop, resume, Instant.EPOCH, Instant.EPOCH, zone)

        val refused = listOf(
            "aucun bivouac" to emptyList(),
            "au-delà du dernier point" to listOf(bivouac(44, 45)),
            "arrêt négatif" to listOf(bivouac(-1, 0)),
            "reprise qui ne suit pas l'arrêt" to listOf(bivouac(10, 12)),
            "chevauchement" to listOf(valid[0], valid[0]),
        )
        for ((label, bivouacs) in refused) {
            try {
                repository.splitDay("trek", night.id, bivouacs)
                fail("$label : découpage accepté")
            } catch (expected: IllegalArgumentException) {
                // attendu
            }
            assertEquals(label, daysBefore, dao.getDays("trek"))
            assertEquals(label, trackBefore, dao.get("trek"))
        }
        try {
            repository.splitDay("trek", 999_999L, valid)
            fail("jour inconnu accepté")
        } catch (expected: IllegalArgumentException) {
            // attendu
        }
        assertEquals(daysBefore, dao.getDays("trek"))
    }

    @Test
    fun aTrackNotYetBackfilledIsRefused() = runBlocking {
        insertTrek()
        dao.markStatsVersion("trek", TrackStatsParameters.ALGORITHM_VERSION - 1)
        val night = nightDay()
        try {
            repository.splitDay("trek", night.id, detectedOn(night))
            fail("rando pas encore rattrapée découpée")
        } catch (expected: IllegalStateException) {
            // attendu
        }
        assertEquals(3, dao.getDays("trek").size)
    }

    @Test
    fun sharedDayWarningCountsFilesNotRowsAfterASplit() = runBlocking {
        insertTrek()
        val night = nightDay()
        repository.splitDay("trek", night.id, detectedOn(night))

        // Lot entrant : le fichier découpé (trois lignes en base, un seul fichier) plus un inconnu.
        val unknown = gpx(1, Instant.parse("2026-07-01T08:00:00Z"), 0.5)
        fun preparedDay(raw: String) = PreparedDay(
            rawGpx = raw, contentHash = sha256(raw), startedAtMillis = null, elapsedSeconds = null,
            segmentAggregate = DaySegmentAggregate.EMPTY,
        )
        val incoming = PreparedImport(
            entity = LoggedTrackEntity(
                id = "incoming", name = "Entrant", startedAt = 0L,
                contentHash = "other-hash", distanceMeters = 1.0, elevationGainMeters = 0.0, elevationLossMeters = 0.0,
                pointCount = 1, estimatedDurationMinutes = 1,
            ),
            days = listOf(preparedDay(nightGpx), preparedDay(unknown)),
        )

        val match = repository.findDuplicate(incoming)
        assertTrue("$match", match is DuplicateMatch.SharedDay)
        match as DuplicateMatch.SharedDay
        assertEquals(1, match.sharedDays)
        assertEquals(2, match.incomingDays)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val NIGHTS = 2
    }
}

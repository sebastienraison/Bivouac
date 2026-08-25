package com.bivouac.app.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.gpx.TrackSegmenter
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RIC-109 : LoggedTrackBackfill étendu pour calculer les sept sommes de segments (voir
 * DaySegmentAggregate) dans la même passe que contentHash/startedAtMillis/elapsedSeconds
 * (RIC-98/99), plutôt que dans un second rattrapage séparé — voir la kdoc de LoggedTrackBackfill
 * pour ce choix. Sans test fonctionnel dédié préexistant pour ce backfill (seule sa forme au
 * niveau schéma est couverte par BivouacDatabaseMigrationTest.migrate8To9) : celui-ci verrouille
 * son comportement, avant et après RIC-109, dont le point le plus important — une ligne déjà
 * rattrapée par RIC-98/99 (contentHash non nul) doit quand même repasser par le rattrapage pour
 * recevoir les colonnes de segments, voir [LoggedTrackDao.getDaysNeedingBackfill].
 */
@RunWith(RobolectricTestRunner::class)
class LoggedTrackBackfillTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dao get() = BivouacDatabase.getInstance(context).loggedTrackDao()

    @Before
    fun resetSingleton() {
        BivouacDatabase.closeAndReset()
    }

    @After
    fun tearDown() {
        LoggedTrackGpxStore.dir(context).deleteRecursively()
        BivouacDatabase.closeAndReset()
    }

    // Quinze points à 30 m d'écart (mêmes coordonnées que TrackSegmenterTest, dérivées exactement
    // de GeoMath.haversineMeters), tous à la même altitude : deux segments plats de 210 m à 4 km/h.
    // Instant.plusSeconds()/toString() plutôt qu'un formatage "%02d" manuel de l'heure : ce dernier
    // déborde silencieusement au-delà de 59 secondes (378 s -> "378", pas "06:18") et produit un
    // <time> invalide que GpxParser rejette sans bruit.
    private fun flatGpx(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\"><trk><name>t</name><trkseg>\n")
        val degPerMeter = Math.toDegrees(1.0 / 6_371_000.0)
        val baseTime = java.time.Instant.parse("2026-06-01T08:00:00Z")
        repeat(15) { i ->
            val lat = 45.0 + i * 30.0 * degPerMeter
            val seconds = (i * 30.0 / 1000.0 / 4.0 * 3600.0).toLong()
            append("<trkpt lat=\"$lat\" lon=\"6.0\"><ele>1000.0</ele><time>${baseTime.plusSeconds(seconds)}</time></trkpt>\n")
        }
        append("</trkseg></trk></gpx>")
    }

    // Un seul point : aucun segment possible (usable.size < 2), mais un GPX bien formé.
    private fun tinyGpx(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\"><trk><name>t</name><trkseg>" +
            "<trkpt lat=\"45.0\" lon=\"6.0\"><ele>1000.0</ele><time>2026-06-01T08:00:00Z</time></trkpt>" +
            "</trkseg></trk></gpx>"

    private suspend fun insertLegacyDay(trackId: String, gpx: String, alreadyRic98: Boolean): String {
        dao.insertTrack(
            LoggedTrackEntity(
                id = trackId, name = "Trace $trackId", startedAt = 0L, contentHash = "hash-$trackId",
                distanceMeters = 0.0, elevationGainMeters = 0.0, elevationLossMeters = 0.0,
                pointCount = 0, estimatedDurationMinutes = 0,
            ),
        )
        LoggedTrackGpxStore.dir(context).mkdirs()
        val relativePath = LoggedTrackGpxStore.relativePath(trackId, 0)
        LoggedTrackGpxStore.resolve(context, relativePath).writeText(gpx, StandardCharsets.UTF_8)
        dao.insertDays(
            listOf(
                LoggedTrackDayEntity(
                    trackId = trackId,
                    dayIndex = 0,
                    rawGpxFilePath = relativePath,
                    // flatCount reste null dans les deux cas : c'est justement ce que le
                    // rattrapage RIC-109 doit combler, que RIC-98/99 ait déjà traité cette ligne
                    // ou non.
                    contentHash = if (alreadyRic98) "already-hashed-by-ric-98-99" else null,
                    startedAtMillis = if (alreadyRic98) 1L else null,
                    elapsedSeconds = if (alreadyRic98) 1L else null,
                ),
            ),
        )
        return relativePath
    }

    private fun expectedAggregateFor(gpx: String): DaySegmentAggregate {
        val points = gpx.byteInputStream(StandardCharsets.UTF_8).use { GpxParser.parse(it) }.points
        return DaySegmentAggregate.of(TrackSegmenter.segment(points))
    }

    @Test
    fun populatesSegmentColumnsForARowNeverBackfilled() = runBlocking {
        insertLegacyDay("track-new", flatGpx(), alreadyRic98 = false)

        LoggedTrackBackfill.run(context, dao)

        val day = dao.getDays("track-new").single()
        val expected = expectedAggregateFor(flatGpx())
        assertNotNull(day.contentHash)
        assertNotNull(day.flatCount)
        assertEquals(expected.flatCount, day.flatCount)
        assertEquals(expected.flatDistanceMeters, day.flatDistanceMeters!!, 1e-6)
        assertEquals(expected.flatHours, day.flatHours!!, 1e-9)
        assertEquals(expected.steepCount, day.steepCount)
    }

    // Le cas qui a motivé le changement de requête dans LoggedTrackDao : une ligne déjà rattrapée
    // par RIC-98/99 (contentHash non nul) ne serait plus jamais revue si le rattrapage continuait
    // à filtrer sur contentHash IS NULL.
    @Test
    fun populatesSegmentColumnsForARowAlreadyBackfilledUnderRic98() = runBlocking {
        insertLegacyDay("track-old", flatGpx(), alreadyRic98 = true)

        LoggedTrackBackfill.run(context, dao)

        val day = dao.getDays("track-old").single()
        val expected = expectedAggregateFor(flatGpx())
        // backfillOne recalcule contentHash à chaque passage (idempotent, coût négligeable face à
        // parser tout le fichier) plutôt que de faire confiance à une valeur déjà en base — donc
        // le placeholder inséré par insertLegacyDay ne survit pas, seul importe qu'un vrai hash
        // remplace la valeur d'origine.
        assertNotNull(day.contentHash)
        assertTrue(day.contentHash != "already-hashed-by-ric-98-99")
        assertEquals(expected.flatCount, day.flatCount)
        assertEquals(expected.steepCount, day.steepCount)
    }

    @Test
    fun writesZeroNotNullWhenTrackHasNoExploitableSegment() = runBlocking {
        insertLegacyDay("track-tiny", tinyGpx(), alreadyRic98 = false)

        LoggedTrackBackfill.run(context, dao)

        val day = dao.getDays("track-tiny").single()
        assertNotNull("flatCount doit être traité (0), pas laissé null", day.flatCount)
        assertEquals(0, day.flatCount)
        assertEquals(0.0, day.flatDistanceMeters!!, 0.0)
        assertEquals(0, day.steepCount)
    }

    @Test
    fun missingFileIsMarkedProcessedWithEmptyAggregate() = runBlocking {
        val relativePath = insertLegacyDay("track-missing", flatGpx(), alreadyRic98 = false)
        LoggedTrackGpxStore.resolve(context, relativePath).delete()

        LoggedTrackBackfill.run(context, dao)

        val day = dao.getDays("track-missing").single()
        assertNotNull(day.contentHash) // hash de chaîne vide, mais traité
        assertEquals(0, day.flatCount)
        assertEquals(0, day.steepCount)
    }

    @Test
    fun secondRunIsANoOpOnceEverythingIsBackfilled() = runBlocking {
        insertLegacyDay("track-idempotent", flatGpx(), alreadyRic98 = false)
        LoggedTrackBackfill.run(context, dao)
        val firstPass = dao.getDays("track-idempotent").single()

        LoggedTrackBackfill.run(context, dao) // ne doit rien retraiter (countDaysNeedingBackfill == 0)

        val secondPass = dao.getDays("track-idempotent").single()
        assertEquals(firstPass, secondPass)
    }

    // Garde-fou RIC-109 (voir LoggedTrackRepository.calibrationSamples) : une trace dont un seul
    // jour n'est pas encore rattrapé ne doit contribuer AUCUNE somme à l'agrégat, même si son autre
    // jour l'est déjà — mélanger une somme partielle avec un jour ignoré donnerait une calibration
    // silencieusement fausse.
    @Test
    fun calibrationSamplesExcludesATrackWithOnePartiallyBackfilledDay() = runBlocking {
        dao.insertTrack(
            LoggedTrackEntity(
                id = "trek", name = "Trek deux jours", startedAt = 0L, contentHash = "hash-trek",
                distanceMeters = 1000.0, elevationGainMeters = 50.0, elevationLossMeters = 50.0,
                pointCount = 30, estimatedDurationMinutes = 20,
            ),
        )
        LoggedTrackGpxStore.dir(context).mkdirs()
        val path0 = LoggedTrackGpxStore.relativePath("trek", 0)
        LoggedTrackGpxStore.resolve(context, path0).writeText(flatGpx(), StandardCharsets.UTF_8)
        val path1 = LoggedTrackGpxStore.relativePath("trek", 1)
        LoggedTrackGpxStore.resolve(context, path1).writeText(flatGpx(), StandardCharsets.UTF_8)
        dao.insertDays(
            listOf(
                LoggedTrackDayEntity(
                    trackId = "trek", dayIndex = 0, rawGpxFilePath = path0,
                    contentHash = "h0", startedAtMillis = 0L, elapsedSeconds = 100L,
                    flatCount = 2, flatDistanceMeters = 420.0, flatHours = 0.105,
                    steepCount = 0, steepDistanceMeters = 0.0, steepGainMeters = 0.0, steepHours = 0.0,
                ),
                // Jour 1 : pas encore rattrapé (flatCount null), même si son GPX est parfaitement
                // lisible — c'est un jour "en attente", pas un jour illisible.
                LoggedTrackDayEntity(trackId = "trek", dayIndex = 1, rawGpxFilePath = path1),
            ),
        )

        val input = LoggedTrackRepository(context).calibrationSamples(setOf("trek"))

        assertEquals(DaySegmentAggregate.EMPTY, input.aggregate)
        // Le repli par échantillons, lui, reste disponible (reparsing complet, comme avant RIC-109).
        assertTrue(input.fallbackSamples.isNotEmpty())
    }

    // GPX à deux points d'altitude croissante, sans rapport avec flatGpx() ci-dessus : le seul
    // point que ces tests RIC-19 vérifient est max/dernier point, pas la segmentation.
    private fun elevationGpx(maxEle: Double, lastEle: Double): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\"><trk><name>t</name><trkseg>" +
            "<trkpt lat=\"45.0\" lon=\"6.0\"><ele>1000.0</ele><time>2026-06-01T08:00:00Z</time></trkpt>" +
            "<trkpt lat=\"45.001\" lon=\"6.0\"><ele>$maxEle</ele><time>2026-06-01T09:00:00Z</time></trkpt>" +
            "<trkpt lat=\"45.002\" lon=\"6.0\"><ele>$lastEle</ele><time>2026-06-01T10:00:00Z</time></trkpt>" +
            "</trkseg></trk></gpx>"

    // GPX bien formé mais sans la moindre balise <ele> : cas réel (certains traceurs n'enregistrent
    // pas l'altitude), distinct d'un fichier illisible.
    private fun noElevationGpx(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\"><trk><name>t</name><trkseg>" +
            "<trkpt lat=\"45.0\" lon=\"6.0\"><time>2026-06-01T08:00:00Z</time></trkpt>" +
            "<trkpt lat=\"45.001\" lon=\"6.0\"><time>2026-06-01T09:00:00Z</time></trkpt>" +
            "</trkseg></trk></gpx>"

    @Test
    fun runElevationPopulatesMaxAndLastPointElevation() = runBlocking {
        insertLegacyDay("track-ele", elevationGpx(maxEle = 2500.0, lastEle = 2100.0), alreadyRic98 = true)

        LoggedTrackBackfill.runElevation(context, dao)

        val day = dao.getDays("track-ele").single()
        assertTrue(day.elevationBackfilled)
        assertEquals(2500.0, day.maxElevationMeters!!, 1e-9)
        assertEquals(2100.0, day.lastPointElevationMeters!!, 1e-9)
    }

    @Test
    fun runElevationLeavesValuesNullButMarksBackfilledWhenNoElevationData() = runBlocking {
        insertLegacyDay("track-no-ele", noElevationGpx(), alreadyRic98 = false)

        LoggedTrackBackfill.runElevation(context, dao)

        val day = dao.getDays("track-no-ele").single()
        assertTrue("un jour sans donnée d'altitude doit quand même sortir de la file d'attente", day.elevationBackfilled)
        assertEquals(null, day.maxElevationMeters)
        assertEquals(null, day.lastPointElevationMeters)
    }

    @Test
    fun runElevationMarksProcessedWithoutDataWhenFileIsMissing() = runBlocking {
        val relativePath = insertLegacyDay("track-ele-missing", elevationGpx(2500.0, 2100.0), alreadyRic98 = false)
        LoggedTrackGpxStore.resolve(context, relativePath).delete()

        LoggedTrackBackfill.runElevation(context, dao)

        val day = dao.getDays("track-ele-missing").single()
        assertTrue(day.elevationBackfilled)
        assertEquals(null, day.maxElevationMeters)
    }

    @Test
    fun runElevationSecondRunIsANoOp() = runBlocking {
        insertLegacyDay("track-ele-idem", elevationGpx(2500.0, 2100.0), alreadyRic98 = false)
        LoggedTrackBackfill.runElevation(context, dao)
        val firstPass = dao.getDays("track-ele-idem").single()

        LoggedTrackBackfill.runElevation(context, dao)

        val secondPass = dao.getDays("track-ele-idem").single()
        assertEquals(firstPass, secondPass)
    }

    // RIC-19 : ce rattrapage ne partage pas son marqueur avec celui de RIC-109 (flatCount) — une
    // ligne déjà entièrement traitée par l'ancien rattrapage doit quand même être reprise ici.
    @Test
    fun runElevationRevisitsARowAlreadyBackfilledUnderRic109() = runBlocking {
        insertLegacyDay("track-ele-ric109", elevationGpx(2500.0, 2100.0), alreadyRic98 = false)
        LoggedTrackBackfill.run(context, dao) // rattrapage RIC-109 seul, elevationBackfilled reste false

        val beforeElevation = dao.getDays("track-ele-ric109").single()
        assertNotNull(beforeElevation.flatCount)
        assertTrue(!beforeElevation.elevationBackfilled)

        LoggedTrackBackfill.runElevation(context, dao)

        val after = dao.getDays("track-ele-ric109").single()
        assertTrue(after.elevationBackfilled)
        assertEquals(2500.0, after.maxElevationMeters!!, 1e-9)
    }
}

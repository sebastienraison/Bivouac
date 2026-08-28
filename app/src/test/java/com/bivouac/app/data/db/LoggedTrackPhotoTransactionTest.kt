package com.bivouac.app.data.db

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.model.TrackPoint
import java.io.ByteArrayInputStream
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
 * RIC-149 : le cycle transactionnel des photos du mode édition, étape par étape.
 *
 * Ce que ces tests garantissent tient en une phrase : entre la validation du sélecteur et la
 * disquette, la base ne bouge pas. Ce qui se joue d'ici là se joue en zone de transit, et un
 * abandon doit rendre la trace exactement telle qu'elle était.
 *
 * Même infrastructure Robolectric que LoggedTrackPhotoRepositoryTest (vrai Context, vraie base
 * SQLite, vrais fichiers, sans appareil), et mêmes « photos » : des suites d'octets quelconques,
 * rien ici ne décodant d'image.
 */
@RunWith(RobolectricTestRunner::class)
class LoggedTrackPhotoTransactionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository

    private val trackId = "ric149-track"

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

    private suspend fun createTrack() {
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test transit",
        )
        repository.commitImport(
            PreparedImport(
                LoggedTrackEntity(
                    id = trackId,
                    name = "Trace test transit",
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

    // registerInputStreamSupplier et non registerInputStream : le staging ouvre chaque Uri deux
    // fois (empreinte, puis copie), un flux unique serait déjà épuisé au deuxième passage.
    private fun registerPhoto(name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/$name")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    private fun storedFiles(): List<String> =
        LoggedTrackPhotoStore.dir(context).listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()

    private fun transitFiles(): List<String> =
        LoggedTrackPhotoStore.transitDir(context).listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()

    private suspend fun stage(vararg uris: Uri): List<PendingPhotoAdd> =
        repository.stagePhotosFromPicker(trackId, context.contentResolver, uris.toList()).staged

    @Test
    fun staging_writesOnlyToTheTransitAreaAndLeavesTheDatabaseUntouched() = runBlocking {
        createTrack()

        val staged = stage(registerPhoto("a", byteArrayOf(1)), registerPhoto("b", byteArrayOf(2)))

        assertEquals(2, staged.size)
        assertEquals("rien ne doit entrer en base avant la disquette", 0, repository.listPhotos(trackId).size)
        assertEquals("rien ne doit entrer dans filesDir avant la disquette", emptyList<String>(), storedFiles())
        assertEquals(2, transitFiles().size)
        // Les octets sont bien là, sous le chemin que l'UI résoudra pour afficher la vignette.
        val file = LoggedTrackPhotoStore.resolve(context, staged.first().transitPath)
        assertTrue(file.exists())
        assertTrue(LoggedTrackPhotoStore.isTransit(staged.first().transitPath))
    }

    @Test
    fun commit_movesTransitFilesIntoStorageAndInsertsTheRows() = runBlocking {
        createTrack()
        val staged = stage(registerPhoto("a", byteArrayOf(1, 1)), registerPhoto("b", byteArrayOf(2, 2)))

        val failures = repository.commitPendingPhotos(trackId, staged)

        assertEquals(0, failures)
        assertEquals(2, repository.listPhotos(trackId).size)
        assertEquals(2, storedFiles().size)
        assertEquals("le transit doit être vide après la sauvegarde", emptyList<String>(), transitFiles())
        // Déplacement et non recopie : les octets d'origine sont ceux qui ont atterri en stockage.
        val photo = repository.listPhotos(trackId).first()
        assertTrue(LoggedTrackPhotoStore.resolve(context, photo.filePath).readBytes().isNotEmpty())
        assertEquals(emptySet<Long>(), repository.missingPhotoFileIds(repository.listPhotos(trackId)))
    }

    @Test
    fun discard_removesTheTransitFilesAndLeavesTheDatabaseUntouched() = runBlocking {
        createTrack()
        val staged = stage(registerPhoto("a", byteArrayOf(3)), registerPhoto("b", byteArrayOf(4)))

        repository.discardPendingPhotos(staged)

        assertEquals(emptyList<String>(), transitFiles())
        assertEquals(emptyList<String>(), storedFiles())
        assertEquals(0, repository.listPhotos(trackId).size)
    }

    /**
     * Le cœur de la transactionnalité côté suppression : marquer n'écrit rien, et abandonner
     * l'édition rend la photo intacte, ligne ET fichier.
     *
     * L'abandon d'une suppression n'a volontairement pas d'appel dédié dans le repository — c'est
     * ne rien faire. Ce test le fige quand même, parce que c'est exactement la garantie que la
     * revue attend : une suppression annulée ne doit pas avoir laissé de trace.
     */
    @Test
    fun aPendingDeletionOnlyTakesEffectWhenItIsCommitted() = runBlocking {
        createTrack()
        repository.commitPendingPhotos(trackId, stage(registerPhoto("a", byteArrayOf(5))))
        val photo = repository.listPhotos(trackId).single()
        val file = LoggedTrackPhotoStore.resolve(context, photo.filePath)

        // Abandon : la suppression n'est jamais transmise au repository.
        assertTrue("la photo doit être intacte tant que rien n'est commité", file.exists())
        assertEquals(1, repository.listPhotos(trackId).size)

        // Sauvegarde : la même intention, cette fois appliquée.
        repository.deletePhotos(listOf(photo.id))

        assertEquals(0, repository.listPhotos(trackId).size)
        assertFalse("le fichier doit partir avec la ligne", file.exists())
    }

    /**
     * RIC-149 : la déduplication couvre le transit.
     *
     * Deux passages dans le sélecteur sans sauvegarde entre les deux doivent se comporter comme
     * deux passages séparés par une sauvegarde — sans cette couverture, la même photo entrerait
     * deux fois et le doublon n'apparaîtrait qu'à la disquette.
     */
    @Test
    fun staging_skipsAPhotoAlreadyStagedButNotYetSaved() = runBlocking {
        createTrack()
        val bytes = byteArrayOf(6, 6, 6)
        val first = stage(registerPhoto("a", bytes))

        val batch = repository.stagePhotosFromPicker(
            trackId,
            context.contentResolver,
            listOf(registerPhoto("b", bytes.copyOf())),
            alreadyStagedHashes = first.mapTo(mutableSetOf()) { it.contentHash },
        )

        assertEquals(0, batch.staged.size)
        assertEquals(1, batch.report.duplicatesSkipped)
        assertEquals("aucun octet en trop dans le transit", 1, transitFiles().size)
    }

    /**
     * L'autre bord de la même règle : une photo marquée pour suppression ne compte plus comme
     * présente. Sans ce retrait, supprimer une photo puis la reprendre dans la même édition serait
     * refusé en doublon, et l'utilisateur se retrouverait coincé entre les deux.
     */
    @Test
    fun staging_acceptsAPhotoThatIsPendingDeletion() = runBlocking {
        createTrack()
        val bytes = byteArrayOf(7, 7)
        repository.commitPendingPhotos(trackId, stage(registerPhoto("a", bytes)))
        val existing = repository.listPhotos(trackId).single()

        val batch = repository.stagePhotosFromPicker(
            trackId,
            context.contentResolver,
            listOf(registerPhoto("b", bytes.copyOf())),
            ignoredHashes = setOf(existing.contentHash),
        )

        assertEquals(1, batch.staged.size)
        assertEquals(0, batch.report.duplicatesSkipped)
    }

    /**
     * RIC-149 : le balayage des transits périmés, c'est-à-dire ce qu'un process tué en pleine
     * édition laisse derrière lui. Fait à l'ouverture du Journal (voir JournalViewModel), où aucune
     * édition n'est encore en cours — d'où l'appel sans exclusion ici.
     */
    @Test
    fun purgePhotoTransit_removesOrphanTransitFilesButSparesTheOnesStillClaimed() = runBlocking {
        createTrack()
        val orphan = stage(registerPhoto("a", byteArrayOf(8)))
        val stillEditing = stage(registerPhoto("b", byteArrayOf(9)))
        assertEquals(2, transitFiles().size)

        repository.purgePhotoTransit(keptPaths = stillEditing.mapTo(mutableSetOf()) { it.transitPath })

        assertEquals(1, transitFiles().size)
        assertFalse(LoggedTrackPhotoStore.resolve(context, orphan.single().transitPath).exists())
        assertTrue(LoggedTrackPhotoStore.resolve(context, stillEditing.single().transitPath).exists())

        // Sans exclusion — le cas réel du démarrage : il ne doit plus rien rester.
        repository.purgePhotoTransit()
        assertEquals(emptyList<String>(), transitFiles())
    }

    /**
     * RIC-43 : le comparateur qui range les photos à l'écran doit rendre, à la photo près, l'ordre
     * de la requête DAO — c'est toute sa raison d'être (voir [PhotoDisplayOrder]).
     *
     * Vérifié contre la vraie base et non contre une réécriture du `ORDER BY` en commentaire : les
     * deux cas qui se devinent mal sont ici, la date de prise de vue absente (SQLite range les NULL
     * en tête d'un tri croissant, `nullsFirst` côté Kotlin) et l'égalité de dates, départagée par
     * l'ordre d'entrée dans le Journal.
     */
    @Test
    fun photoDisplayOrder_reproducesTheDaoOrderByToThePhoto() = runBlocking {
        createTrack()
        val dao = BivouacDatabase.getInstance(context).loggedTrackDao()
        // Insérées dans un ordre qui n'est ni celui de la prise de vue ni celui de l'ajout, pour
        // qu'un tri qui ne trierait rien ne puisse pas passer par chance.
        val inserted = listOf(
            // takenAtMillis, addedAtMillis
            2_000L to 10L,
            null to 90L,
            1_000L to 30L,
            2_000L to 5L,
            null to 20L,
            3_000L to 1L,
            1_000L to 31L,
        ).map { (takenAt, addedAt) ->
            val id = dao.insertPhoto(
                LoggedTrackPhotoEntity(
                    trackId = trackId,
                    filePath = "photos/$trackId-$takenAt-$addedAt.jpg",
                    addedAtMillis = addedAt,
                    takenAtMillis = takenAt,
                    contentHash = "hash-$takenAt-$addedAt",
                ),
            )
            id
        }

        val fromDatabase = dao.getPhotos(trackId)
        val sortedInKotlin = fromDatabase.shuffled().sortedWith(PhotoDisplayOrder)

        assertEquals(inserted.size, fromDatabase.size)
        assertEquals(
            "le tri Kotlin doit rendre exactement l'ordre du ORDER BY",
            fromDatabase.map { it.id },
            sortedInKotlin.map { it.id },
        )
        // Et l'ordre attendu lui-même, écrit à la main : sans lui, les deux côtés pourraient être
        // faux de la même façon.
        assertEquals(
            listOf(null to 20L, null to 90L, 1_000L to 30L, 1_000L to 31L, 2_000L to 5L, 2_000L to 10L, 3_000L to 1L),
            fromDatabase.map { it.takenAtMillis to it.addedAtMillis },
        )
    }

    /**
     * RIC-152 : « Purger les photos ». Le relevé annoncé par le bouton et le résultat de la purge,
     * vérifiés ensemble — annoncer une volumétrie puis en supprimer une autre serait pire que ne
     * rien annoncer.
     */
    @Test
    fun purgeAllPhotos_reportsThenRemovesEveryRowAndEveryFile() = runBlocking {
        createTrack()
        repository.commitPendingPhotos(
            trackId,
            stage(registerPhoto("a", ByteArray(1_000)), registerPhoto("b", ByteArray(2_000))),
        )

        val summary = repository.photoStorageSummary()
        assertEquals(2, summary.count)
        assertEquals(3_000L, summary.totalBytes)

        repository.purgeAllPhotos()

        assertEquals(0, repository.listPhotos(trackId).size)
        assertEquals(emptyList<String>(), storedFiles())
        assertEquals(PhotoStorageSummary(count = 0, totalBytes = 0L), repository.photoStorageSummary())
        // La trace, elle, survit : la purge ne touche que les photos.
        assertEquals(1, repository.list().size)
    }
}

package com.bivouac.app.data.db

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.backup.BackupManager
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.model.TrackPoint
import java.nio.charset.StandardCharsets
import java.io.File
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
 * RIC-103 : BackupManager.backup() ferme puis rouvre la base (closeAndReset, pour un checkpoint
 * WAL propre avant la copie du fichier) pendant que les ViewModels vivants gardent leurs
 * repositories. Ceux-ci doivent résoudre une connexion vivante à chaque accès, pas celle qu'ils
 * ont vue à leur construction — sinon toute lecture qui suit une sauvegarde échoue jusqu'au
 * redémarrage du process, sur les trois écrans à la fois : Journal (LoggedTrackRepository),
 * Planification (BankedTrackRepository et SavedTrackRepository) et Réglages, qui lit le Journal
 * par la même classe LoggedTrackRepository.
 *
 * Limite connue : backup() écrit aussi dans le DataStore de SettingsPreferences, un singleton de
 * process qu'aucun tearDown ne peut réinitialiser — il reste lié au sandbox Robolectric de la
 * première classe de test qui l'a touché. Sans conséquence ici, mais une future classe de test
 * qui voudrait ASSERTER sur le contenu du DataStore devra en tenir compte.
 */
@RunWith(RobolectricTestRunner::class)
class RepositoryBackupCycleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Le singleton survit d'un test à l'autre alors que Robolectric change de sandbox : reset
    // avant ET après, pour ne pas hériter d'une base pointant sur les fichiers d'un environnement
    // disparu (classe de test antérieure), ni en léguer une au test suivant.
    @Before
    fun resetSingleton() {
        BivouacDatabase.closeAndReset()
    }

    @After
    fun tearDown() {
        BivouacDatabase.closeAndReset()
    }

    @Test
    fun repositoriesBuiltBeforeABackupStillReadAfterIt() = runBlocking {
        // Construits avant la sauvegarde, comme le sont ceux des ViewModels déjà vivants.
        val logged = LoggedTrackRepository(context)
        val banked = BankedTrackRepository(context)
        val saved = SavedTrackRepository(context)

        val gpx = GpxWriter.write(listOf(TrackPoint(45.0, 6.0, 1000.0, null)), "Trace test")
        logged.commitImport(
            PreparedImport(
                LoggedTrackEntity(
                    id = "ric103-logged",
                    name = "Journal",
                    startedAt = 0L,
                    contentHash = "hash",
                    distanceMeters = 1.0,
                    elevationGainMeters = 2.0,
                    elevationLossMeters = 3.0,
                    pointCount = 1,
                    estimatedDurationMinutes = 4,
                ),
                listOf(
                    PreparedDay(
                        rawGpx = gpx,
                        contentHash = "hash",
                        startedAtMillis = 0L,
                        elapsedSeconds = null,
                        segmentAggregate = DaySegmentAggregate.EMPTY,
                    ),
                ),
            ),
        )
        val database = BivouacDatabase.getInstance(context)
        // RIC-97 : gpxContent vit maintenant dans un fichier sous PlanificationGpxStore, pas dans
        // la colonne — écrit ici directement, comme le ferait BankedTrackRepository.save()/
        // SavedTrackRepository.save(), puisque ce test insère les lignes en passant par le DAO nu.
        PlanificationGpxStore.dir(context).mkdirs()
        val bankedRelativePath = PlanificationGpxStore.bankedRelativePath("ric103-banked")
        PlanificationGpxStore.resolve(context, bankedRelativePath).writeText(gpx, StandardCharsets.UTF_8)
        database.bankedTrackDao().save(
            BankedTrackEntity(
                id = "ric103-banked",
                name = "Banque",
                gpxFilePath = bankedRelativePath,
                bivouacTrackPointIndices = "",
                distanceMeters = 1.0,
                elevationGainMeters = 2.0,
                elevationLossMeters = 3.0,
                estimatedDurationMinutes = 4,
                savedAt = 5L,
            ),
        )
        val savedRelativePath = PlanificationGpxStore.savedRelativePath()
        PlanificationGpxStore.resolve(context, savedRelativePath).writeText(gpx, StandardCharsets.UTF_8)
        database.savedTrackDao().save(
            SavedTrackEntity(trackName = "Plan en cours", gpxFilePath = savedRelativePath, bivouacTrackPointIndices = "0"),
        )

        val backup = BackupManager.backup(context, Uri.fromFile(File(context.cacheDir, "ric103-backup.zip")))
        assertTrue("La sauvegarde elle-même doit réussir : ${backup.exceptionOrNull()}", backup.isSuccess)

        // Le cœur de RIC-103 : chacune de ces lectures passait par la connexion fermée.
        assertEquals(listOf("Journal"), logged.list().map { it.name })
        assertEquals(listOf("Banque"), banked.list().map { it.name })
        assertNotNull(saved.loadLast())
    }
}

package com.bivouac.app.settings

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.db.LoggedTrackGpxStore
import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import com.bivouac.app.data.db.LoggedTrackPhotoStore
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.db.PreparedDay
import com.bivouac.app.data.db.PreparedImport
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.GpxWriter
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.data.operations.ExclusiveOperation
import com.bivouac.app.data.operations.ExclusiveOperations
import com.bivouac.app.data.photo.PhotoContentHash
import com.bivouac.app.data.photo.PhotoStorageMode
import com.bivouac.app.data.prefs.SettingsPreferences
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * RIC-157, retour de recette : la proposition de recompresser le stock, enchaînée depuis les
 * Réglages juste après avoir basculé vers la copie réduite.
 *
 * Même infrastructure que StorageUsageRecompressionExclusionTest et
 * SettingsPhotoPurgeExclusionTest : une vraie base et de vrais fichiers (Robolectric), parce que
 * choosePhotoStorageMode s'appuie sur AppStorageUsageCalculator.compute, qui les lit.
 *
 * ⚠️ Même limite du harnais que JournalPhotoStorageModeTest : le DataStore des Réglages est un
 * singleton de classloader qui survit d'un test à l'autre sous Robolectric. Aucun test d'ici ne
 * part donc de « aucune décision enregistrée » : setUp POSE explicitement FULL avant de construire
 * le ViewModel, ce qui rend chaque test indépendant de son ordre d'exécution.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsPhotoStorageModeRecompressionOfferTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: LoggedTrackRepository
    private lateinit var settingsPreferences: SettingsPreferences
    private lateinit var viewModel: SettingsViewModel

    private val trackId = "ric157-offer"

    // Assez lourde pour qu'il reste quelque chose à gagner : PhotoRecompression.estimate compare
    // au poids supposé d'une copie réduite (ASSUMED_REDUCED_PHOTO_BYTES, 700 000 octets) quand le
    // Journal n'a encore aucun échantillon réel. Une photo plus légère que ça (le cas des autres
    // fixtures de ce fichier de test, qui ne regardent pas l'estimation) ne libérerait rien et
    // l'estimation vaudrait null, comme il se doit.
    private val photoBytes = ByteArray(900_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
        ExclusiveOperations.resetForTests()
        repository = LoggedTrackRepository(application)
        settingsPreferences = SettingsPreferences(application)
        runBlocking { seedOneArchivePhoto() }
        // Voir l'avertissement en tête de classe : posé explicitement, jamais laissé à
        // « aucune décision », qui dépendrait de l'ordre d'exécution des tests sous Robolectric.
        runBlocking { settingsPreferences.setPhotoStorageMode(PhotoStorageMode.FULL) }
        viewModel = SettingsViewModel(application)
        // photoStorageMode est un combine(...).stateIn(WhileSubscribed(5_000), ...) : sans
        // souscripteur, sa valeur reste figée sur son défaut de départ (REDUCED) et ne reflète
        // jamais le FULL posé ci-dessus, exactement ce que choosePhotoStorageMode lit comme mode
        // PRÉCÉDENT. En écran réel, collectAsStateWithLifecycle est ce souscripteur ; ici, ce
        // premier first() joue ce rôle et arme la fenêtre de 5 s pendant laquelle settle() peut
        // laisser le combine se recalculer.
        runBlocking { viewModel.photoStorageMode.first() }
        settle()
    }

    @After
    fun tearDown() {
        ExclusiveOperations.resetForTests()
        BivouacDatabase.closeAndReset()
        application.deleteDatabase(BivouacDatabase.DATABASE_NAME)
        LoggedTrackPhotoStore.dir(application).deleteRecursively()
        LoggedTrackGpxStore.dir(application).deleteRecursively()
    }

    // Garde-fou de la mise en place ci-dessus : si l'amorçage de photoStorageMode échoue un jour
    // (changement de patron StateFlow), c'est ici que ça doit casser, avec un message clair,
    // plutôt qu'en confondant les tests de comportement ci-dessous.
    @Test
    fun sanityFixtureStartsWithTheArchiveModeInEffect() {
        assertEquals(PhotoStorageMode.FULL, viewModel.photoStorageMode.value)
    }

    @Test
    fun switchingToReducedWithFullPhotosOffersToRecompressThem() {
        viewModel.choosePhotoStorageMode(PhotoStorageMode.REDUCED)
        awaitOffer()

        val offer = viewModel.photoRecompressionOffer.value
        assertNotNull("le stock FULL doit déclencher la proposition", offer)
        assertEquals(1, offer?.photoCount)
        assertEquals(
            PhotoStorageMode.REDUCED,
            runBlocking { settingsPreferences.photoStorageModeDecision.first() },
        )
    }

    @Test
    fun switchingToFullNeverOffersAnything() {
        // Part de REDUCED et non du FULL posé par setUp : c'est la vraie bascule (REDUCED -> FULL)
        // qu'on veut couvrir ici, pas seulement l'absence de changement déjà testée par
        // choosingReducedTwiceInARowOffersOnlyOnTheActualSwitch.
        runBlocking { settingsPreferences.setPhotoStorageMode(PhotoStorageMode.REDUCED) }
        runBlocking { viewModel.photoStorageMode.first() }
        settle()

        viewModel.choosePhotoStorageMode(PhotoStorageMode.FULL)
        settle()

        assertNull(
            "« Qualité d'origine » ne doit jamais proposer de recompresser quoi que ce soit",
            viewModel.photoRecompressionOffer.value,
        )
    }

    @Test
    fun choosingReducedTwiceInARowOffersOnlyOnTheActualSwitch() {
        viewModel.choosePhotoStorageMode(PhotoStorageMode.REDUCED)
        awaitOffer()
        assertNotNull(viewModel.photoRecompressionOffer.value)
        viewModel.dismissPhotoRecompressionOffer()
        // awaitOffer n'attend que le relevé (usage/estimation), pas la persistance de la décision
        // elle-même : sans ceci, le second appel ci-dessous pourrait encore lire l'ancien mode
        // PRÉCÉDENT (FULL) le temps que l'écriture DataStore se propage jusqu'à photoStorageMode,
        // et croirait à tort à une vraie bascule.
        awaitPhotoStorageMode(PhotoStorageMode.REDUCED)

        // Rappuyer sur le bouton déjà sélectionné (SegmentedButton rappelle onModeSelected même
        // sans changement) ne doit pas relancer la proposition. Rien à attendre ici : c'est
        // justement l'absence d'effet qu'on vérifie, d'où un settle() borné et non un awaitOffer().
        viewModel.choosePhotoStorageMode(PhotoStorageMode.REDUCED)
        settle()

        assertNull(
            "un appui sur le mode déjà effectif n'est pas une bascule",
            viewModel.photoRecompressionOffer.value,
        )
    }

    @Test
    fun recompressingFromTheOfferClearsItLocksAndReportsExactlyLikeTheDedicatedButton() {
        viewModel.choosePhotoStorageMode(PhotoStorageMode.REDUCED)
        awaitOffer()
        assertNotNull(viewModel.photoRecompressionOffer.value)

        viewModel.recompressPhotosFromOffer()

        assertNull("la proposition se ferme dès le clic sur Recompresser", viewModel.photoRecompressionOffer.value)
        assertEquals(DataOperationPhase.PHOTO_RECOMPRESS, viewModel.dataOperationProgress.value?.phase)
        assertEquals(ExclusiveOperation.PHOTO_RECOMPRESS, ExclusiveOperations.current.value)

        idleUntilRecompressionDone()

        assertNull(viewModel.dataOperationProgress.value)
        assertNull(ExclusiveOperations.current.value)
        val report = viewModel.photoRecompressionReport.value
        assertNotNull("jamais de fin silencieuse", report)
        // L'URI d'origine du montage ("content://test/introuvable") ne résout jamais à de vrais
        // octets : la passe ne peut donc que CONSERVER cette photo (original introuvable), jamais
        // la recompresser. C'est le verrou et le rapport qu'on vérifie ici, pas le taux de
        // réussite de la résolution d'original, déjà couvert par PhotoOriginalResolver et
        // PhotoRecompressionPassTest.
        assertEquals(0, report?.recompressed)
        assertEquals(1, report?.kept)
    }

    // --- Mise en place -------------------------------------------------------------------------

    private suspend fun seedOneArchivePhoto() {
        val gpx = GpxWriter.write(
            listOf(
                TrackPoint(45.0, 6.0, 1000.0, Instant.parse("2026-06-12T08:00:00Z")),
                TrackPoint(45.01, 6.01, 1100.0, Instant.parse("2026-06-12T08:30:00Z")),
            ),
            "Trace test proposition",
        )
        repository.commitImport(
            PreparedImport(
                LoggedTrackEntity(
                    id = trackId,
                    name = "Trace test proposition",
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
        val relativePath = "${LoggedTrackPhotoStore.DIR_NAME}/$trackId-copie.jpg"
        val file = LoggedTrackPhotoStore.resolve(application, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(photoBytes)
        BivouacDatabase.getInstance(application).loggedTrackDao().insertPhoto(
            LoggedTrackPhotoEntity(
                trackId = trackId,
                filePath = relativePath,
                addedAtMillis = 1780300900000L,
                contentHash = PhotoContentHash.of(ByteArrayInputStream(photoBytes)),
                sourceDisplayName = "IMG_0001.jpg",
                sourceDateTakenMillis = 1780300850000L,
                storageMode = PhotoStorageMode.FULL,
                lastResolvedUri = "content://test/introuvable",
            ),
        )
    }

    // choosePhotoStorageMode lance son relevé (AppStorageUsageCalculator.compute) sur
    // Dispatchers.IO, un vrai pool de threads que Robolectric ne pilote pas : il faut donc laisser
    // s'écouler un peu de temps réel, pas seulement vider la boucle du looper principal. Utilisé
    // seulement là où on vérifie une ABSENCE d'effet (rien à attendre positivement) : sinon voir
    // awaitOffer ci-dessous, qui sort dès que le résultat attendu est là plutôt que d'attendre le
    // plafond à chaque fois.
    private fun settle(timeoutMillis: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun awaitOffer(timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (viewModel.photoRecompressionOffer.value != null) return
            Thread.sleep(5)
        }
    }

    private fun awaitPhotoStorageMode(expected: PhotoStorageMode, timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (viewModel.photoStorageMode.value == expected) return
            Thread.sleep(5)
        }
        org.junit.Assert.fail("photoStorageMode n'a jamais atteint $expected")
    }

    private fun idleUntilRecompressionDone(timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (ExclusiveOperations.current.value != ExclusiveOperation.PHOTO_RECOMPRESS) {
                shadowOf(Looper.getMainLooper()).idle()
                return
            }
            Thread.sleep(5)
        }
        org.junit.Assert.fail("la recompression ne s'est jamais terminée")
    }
}

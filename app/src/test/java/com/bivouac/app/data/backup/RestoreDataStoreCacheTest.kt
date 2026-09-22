package com.bivouac.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.db.BivouacDatabase
import com.bivouac.app.data.prefs.MapLayerPreferences
import com.bivouac.app.data.prefs.SettingsPreferences
import com.bivouac.app.data.prefs.SpeedCalibrationMode
import com.bivouac.app.ui.map.MapLayer
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RIC-204 : reproduit le vrai bug (S22, Android 16, journal système à l'appui) au niveau où il vit
 * réellement. AppRestart tuait le processus après une restauration parce que deux choses restaient
 * périmées sans ça : la base (RIC-103, déjà couvert par RepositoryBackupCycleTest) et les DataStore
 * de SettingsPreferences/MapLayerPreferences, des singletons de process qui ne se relisent jamais
 * seuls depuis le disque (voir ResettableDataStoreHolder). replaceWithRollback() remplace pourtant
 * leur fichier .preferences_pb EN PLACE, sans passer par l'API DataStore.
 *
 * Contrairement à BivouacDatabase (RIC-103, résolu par un DAO recalculé à CHAQUE ACCÈS,
 * indépendamment de qui le détient), SettingsPreferences/MapLayerPreferences résolvent leur
 * DataStore une fois pour toutes DANS LEUR CONSTRUCTEUR (`val manualCalibration = context.
 * settingsDataStore.data.map { ... }`) : une instance déjà vivante au moment de la restauration ne
 * se rafraîchit donc jamais toute seule, reset ou pas. Ce que garantit le correctif RIC-204, c'est
 * qu'une instance construite APRÈS restore() (c'est ce que produit AppRestart.refresh() en vidant
 * le ViewModelStore puis en recréant l'Activity : chaque écran refait un ViewModel neuf, donc une
 * SettingsPreferences/MapLayerPreferences neuve) lit bien le fichier restauré, pas la valeur que
 * l'instance périmée avait mise en cache juste avant.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreDataStoreCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Même précaution que RepositoryBackupCycleTest (RIC-103) : ces singletons survivent d'une
    // classe de test à l'autre alors que Robolectric change de sandbox.
    @Before
    fun resetSingletons() {
        BivouacDatabase.closeAndReset()
        SettingsPreferences.resetCache()
        MapLayerPreferences.resetCache()
    }

    @After
    fun tearDown() {
        BivouacDatabase.closeAndReset()
        SettingsPreferences.resetCache()
        MapLayerPreferences.resetCache()
    }

    @Test
    fun freshInstancesConstructedAfterRestoreReadTheRestoredPreferences() = runBlocking {
        // restore() refuse toute archive sans bivouac.db (RIC-95) : Room ne crée le fichier
        // physique qu'à son premier accès, donc un accès quelconque suffit ici, sans qu'aucune
        // ligne ne soit nécessaire pour ce que ce test vérifie (les préférences, pas le Journal).
        BivouacDatabase.getInstance(context).savedTrackDao().get()

        // Comme le ferait SettingsViewModel/GpxImportViewModel avant la restauration.
        val settingsBeforeRestore = SettingsPreferences(context)
        val mapPrefsBeforeRestore = MapLayerPreferences(context)
        settingsBeforeRestore.setSpeedCalibrationMode(SpeedCalibrationMode.MANUAL)
        settingsBeforeRestore.setManualCalibration(5.0, 120.0, 20.0)
        mapPrefsBeforeRestore.setSelectedLayer(MapLayer.SATELLITE)

        val backupFile = File(context.cacheDir, "ric204-backup-${System.nanoTime()}.zip")
        val backupResult = BackupManager.backup(context, Uri.fromFile(backupFile))
        assertTrue("La sauvegarde doit réussir : ${backupResult.exceptionOrNull()}", backupResult.isSuccess)

        // État courant modifié APRÈS la sauvegarde, à travers les mêmes instances que celles d'un
        // écran déjà affiché : c'est ce qui met le DataStore en cache mémoire, exactement comme le
        // ferait un StateFlow déjà collecté à l'écran Réglages au moment où l'utilisateur restaure.
        settingsBeforeRestore.setManualCalibration(3.5, 100.0, 0.0)
        mapPrefsBeforeRestore.setSelectedLayer(MapLayer.HIKING)
        assertEquals(3.5, settingsBeforeRestore.manualCalibration.first().walkingSpeedKmh, 0.0)
        assertEquals(MapLayer.HIKING, mapPrefsBeforeRestore.selectedLayer.first())

        val restoreResult = BackupManager.restore(context, Uri.fromFile(backupFile))
        assertEquals(RestoreResult.Success, restoreResult)

        // Le cœur de RIC-204 : ce qu'AppRestart.refresh() produit réellement, un ViewModel neuf
        // donc une instance neuve, jamais celle qui a survécu à la restauration. Construite APRÈS
        // restore() (donc après resetCache()), elle doit lire l'archive restaurée, pas ce que
        // settingsBeforeRestore/mapPrefsBeforeRestore ont laissé en cache juste avant.
        val settingsAfterRestore = SettingsPreferences(context)
        val mapPrefsAfterRestore = MapLayerPreferences(context)
        val restoredManual = settingsAfterRestore.manualCalibration.first()
        assertEquals(5.0, restoredManual.walkingSpeedKmh, 0.0)
        assertEquals(120.0, restoredManual.elevationGainPenaltyMetersPerKm, 0.0)
        assertEquals(20.0, restoredManual.pauseFractionPercent, 0.0)
        assertEquals(SpeedCalibrationMode.MANUAL, settingsAfterRestore.speedCalibrationMode.first())
        assertEquals(MapLayer.SATELLITE, mapPrefsAfterRestore.selectedLayer.first())
    }
}

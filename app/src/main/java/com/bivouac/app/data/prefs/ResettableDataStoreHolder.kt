package com.bivouac.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * RIC-204 : remplace `by preferencesDataStore(name = ...)` pour [SettingsPreferences] et
 * [MapLayerPreferences], les deux seuls DataStore que BackupManager restaure (voir
 * PREFS_FILE_NAMES).
 *
 * Le délégué standard construit un singleton de PROCESS : une fois le fichier ouvert une première
 * fois, il ne se relit plus jamais tout seul depuis le disque (voir la kdoc de
 * BackupManagerTest.backupThenRestoreBringsBackJournalDataAndPreferences, qui documentait déjà
 * cette limite avant ce ticket). BackupManager.restore() remplace pourtant le fichier
 * .preferences_pb EN PLACE, par un rename/copie qui ne passe jamais par l'API DataStore : sans
 * [reset], toute lecture qui suit continuerait de servir la valeur mise en cache avant la
 * restauration, quelle que soit la fraîcheur de l'instance de [SettingsPreferences] ou
 * [MapLayerPreferences] qui la déclenche (un ViewModel neuf y compris).
 *
 * Même doctrine que BivouacDatabase.getInstance()/closeAndReset() (RIC-103) : résolu à chaque
 * accès, jamais figé, et explicitement réinitialisable au moment précis où les fichiers changent
 * sous ses pieds.
 */
internal class ResettableDataStoreHolder(private val fileName: String) {

    @Volatile private var instance: DataStore<Preferences>? = null

    // DataStore refuse d'ouvrir une deuxième instance sur le même fichier tant que la portée
    // (CoroutineScope) de la précédente n'a pas été annulée (IllegalStateException "There are
    // multiple DataStores active for the same file", vérifié en écrivant le test JVM de ce
    // ticket) : sans coroutine à elle pour la fermer, [reset] ne ferait que perdre la référence,
    // pas libérer le fichier. D'où une portée EXPLICITE, gardée ici pour être annulée par reset().
    @Volatile private var scope: CoroutineScope? = null

    fun getInstance(context: Context): DataStore<Preferences> =
        instance ?: synchronized(this) {
            instance ?: run {
                val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                scope = dataStoreScope
                PreferenceDataStoreFactory.create(
                    scope = dataStoreScope,
                    produceFile = { context.applicationContext.preferencesDataStoreFile(fileName) },
                ).also { instance = it }
            }
        }

    /** Appelé par BackupManager juste après avoir remplacé le fichier de préférences restauré. */
    fun reset() {
        synchronized(this) {
            scope?.cancel()
            scope = null
            instance = null
        }
    }
}

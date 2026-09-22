package com.bivouac.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bivouac.app.ui.map.MapLayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Exposed so BackupManager (BIV-66) can name this file explicitly instead of guessing.
internal const val MAP_LAYER_DATASTORE_NAME = "map_layer_prefs"

// RIC-204 : ResettableDataStoreHolder plutôt que `by preferencesDataStore(...)` : voir sa kdoc
// dans SettingsPreferences.kt, qui a le même besoin pour le même motif (BackupManager restaure
// aussi ce fichier).
private val mapLayerDataStoreHolder = ResettableDataStoreHolder(MAP_LAYER_DATASTORE_NAME)
private val Context.mapLayerDataStore: DataStore<Preferences> get() = mapLayerDataStoreHolder.getInstance(this)

// Randonnée (OpenTopoMap) stays the fallback default when nothing is stored yet: matches the
// V1.1 choice of the more useful default for hiking, before this preference existed.
class MapLayerPreferences(private val context: Context) {
    private val key = stringPreferencesKey("selected_map_layer")

    val selectedLayer: Flow<MapLayer> = context.mapLayerDataStore.data.map { prefs ->
        prefs[key]?.let { name -> MapLayer.entries.find { it.name == name } } ?: MapLayer.HIKING
    }

    suspend fun setSelectedLayer(layer: MapLayer) {
        context.mapLayerDataStore.edit { it[key] = layer.name }
    }

    companion object {
        // RIC-204 : même rôle que SettingsPreferences.resetCache(), appelé au même instant par
        // BackupManager.restore().
        fun resetCache() = mapLayerDataStoreHolder.reset()
    }
}

package com.bivouac.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bivouac.app.ui.map.MapLayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.mapLayerDataStore by preferencesDataStore(name = "map_layer_prefs")

// Randonnée (OpenTopoMap) stays the fallback default when nothing is stored yet — matches the
// V1.1 choice of the more useful default for hiking, before this preference existed.
class MapLayerPreferences(private val context: Context) {
    private val key = stringPreferencesKey("selected_map_layer")

    val selectedLayer: Flow<MapLayer> = context.mapLayerDataStore.data.map { prefs ->
        prefs[key]?.let { name -> MapLayer.entries.find { it.name == name } } ?: MapLayer.HIKING
    }

    suspend fun setSelectedLayer(layer: MapLayer) {
        context.mapLayerDataStore.edit { it[key] = layer.name }
    }
}

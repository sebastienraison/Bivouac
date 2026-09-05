package com.bivouac.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MapControls(
    selectedLayer: MapLayer,
    onLayerSelected: (MapLayer) -> Unit,
    recenterEnabled: Boolean,
    onRecenterClick: () -> Unit,
    modifier: Modifier = Modifier,
    // BIV-16: Satellite (Esri) is one of the two features cut by "Désactiver les fonctions non
    // libres": removed from the picker entirely rather than shown disabled, since the ViewModels
    // already fall the active layer back off it the moment this is true.
    nonFreeFeaturesDisabled: Boolean = false,
) {
    var layerMenuExpanded by remember { mutableStateOf(false) }
    val availableLayers = MapLayer.entries.filterNot { nonFreeFeaturesDisabled && it == MapLayer.SATELLITE }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The dropdown must live inside its own Box, scoped to just this button: as a direct
        // Column child (a sibling of the recenter button) its expand/collapse animation briefly
        // reports a non-zero measured height, which the Column's spacedBy() picks up and uses to
        // shove the recenter button down and back up.
        Box {
            FilledIconButton(
                onClick = { layerMenuExpanded = true },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(selectedLayer.icon, contentDescription = "Choisir le fond de carte (actuel : ${selectedLayer.label})")
            }
            DropdownMenu(expanded = layerMenuExpanded, onDismissRequest = { layerMenuExpanded = false }) {
                availableLayers.forEach { layer ->
                    DropdownMenuItem(
                        text = { Text(layer.label) },
                        leadingIcon = { Icon(layer.icon, contentDescription = null) },
                        trailingIcon = {
                            if (layer == selectedLayer) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                            } else {
                                Box(modifier = Modifier.size(20.dp))
                            }
                        },
                        onClick = {
                            onLayerSelected(layer)
                            layerMenuExpanded = false
                        },
                    )
                }
            }
        }
        FilledIconButton(
            onClick = onRecenterClick,
            enabled = recenterEnabled,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "Recentrer sur la trace")
        }
    }
}

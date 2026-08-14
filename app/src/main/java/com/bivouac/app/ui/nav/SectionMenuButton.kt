package com.bivouac.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.bivouac.app.R

/**
 * Trigger + dropdown for switching between the app's top-level sections. The trigger shows the
 * active section's own icon rather than a generic menu glyph, so it doubles as a permanent
 * "you are here" indicator without needing a persistent nav bar (which would eat into the map's
 * height — a real constraint on this app, see BIV-31). The bivouac orange is reserved for this
 * button and the app logo, not reused elsewhere (e.g. the map layer button stays neutral).
 */
@Composable
fun SectionMenuButton(current: AppSection, onSelect: (AppSection) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        FilledIconButton(
            onClick = { expanded = true },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = colorResource(R.color.marker_bivouac),
                contentColor = Color.White,
            ),
        ) {
            Icon(current.icon, contentDescription = "Changer de section (actuelle : ${current.label})")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppSection.entries.forEach { section ->
                DropdownMenuItem(
                    text = { Text(section.label) },
                    leadingIcon = { Icon(section.icon, contentDescription = null) },
                    trailingIcon = {
                        if (section == current) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        } else {
                            Box(modifier = Modifier.size(20.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(section)
                    },
                )
            }
        }
    }
}

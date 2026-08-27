package com.bivouac.app.ui.journal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.bivouac.app.data.db.LoggedTrackPhotoEntity

// RIC-43 : vignette du bandeau « Photos » de la vue détail, appui long -> menu contextuel
// (Repositionner / Supprimer). Sorti de JournalScreen.kt, comme DateFilteredPhotoPickerDialog :
// l'écran hébergeait quatre composables photo qui n'ont rien à voir avec sa mécanique de tiroir.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotoThumbnail(
    photo: LoggedTrackPhotoEntity,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onRepositionClick: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        PhotoTile(
            photo = photo,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true }),
        )
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            // Deux libellés pour la même entrée, selon qu'un repère existe déjà ou non : sur une
            // photo sans position, « Repositionner » promettait de déplacer quelque chose qui
            // n'était nulle part. « Placer sur la trace » crée le repère au curseur puis enchaîne
            // sur le même glisser (voir JournalViewModel.beginRepositionPhoto).
            val placed = photo.positionPointIndex != null
            DropdownMenuItem(
                text = { Text(if (placed) "Repositionner" else "Placer sur la trace") },
                leadingIcon = {
                    Icon(
                        if (placed) Icons.Default.OpenWith else Icons.Default.AddLocationAlt,
                        contentDescription = null,
                    )
                },
                onClick = { menuExpanded = false; onRepositionClick() },
            )
            DropdownMenuItem(
                text = { Text("Supprimer") },
                leadingIcon = {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { menuExpanded = false; onDeleteClick() },
            )
        }
    }
}

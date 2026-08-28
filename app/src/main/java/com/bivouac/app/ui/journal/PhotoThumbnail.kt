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

/**
 * RIC-43 : vignette du bandeau « Photos » de la vue détail. Sortie de JournalScreen.kt, comme
 * PhotoPickerDialog : l'écran hébergeait quatre composables photo qui n'ont rien à voir avec sa
 * mécanique de tiroir.
 *
 * RIC-149 : l'appui long et son menu (Repositionner / Placer sur la trace / Supprimer)
 * n'existent qu'en mode [editing], au même titre que les tags et la note. Hors édition, la
 * vignette est en consultation stricte : un appui long ne fait rien du tout, plutôt que d'ouvrir
 * un menu aux entrées grisées qui laisserait croire à une action momentanément indisponible.
 * Voir, zoomer et feuilleter restent libres dans les deux cas, par le tap.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotoThumbnail(
    photo: LoggedTrackPhotoEntity,
    editing: Boolean,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onRepositionClick: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // Quitter le mode édition pendant que le menu est ouvert le referme : sans ça, il resterait à
    // l'écran, seul reliquat modifiant d'une vue redevenue consultation.
    if (!editing && menuExpanded) menuExpanded = false
    Box {
        PhotoTile(
            photo = photo,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (editing) ({ menuExpanded = true }) else null,
                ),
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

package com.bivouac.app.ui.journal

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.bivouac.app.data.photo.PhotoPickerScope

/**
 * RIC-43 : le sélecteur de photos de l'app, et le seul chemin d'ajout depuis que le Photo Picker
 * système a été retiré.
 *
 * Pourquoi ne pas avoir gardé le Photo Picker : il ne demande aucune permission, ce qui est son
 * seul avantage, et il le paie deux fois. Il expurge le GPS de l'EXIF des photos qu'il rend (voir
 * ACCESS_MEDIA_LOCATION au manifest), donc il prive la fonctionnalité de la seule donnée qui place
 * une photo à coup sûr ; et il présente toute la pellicule sans filtre, donc il rend à la main le
 * travail que la recherche par date fait toute seule. Un seul chemin, permissionné, qui exploite
 * pleinement ce que la permission débloque.
 *
 * Les candidats sont trouvés par MediaStorePhotoQuery avant l'ouverture : ce dialogue ne fait que
 * la sélection, jamais la requête lui-même.
 */
@Composable
internal fun PhotoPickerDialog(
    candidates: List<Uri>,
    loading: Boolean,
    scope: PhotoPickerScope,
    partialAccess: Boolean,
    onScopeChange: (PhotoPickerScope) -> Unit,
    onSelectMorePhotos: () -> Unit,
    onConfirm: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Volontairement sans clé sur `candidates` : changer de périmètre relance la requête et
    // remplace la liste, mais ce que l'utilisateur a déjà coché reste coché. Perdre une sélection
    // parce qu'on est allé voir plus loin serait une punition, et le compte affiché plus bas dit
    // toujours combien de photos partiront, y compris celles qui ne sont plus à l'écran.
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Ajouter des photos",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fermer")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = scope == PhotoPickerScope.TRACK_DATES,
                        onClick = { onScopeChange(PhotoPickerScope.TRACK_DATES) },
                        label = { Text("Période de la sortie") },
                    )
                    FilterChip(
                        selected = scope == PhotoPickerScope.WHOLE_GALLERY,
                        onClick = { onScopeChange(PhotoPickerScope.WHOLE_GALLERY) },
                        label = { Text("Toute la galerie") },
                    )
                }
                if (partialAccess) {
                    PartialAccessBanner(onSelectMorePhotos = onSelectMorePhotos)
                }
                when {
                    loading -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    candidates.isEmpty() -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(32.dp),
                        ) {
                            Text(
                                if (scope == PhotoPickerScope.TRACK_DATES) {
                                    "Aucune photo trouvée sur la période de cette sortie."
                                } else {
                                    "Aucune photo accessible dans la galerie."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (scope == PhotoPickerScope.TRACK_DATES) {
                                TextButton(onClick = { onScopeChange(PhotoPickerScope.WHOLE_GALLERY) }) {
                                    Text("Chercher dans toute la galerie")
                                }
                            }
                        }
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(2.dp),
                    ) {
                        items(candidates, key = { it.toString() }) { uri ->
                            val isSelected = uri in selected
                            Box(
                                modifier = Modifier
                                    .padding(2.dp)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        selected = if (isSelected) selected - uri else selected + uri
                                    },
                            ) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (isSelected) {
                                    Box(modifier = Modifier.fillMaxSize().background(SelectedOverlayColor))
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                            .padding(2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (candidates.isNotEmpty()) {
                    // « Tout sélectionner » porte sur le lot affiché, jamais sur la galerie
                    // entière : filtré sur la période d'une sortie, c'est le geste le plus
                    // fréquent (toutes ces photos sont celles de la rando), et il n'a plus de sens
                    // dès qu'on élargit le périmètre, d'où la bascule vers « Tout désélectionner »
                    // une fois le lot entièrement coché plutôt qu'un bouton qui ne ferait rien.
                    val allShownSelected = candidates.all { it in selected }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                selected = if (allShownSelected) selected - candidates.toSet() else selected + candidates
                            },
                        ) {
                            Text(if (allShownSelected) "Tout désélectionner" else "Tout sélectionner")
                        }
                        Text(
                            if (selected.isEmpty()) "Aucune sélection" else "${selected.size} sélectionnée(s)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { onConfirm(selected.toList()) }, enabled = selected.isNotEmpty()) {
                            Text("Ajouter")
                        }
                    }
                }
            }
        }
    }
}

/**
 * RIC-43 : accès partiel Android 14+ (« Sélectionner des photos »). MediaStore ne montre alors que
 * les photos explicitement ouvertes à l'app, ce qui est un choix légitime et pas un refus, mais
 * qui produit une grille trompeuse si rien ne le dit : la pellicule a l'air vide ou incomplète.
 *
 * Le bouton relance la demande de permission, ce qui est la seule façon de rouvrir le dialogue
 * système de re-sélection (il n'existe pas d'API pour l'appeler directement).
 */
@Composable
private fun PartialAccessBanner(onSelectMorePhotos: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Seules les photos que tu as autorisées sont visibles.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSelectMorePhotos) { Text("Sélectionner plus de photos") }
        }
    }
}

private val SelectedOverlayColor = Color.Black.copy(alpha = 0.35f)

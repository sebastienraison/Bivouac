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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
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
    // RIC-43 : la page « informations sur l'application » du système, seul endroit où un accès
    // partiel se transforme en accès complet.
    onOpenAppSettings: () -> Unit,
    onConfirm: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Volontairement sans clé sur `candidates` : changer de périmètre relance la requête et
    // remplace la liste, mais ce que l'utilisateur a déjà coché reste coché. Perdre une sélection
    // parce qu'on est allé voir plus loin serait une punition, et le compte affiché plus bas dit
    // toujours combien de photos partiront, y compris celles qui ne sont plus à l'écran.
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    // RIC-162 : index de la candidate agrandie dans la visionneuse de sélection, ou null quand la
    // grille seule est affichée. Vit ici et pas dans la visionneuse elle-même : c'est ce qui permet
    // à `selected` (juste au-dessus) de rester la SEULE source de vérité de la sélection, lue et
    // modifiée aussi bien par la grille que par la visionneuse, sans copie à resynchroniser à la
    // fermeture.
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
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
                    PartialAccessBanner(
                        scope = scope,
                        onSelectMorePhotos = onSelectMorePhotos,
                        onOpenAppSettings = onOpenAppSettings,
                    )
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
                        itemsIndexed(candidates, key = { _, uri -> uri.toString() }) { index, uri ->
                            val isSelected = uri in selected
                            Box(
                                modifier = Modifier
                                    .padding(2.dp)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { selected = toggleSelection(selected, uri) },
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
                                        contentDescription = "Sélectionnée",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                            .padding(2.dp),
                                    )
                                }
                                // RIC-162 : icône « étendre », en haut à GAUCHE (la coche reste à
                                // droite), qui ouvre la visionneuse de sélection sur CETTE
                                // candidate. Zone cliquable de 32 dp autour du pictogramme visuel
                                // de 24 dp : une cible tactile plus généreuse que le seul rond
                                // visible, sans empiéter sur la coche de l'autre coin. Nichée dans
                                // le Box cliquable de la vignette, elle intercepte le tap avant
                                // qu'il n'atteigne le clic de bascule de sélection : les deux
                                // gestes cohabitent sans se marcher dessus.
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(4.dp)
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .clickable { viewerIndex = index },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.45f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            ExpandPhotoIcon,
                                            contentDescription = "Agrandir la photo",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
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
                            Text(if (allShownSelected) "Tout désélectionner" else "Tout sélectionner", maxLines = 1)
                        }
                        // RIC-175 : le texte « n sélectionnée(s) » au milieu de la ligne est retiré
                        // (le bouton porte déjà le compte) : à 21 photos et plus il faisait replier
                        // « Ajouter (21) » sur trois lignes et déborder du dialogue. `maxLines = 1`
                        // sur les deux boutons empêche tout repli futur, quel que soit le compte.
                        Button(onClick = { onConfirm(selected.toList()) }, enabled = selected.isNotEmpty()) {
                            Text(if (selected.isEmpty()) "Ajouter" else "Ajouter (${selected.size})", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    // RIC-162 : visionneuse de sélection, ouverte par l'icône « étendre » d'une vignette. `selected`
    // est passé par référence (lecture/écriture) : basculer une photo ici modifie directement l'état
    // hoisté ci-dessus, donc la grille est déjà à jour au moment où la visionneuse se referme.
    viewerIndex?.let { index ->
        PhotoSelectionViewerDialog(
            photos = candidates,
            initialIndex = index,
            isSelected = { it in selected },
            onToggleSelected = { uri -> selected = toggleSelection(selected, uri) },
            onDismiss = { viewerIndex = null },
        )
    }
}

/**
 * RIC-162 : pictogramme « open_in_full » (flèches vers les coins opposés) de l'icône « étendre »
 * de la grille, reconstruit à la main plutôt que d'ajouter la dépendance material-icons-extended
 * pour un seul glyphe absent du jeu core déjà utilisé ici (Check, Close). Tracé identique à celui
 * de la maquette validée (`docs/pilotage/maquette-manipulation-photos-2026-09-16.html`, écran 6).
 */
private val ExpandPhotoIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ExpandPhoto",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        val stroke = SolidColor(Color.Black)
        path(stroke = stroke, strokeLineWidth = 2.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(15f, 3f)
            lineTo(21f, 3f)
            lineTo(21f, 9f)
        }
        path(stroke = stroke, strokeLineWidth = 2.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(9f, 21f)
            lineTo(3f, 21f)
            lineTo(3f, 15f)
        }
        path(stroke = stroke, strokeLineWidth = 2.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(21f, 3f)
            lineTo(14f, 10f)
        }
        path(stroke = stroke, strokeLineWidth = 2.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 21f)
            lineTo(10f, 14f)
        }
    }.build()
}

/**
 * RIC-43 : accès partiel Android 14+ (« Sélectionner des photos »). MediaStore ne montre alors que
 * les photos explicitement ouvertes à l'app, ce qui est un choix légitime et pas un refus, mais
 * qui produit une grille trompeuse si rien ne le dit : la pellicule a l'air vide ou incomplète.
 *
 * « Sélectionner plus de photos » relance la demande de permission, ce qui est la seule façon de
 * rouvrir le dialogue système de re-sélection (il n'existe pas d'API pour l'appeler directement).
 *
 * Le périmètre « Toute la galerie » a droit à un rappel plus explicite et à une seconde sortie.
 * C'est là que le malentendu est le plus fort : demander toute la galerie et obtenir une poignée de
 * photos, sans que le libellé du chip ne dise rien, se lit comme une pellicule vide plutôt que
 * comme une autorisation restreinte. Et c'est là que « en ouvrir quelques-unes de plus » n'est
 * souvent pas la bonne réponse : celui qui demande toute sa galerie veut l'accès complet, qui ne
 * s'accorde que dans les réglages système.
 */
@Composable
private fun PartialAccessBanner(
    scope: PhotoPickerScope,
    onSelectMorePhotos: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val wholeGallery = scope == PhotoPickerScope.WHOLE_GALLERY
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        if (wholeGallery) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
                Text(
                    "Autorisation partielle : même en « Toute la galerie », seules les photos que tu as " +
                        "sélectionnées sont visibles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onSelectMorePhotos) { Text("Sélectionner plus de photos") }
                    TextButton(onClick = onOpenAppSettings) { Text("Accès complet") }
                }
            }
        } else {
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
}

private val SelectedOverlayColor = Color.Black.copy(alpha = 0.35f)

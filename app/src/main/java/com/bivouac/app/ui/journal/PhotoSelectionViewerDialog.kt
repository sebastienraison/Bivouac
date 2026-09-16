package com.bivouac.app.ui.journal

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * RIC-162 : visionneuse ouverte depuis la grille du sélecteur de photos (icône « étendre » sur une
 * vignette), pour agrandir une candidate avant de décider de l'importer. Ce ne sont pas encore des
 * [com.bivouac.app.data.db.LoggedTrackPhotoEntity] : ces photos ne sont pas encore dans le journal,
 * seulement des URIs MediaStore trouvées par la requête de candidates.
 *
 * Réutilise le pager zoomable et la fenêtre immersive de [PhotoViewerDialog] ([ZoomableAsyncPhoto],
 * [ImmersiveBlackWindow]) : même geste de zoom pincé, même croix, même plein écran sans barres
 * système. Ce qui n'est PAS réutilisé, volontairement, c'est la montée en qualité RIC-157 :
 * [overlayUri] est toujours `null` ici, ces candidates étant déjà les fichiers d'origine de la
 * galerie (aucun original « meilleur » à aller chercher) ; Coil sous-échantillonne pour l'affichage
 * sans jamais décoder en pleine résolution.
 *
 * La sélection n'est pas dupliquée ici : [isSelected] et [onToggleSelected] lisent et modifient le
 * même `Set<Uri>` que la grille de [PhotoPickerDialog], hoisté chez l'appelant. Bascule depuis la
 * case à cocher du bandeau du bas, referme par la croix : la grille retrouve alors une sélection
 * déjà à jour, sans code de synchronisation dédié, c'est le même état, pas une copie.
 */
@Composable
internal fun PhotoSelectionViewerDialog(
    photos: List<Uri>,
    initialIndex: Int,
    isSelected: (Uri) -> Boolean,
    onToggleSelected: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { photos.size }
    // Même garde que PhotoViewerDialog : tant que la page courante est zoomée, un panoramique ne
    // doit pas être lu comme un balayage vers la candidate suivante.
    var zoomed by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImmersiveBlackWindow()
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableAsyncPhoto(
                    model = photos[page],
                    overlayUri = null,
                    onZoomedChanged = { zoomed = it },
                )
            }
            Text(
                selectionCounterLabel(pagerState.currentPage, photos.size),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .safeDrawingPadding()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(4.dp),
            ) {
                // Description distincte de celle de PhotoViewerDialog ("Fermer") : les deux
                // visionneuses peuvent être composées en même temps qu'une grille sous-jacente
                // (fenêtres de dialogue empilées), et un test qui cible celle-ci doit pouvoir la
                // distinguer sans dépendre de l'ordre de composition.
                Icon(Icons.Default.Close, contentDescription = "Fermer la visionneuse", tint = Color.White)
            }
            // getOrNull et non l'index direct : le pager peut brièvement rendre une page dont
            // l'index n'est plus valide pendant une recomposition (liste de candidates qui change
            // sous le dialogue, ex. changement de périmètre pendant que la visionneuse est ouverte).
            val currentUri = photos.getOrNull(pagerState.currentPage)
            if (currentUri != null) {
                SelectionBar(
                    selected = isSelected(currentUri),
                    onToggle = { onToggleSelected(currentUri) },
                )
            }
        }
    }
}

/**
 * RIC-162 : bandeau translucide du bas de la visionneuse de sélection, case à cocher + libellé,
 * tap n'importe où sur la ligne pour basculer (pas seulement sur la case), et le rappel du geste de
 * balayage. La case elle-même n'a pas de `onCheckedChange` propre : c'est la ligne entière qui porte
 * le clic, sinon les deux gestes se disputeraient le même tap.
 */
@Composable
private fun BoxScope.SelectionBar(selected: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = Color.White,
                    checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
            Text("Sélectionner cette photo", color = Color.White)
        }
        Text(
            "Balaye pour passer à la suivante",
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * RIC-162 : libellé "n / N" du compteur, extrait pour être testable sans monter la visionneuse.
 * `page` est un index base 0 (celui du pager) ; affiché en base 1, comme sur la maquette validée.
 */
internal fun selectionCounterLabel(page: Int, total: Int): String = "${page + 1} / $total"

/**
 * RIC-162 : bascule la présence de [uri] dans l'ensemble sélectionné. Extrait pour être testable
 * sans monter la grille, et partagé entre le tap sur une vignette de [PhotoPickerDialog] et la case
 * à cocher du bandeau ci-dessus : les deux gestes doivent produire exactement le même résultat.
 */
internal fun toggleSelection(selected: Set<Uri>, uri: Uri): Set<Uri> =
    if (uri in selected) selected - uri else selected + uri

package com.bivouac.app.ui.journal

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.testTag
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
 * même `Set<Uri>` que la grille de [PhotoPickerDialog], hoisté chez l'appelant. Referme par la
 * croix : la grille retrouve alors une sélection déjà à jour, sans code de synchronisation dédié,
 * c'est le même état, pas une copie.
 *
 * RIC-182 : la bascule se fait par un tap sur la photo elle-même, plus de bandeau ni de case à
 * cocher au bas de l'écran (décision produit : un geste direct sur ce qu'on regarde, plutôt qu'une
 * zone dédiée qui oblige à viser en bas). Le tap est posé par [ZoomableAsyncPhoto] via son
 * paramètre `onTap`, dans un `pointerInput` distinct de celui du pincement : le pincement, le
 * panoramique une fois zoomé et le balayage du pager continuent de fonctionner sans le déclencher,
 * `detectTapGestures` sachant déjà tout seul ignorer un contact qui n'est pas un simple tap. Le
 * retour visuel est la même coche que la grille (icône, fond, teinte), agrandie et superposée sous
 * la barre du haut ; elle suit l'état de sélection, pas le tap qui vient de le produire, donc elle
 * apparaît et disparaît avec lui sans code de transition dédié.
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
                // RIC-182 : testTag posé ici, côté appelant, plutôt que dans ZoomableAsyncPhoto lui
                // même : ce fichier est celui qui a besoin de viser le tap en test, l'autre reste
                // inchangé pour ses propres visionneuses (PhotoViewerDialog, PhotoGalleryDialog).
                Box(modifier = Modifier.fillMaxSize().testTag("selection-viewer-photo")) {
                    ZoomableAsyncPhoto(
                        model = photos[page],
                        overlayUri = null,
                        onZoomedChanged = { zoomed = it },
                        // page et non currentUri : chaque page du pager bascule SA propre photo, ce
                        // qui revient au même pour la page affichée mais évite de dépendre d'un
                        // recalcul de currentUri en dehors de cette fermeture.
                        onTap = { onToggleSelected(photos[page]) },
                    )
                }
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
            // RIC-182 : la coche suit la sélection, pas le tap qui vient de la produire : présente
            // tant que la photo courante est sélectionnée, absente sinon, sans état de transition
            // dédié. Sous la barre du haut (padding top généreux) pour ne jamais chevaucher le
            // compteur ni la croix de fermeture.
            if (currentUri != null && isSelected(currentUri)) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Sélectionnée",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .safeDrawingPadding()
                        .padding(top = 64.dp, end = 16.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(6.dp),
                )
            }
        }
    }
}

/**
 * RIC-162 : libellé "n / N" du compteur, extrait pour être testable sans monter la visionneuse.
 * `page` est un index base 0 (celui du pager) ; affiché en base 1, comme sur la maquette validée.
 */
internal fun selectionCounterLabel(page: Int, total: Int): String = "${page + 1} / $total"

/**
 * RIC-162 / RIC-182 : bascule la présence de [uri] dans l'ensemble sélectionné. Extrait pour être
 * testable sans monter la grille, et partagé entre le tap sur une vignette de [PhotoPickerDialog]
 * et le tap sur la photo de cette visionneuse : les deux gestes doivent produire exactement le
 * même résultat.
 */
internal fun toggleSelection(selected: Set<Uri>, uri: Uri): Set<Uri> =
    if (uri in selected) selected - uri else selected + uri

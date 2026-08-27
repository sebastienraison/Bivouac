package com.bivouac.app.ui.journal

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import com.bivouac.app.data.db.LoggedTrackPhotoStore

/**
 * RIC-43 : le rendu commun d'une photo du Journal partout où elle apparaît en petit — bandeau de la
 * vue détail comme galerie plate. Factorisé pour que l'état d'une photo (position approximative,
 * et plus tard fichier absent) se raconte de la même façon aux deux endroits, plutôt que d'être
 * réécrit deux fois.
 *
 * Le [modifier] porte la taille, la forme et le clic : le bandeau et la grille n'ont ni les mêmes
 * dimensions ni le même découpage, mais le contenu, lui, est identique.
 */
@Composable
internal fun PhotoTile(photo: LoggedTrackPhotoEntity, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val file = remember(photo.filePath) { LoggedTrackPhotoStore.resolve(context, photo.filePath) }
    val painter = rememberAsyncImagePainter(model = file, contentScale = ContentScale.Crop)
    Box(modifier = modifier) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        if (photo.positionApproximate) {
            ApproximatePositionBadge(
                modifier = Modifier.align(Alignment.BottomStart).padding(3.dp),
            )
        }
    }
}

/**
 * RIC-43 : « positionnement approximatif », c'est-à-dire une position déduite de l'horodatage et
 * non d'un GPS ni d'un placement manuel.
 *
 * Le WIP ne le signalait que par une opacité réduite du marqueur carte, invisible en pratique
 * (rien à quoi comparer quand toutes les photos d'une sortie sont dans le même cas) et absente de
 * la galerie. Un pictogramme discret plutôt qu'un libellé : la vignette la plus petite fait 72 dp,
 * un texte y serait illisible, et la même pastille sert aux deux tailles. Le sens reste accessible
 * par la description de contenu.
 */
@Composable
private fun ApproximatePositionBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
    ) {
        Icon(
            Icons.Default.LocationSearching,
            contentDescription = "Positionnement approximatif",
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(2.dp).size(12.dp),
        )
    }
}

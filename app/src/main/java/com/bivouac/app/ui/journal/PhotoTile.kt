package com.bivouac.app.ui.journal

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.NotListedLocation
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.bivouac.app.R
import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import com.bivouac.app.data.db.LoggedTrackPhotoStore
import com.bivouac.app.data.db.positionUncertain
import com.bivouac.app.data.photo.adjustedBy
import com.bivouac.app.data.photo.adjustments

/**
 * RIC-43 : le rendu commun d'une photo du Journal partout où elle apparaît en petit : bandeau de la
 * vue détail comme galerie plate. Factorisé pour que l'état d'une photo (position approximative,
 * fichier absent) se raconte de la même façon aux deux endroits, plutôt que d'être réécrit deux
 * fois.
 *
 * Le [modifier] porte la taille, la forme et le clic : le bandeau et la grille n'ont ni les mêmes
 * dimensions ni le même découpage, mais le contenu, lui, est identique.
 */
@Composable
internal fun PhotoTile(photo: LoggedTrackPhotoEntity, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val file = remember(photo.filePath) { LoggedTrackPhotoStore.resolve(context, photo.filePath) }
    // RIC-143/144 : la vignette montre la zone recadrée et rien d'autre, comme s'il s'agissait d'une
    // image à part entière (décision produit du 2026-09-16). Le recadrage n'est pas un état qu'on
    // signale, c'est ce que la photo EST devenue : le bandeau, la grille, la bulle et la visionneuse
    // doivent donc toutes montrer la même chose, d'où la transformation commune.
    val adjustments = photo.adjustments
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context).data(file).adjustedBy(adjustments).build(),
        contentScale = ContentScale.Crop,
    )
    Box(modifier = modifier) {
        // L'état d'erreur de Coil plutôt qu'un File.exists() : il couvre aussi le fichier présent
        // mais illisible, et surtout il n'ajoute aucun accès disque sur le thread de composition,
        // là où une grille de galerie recompose souvent.
        if (painter.state is AsyncImagePainter.State.Error) {
            MissingPhotoPlaceholder(Modifier.matchParentSize())
        } else {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        // RIC-214 : coin bas gauche, deux états qui s'excluent (une photo sans position sur la trace
        // n'est jamais « approximative » : positionApproximate n'est écrit qu'avec un point de trace,
        // voir PhotoPositionCorrelator.Position.NONE). « Sans position » est testé en premier :
        // si les deux se rencontraient un jour, c'est l'état le plus fort qui se dirait.
        if (photo.positionPointIndex == null) {
            PhotoBadge(
                icon = Icons.Default.LocationOff,
                description = stringResource(R.string.photo_gallery_no_position_description),
                modifier = Modifier.align(Alignment.BottomStart).padding(3.dp),
            )
        } else if (photo.positionUncertain) {
            PhotoBadge(
                icon = Icons.Default.NotListedLocation,
                description = stringResource(R.string.photo_gallery_approximate_position_description),
                modifier = Modifier.align(Alignment.BottomStart).padding(3.dp),
            )
        }
        // RIC-171 : « Retirée de la carte ». BottomEnd et non BottomStart : l'autre coin porte déjà
        // la pastille de position (approximative ou absente), et une photo peut porter les deux à la
        // fois (retirée de la carte ET sans position, ou ET approximative).
        if (!photo.shownOnMap) {
            PhotoBadge(
                icon = Icons.Default.VisibilityOff,
                description = stringResource(R.string.photo_gallery_removed_from_map_description),
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
            )
        }
    }
}

/**
 * RIC-43 : la copie locale de cette photo a disparu (restauration d'une sauvegarde antérieure,
 * nettoyage manuel du stockage de l'app...).
 *
 * La ligne, elle, n'est jamais supprimée pour autant : ses métadonnées d'origine (voir les colonnes
 * source* de LoggedTrackPhotoEntity) serviront à la re-acquérir depuis la galerie (RIC-151). D'ici
 * là, elle se montre telle qu'elle est plutôt que de laisser un carré vide qu'on prendrait pour un
 * chargement qui n'aboutit pas.
 */
@Composable
private fun MissingPhotoPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                Icons.Default.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.photo_msg_missing_photo),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/**
 * Les trois pastilles d'état d'une vignette, une seule fonction pour un seul rendu (disque noir
 * translucide, icône blanche, mêmes constantes de taille) :
 *
 * | État                      | Icône                          | Coin       |
 * |---------------------------|--------------------------------|------------|
 * | Position approximative    | NotListedLocation (pin avec ?) | bas gauche |
 * | Sans position sur la trace| LocationOff (pin barré)        | bas gauche |
 * | Retirée de la carte       | VisibilityOff (œil barré)      | bas droite |
 *
 * Le sens reste accessible par [description]. Un pictogramme discret plutôt qu'un libellé : la
 * vignette la plus petite fait 72 dp, un texte y serait illisible, et la même pastille sert à toutes
 * les tailles.
 *
 * RIC-43 : « positionnement approximatif » est une position déduite de l'horodatage alors que le
 * fuseau de cet horodatage n'était pas connu du fichier : voir
 * LoggedTrackPhotoEntity.positionUncertain pour les autres chemins, tous fiables et donc sans
 * pastille. Le badge est porté par la photo, jamais par la carte : le WIP signalait aussi le cas par
 * un marqueur estompé et un « ? » sur la carte, invisible en pratique (rien à quoi comparer quand
 * toutes les photos d'une sortie sont dans le même cas) et absent de la galerie.
 *
 * RIC-171 : « retirée de la carte » dit seulement « pas montrée là-bas en ce moment » (marqueur
 * absent, absente de la bulle et de son carrousel, mais présente ici, dans le bandeau, la grille et
 * la visionneuse). La position, elle, est conservée en base (voir shownOnMap).
 *
 * RIC-177 : fond noir translucide et icône blanche plutôt que les couleurs de thème d'origine
 * (`secondaryContainer` clair sur icône teintée), qui se fondaient dans une photo claire. Les
 * pastilles forment une famille, y compris sur une photo qui en porte deux à la fois.
 *
 * RIC-214 : « sans position sur la trace » (positionPointIndex nul) a désormais sa pastille. Le pin
 * barré, qui disait « retirée de la carte », lui revient ; « retirée de la carte » passe à l'œil
 * barré, et « approximatif » du pin-loupe au pin avec « ? ».
 */
@Composable
private fun PhotoBadge(icon: ImageVector, description: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = PhotoBadgeBackgroundColor,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.padding(PhotoBadgePadding).size(PhotoBadgeIconSize),
        )
    }
}

/**
 * RIC-177 : constantes partagées des pastilles de [PhotoTile], pour qu'elles restent
 * cohérentes si l'une évolue. Icône 18 dp (contre 12 dp à l'origine, illisible en pratique) dans
 * un disque de 4 dp de marge, soit 26 dp de diamètre : net sur la plus petite vignette de l'app
 * (bandeau, 72 dp) sans dévorer un quart de sa surface.
 */
private val PhotoBadgeBackgroundColor = Color.Black.copy(alpha = 0.55f)
private val PhotoBadgeIconSize = 18.dp
private val PhotoBadgePadding = 4.dp

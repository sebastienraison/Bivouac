package com.bivouac.app.ui.journal

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import com.bivouac.app.data.db.LoggedTrackPhotoStore
import com.bivouac.app.data.photo.PhotoAdjustments
import com.bivouac.app.data.photo.PhotoStorageMode
import com.bivouac.app.data.photo.adjustedBy
import com.bivouac.app.data.photo.adjustments
import java.io.File

/**
 * RIC-43 : visionneuse plein écran avec défilement entre les photos de la sortie (HorizontalPager)
 * et zoom pincé sur chaque page : fermeture par la croix plutôt qu'un tap n'importe où, pour ne
 * pas entrer en conflit avec le geste de pincement/panoramique.
 *
 * RIC-157 : c'est la seule surface qui monte en qualité. Les vignettes, la grille, le bandeau et
 * les marqueurs de la carte continuent d'afficher la copie locale, et c'est très bien : une
 * résolution coûte une lecture complète de fichier, et la payer pour une image de 100 px serait
 * absurde. Voir [resolveOriginal].
 */
@Composable
internal fun PhotoViewerDialog(
    photos: List<LoggedTrackPhotoEntity>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    /**
     * RIC-157 : retrouve l'original de la photo AFFICHÉE dans la galerie, ou rend null.
     *
     * Null par défaut : la visionneuse s'affiche exactement comme avant sans lui, et les écrans qui
     * n'ont pas de quoi résoudre (aperçu, test) n'ont rien à fournir.
     */
    resolveOriginal: (suspend (LoggedTrackPhotoEntity) -> Uri?)? = null,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialIndex) { photos.size }
    // Le défilement entre photos est désactivé tant que la page courante est zoomée : sinon un
    // panoramique vers la droite/gauche à l'intérieur d'une photo zoomée changerait de page au
    // lieu de déplacer le cadrage.
    var zoomed by remember { mutableStateOf(false) }

    /**
     * RIC-157 : l'original de la SEULE page courante, une fois retrouvé et confirmé.
     *
     * Une page et pas une carte de toutes les pages : la résolution ne doit jamais partir en masse,
     * et le pager compose déjà ses voisines. La clé de l'effet est la page courante, donc un
     * balayage annule la résolution en cours au lieu de la laisser courir pour une photo qui n'est
     * plus à l'écran (voir PhotoOriginalResolver, qui vérifie l'annulation entre deux candidats).
     *
     * L'état est remis à zéro à chaque changement de page AVANT toute recherche : sans ça, la photo
     * suivante afficherait un instant l'original de la précédente.
     */
    var upgradedPage by remember { mutableStateOf<Int?>(null) }
    var upgradedUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(pagerState.currentPage, photos, resolveOriginal) {
        upgradedPage = null
        upgradedUri = null
        val photo = photos.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        if (!deservesQualityUpgrade(photo)) return@LaunchedEffect
        val resolve = resolveOriginal ?: return@LaunchedEffect
        // Échec = repli silencieux, jamais de message : l'utilisateur n'a rien demandé, il regarde
        // sa photo, et la copie locale est parfaitement lisible. Lui annoncer que l'original a
        // disparu serait une mauvaise nouvelle non sollicitée au milieu d'un souvenir.
        val uri = runCatching { resolve(photo) }.getOrNull() ?: return@LaunchedEffect
        upgradedPage = pagerState.currentPage
        upgradedUri = uri
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImmersiveBlackWindow()
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableAsyncPhoto(
                    file = LoggedTrackPhotoStore.resolve(context, photos[page].filePath),
                    originalUri = upgradedUri.takeIf { page == upgradedPage },
                    // RIC-143/144 : les MÊMES ajustements pour les deux couches. C'est tout l'intérêt
                    // d'un rectangle normalisé : la copie locale et l'original n'ont pas les mêmes
                    // dimensions, mais la même fraction leur donne le même cadrage, donc le fondu de
                    // RIC-157 ne fait pas sauter la photo.
                    adjustments = photos[page].adjustments,
                    onZoomedChanged = { zoomed = it },
                )
            }
            IconButton(
                onClick = onDismiss,
                // safeDrawingPadding et non statusBarsPadding : les barres système étant masquées,
                // l'inset de statut vaut zéro et ne protège plus rien. Ce qui reste à éviter, c'est
                // l'encoche, que la fenêtre couvre désormais volontairement, et sous laquelle la
                // croix serait à moitié illisible.
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(4.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Fermer", tint = Color.White)
            }
        }
    }
}

/**
 * RIC-157 : cette photo mérite-t-elle qu'on aille chercher son original ?
 *
 * Une seule condition, et elle tient à ce que veut dire la colonne : seule une copie RÉDUITE peut
 * gagner quelque chose. Une copie intégrale EST déjà l'original, aller le chercher coûterait une
 * lecture complète de fichier pour réafficher exactement la même image.
 *
 * Extraite du composable pour être vérifiable sans monter d'écran, comme `addPhotosOutcome`
 * (RIC-43) : c'est la règle qui décide de la seule dépense que cette surface engage.
 */
internal fun deservesQualityUpgrade(photo: LoggedTrackPhotoEntity): Boolean =
    photo.storageMode == PhotoStorageMode.REDUCED

/**
 * RIC-43 : la visionneuse occupe réellement toute la dalle : barres système masquées, encoche
 * couverte, fond noir de bord à bord, et rend tout à la fermeture.
 *
 * Deux recettes successives ont mené ici. La première signalait, en paysage, la bande latérale de
 * l'encoche restée à la couleur du système : une fenêtre est par défaut mise en boîte autour de
 * l'encoche, bon comportement pour une interface, mauvais pour une visionneuse. Dessiner derrière
 * l'encoche a réglé ce bord-là, mais la seconde recette a montré que la barre de statut, elle,
 * restait en haut : couvrir l'encoche ne dit rien des barres, qui sont un réglage à part.
 *
 * D'où le mode immersif ici : les barres sont masquées tant que la visionneuse est ouverte, et un
 * balayage depuis le bord les fait revenir le temps de s'en servir (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
 * les cacher sans porte de sortie serait s'arroger la navigation du téléphone.
 *
 * Tout est porté par la fenêtre du dialogue et non par celle de l'Activity, comme le correctif
 * d'encoche l'était déjà : la fenêtre disparaît avec le dialogue, donc l'app retrouve son état
 * d'elle-même. Le `show` du onDispose est une ceinture de plus, pour le cas où le contrôleur
 * d'inserts d'une ROM tiendrait l'état plus longtemps que la fenêtre qui l'a demandé.
 *
 * ALWAYS à partir de l'API 30, qui couvre les encoches où qu'elles soient ; SHORT_EDGES sur 28-29,
 * la seule valeur disponible à l'époque, et qui suffit ici (en paysage, le bord qui porte l'encoche
 * est justement un petit côté). En dessous de l'API 28 il n'y a pas d'encoche à gérer.
 */
// RIC-143 : internal et non private, l'éditeur « Ajuster » est l'autre plein écran noir de l'app et
// doit se comporter exactement pareil (barres masquées, encoche couverte, tout rendu à la fermeture).
@Composable
internal fun ImmersiveBlackWindow() {
    val view = LocalView.current
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window
    DisposableEffect(dialogWindow) {
        val window = dialogWindow ?: return@DisposableEffect onDispose {}
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Attributs relus, modifiés, puis réassignés : c'est setAttributes qui déclenche le
            // relayout de la fenêtre, muter l'objet en place ne suffirait pas.
            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            window.attributes = attributes
        }
        // Le fond de la fenêtre elle-même, et pas seulement celui du Box : pendant le layout, et
        // dans la bande de l'encoche que le contenu n'a pas encore couverte, c'est lui qui se voit.
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
        // L'assombrissement que tout dialogue pose derrière lui : invisible sous un contenu noir
        // opaque, mais bien visible dans la bande que ce contenu ne couvrait pas encore.
        window.setDimAmount(0f)
        val controller = WindowInsetsControllerCompat(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * RIC-43 : zoom pincé (1x à 5x) + panoramique une fois zoomé, remise à 1x automatique dès que le
 * pincement repasse sous 1x : pas de double-tap dédié pour l'instant, pincer suffit dans les deux
 * sens.
 *
 * RIC-157 : [originalUri], quand il est là, se superpose à la copie locale au lieu de la remplacer.
 * Deux images empilées et non un `model` qu'on échange : un échange repart d'une image vide le
 * temps du chargement, ce qui donne exactement le flash blanc que la spec interdit. Ici la copie
 * locale reste dessous, visible, et l'original apparaît par-dessus en fondu une fois décodé. Le
 * zoom et le cadrage vivent sur le conteneur des deux, donc ils survivent tels quels à la bascule :
 * l'utilisateur qui examinait un détail continue de l'examiner, en mieux.
 */
@Composable
private fun ZoomableAsyncPhoto(
    file: File,
    originalUri: Uri?,
    adjustments: PhotoAdjustments,
    onZoomedChanged: (Boolean) -> Unit,
) {
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale > 1f) { onZoomedChanged(scale > 1f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Clé `file` et non `Unit` : un changement de fichier recrée scale/offset ci-dessus
            // (remember(file)) alors qu'un pointerInput déjà lancé garde sa closure sur les
            // anciens états : la boucle de gestes aurait alors piloté des états orphelins, et la
            // photo affichée n'aurait plus jamais zoomé.
            .pointerInput(file) {
                // detectTransformGestures consomme aussi un simple glissement à un doigt (c'est
                // un pan par définition) : ça cassait le défilement du HorizontalPager parent dès
                // qu'on touchait une photo, signalé en testant. Boucle manuelle à la place :
                // rien n'est consommé tant qu'il n'y a qu'un seul doigt et que l'image n'est pas
                // déjà zoomée, le pager reste alors seul maître du geste.
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            scale = newScale
                            offset = if (newScale <= 1f) {
                                Offset.Zero
                            } else {
                                // Panoramique borné à ce que l'agrandissement dégage réellement de
                                // chaque côté : sans cette borne, un glissement un peu ample
                                // poussait la photo entièrement hors de l'écran, sans autre issue
                                // que de dézoomer à l'aveugle pour la retrouver.
                                clampToBounds(offset + event.calculatePan(), newScale, size.width, size.height)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Le zoom est porté par le conteneur des deux images : elles se superposent au pixel près
        // quoi qu'il arrive, et la bascule en qualité supérieure ne bouge pas le cadrage d'un poil.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(file).adjustedBy(adjustments).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            if (originalUri != null) {
                AsyncImage(
                    // crossfade : l'original apparaît en fondu par-dessus la copie locale, jamais
                    // d'un coup. Sans lui, la substitution se voit comme un clignotement, alors
                    // que ce qu'on veut est que la photo « se précise » sans annoncer sa mécanique.
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(originalUri)
                        .adjustedBy(adjustments)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// L'agrandissement se fait autour du centre, donc il dégage (scale - 1) / 2 de chaque côté. Borne
// calculée sur le cadre, pas sur l'image rendue : ContentScale.Fit peut la laisser plus petite que
// son cadre (bandes noires), auquel cas la borne est un peu large, assumé : elle garantit qu'on ne
// peut jamais perdre la photo hors écran, ce qui est le seul rôle qu'on lui demande.
private fun clampToBounds(offset: Offset, scale: Float, widthPx: Int, heightPx: Int): Offset {
    val maxX = widthPx * (scale - 1f) / 2f
    val maxY = heightPx * (scale - 1f) / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

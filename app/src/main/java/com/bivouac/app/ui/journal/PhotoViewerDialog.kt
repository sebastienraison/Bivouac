package com.bivouac.app.ui.journal

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
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
import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import com.bivouac.app.data.db.LoggedTrackPhotoStore
import java.io.File

// RIC-43 : visionneuse plein écran avec défilement entre les photos de la sortie (HorizontalPager)
// et zoom pincé sur chaque page — fermeture par la croix plutôt qu'un tap n'importe où, pour ne
// pas entrer en conflit avec le geste de pincement/panoramique.
@Composable
internal fun PhotoViewerDialog(photos: List<LoggedTrackPhotoEntity>, initialIndex: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialIndex) { photos.size }
    // Le défilement entre photos est désactivé tant que la page courante est zoomée — sinon un
    // panoramique vers la droite/gauche à l'intérieur d'une photo zoomée changerait de page au
    // lieu de déplacer le cadrage.
    var zoomed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImmersiveBlackWindow()
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableAsyncPhoto(
                    file = LoggedTrackPhotoStore.resolve(context, photos[page].filePath),
                    onZoomedChanged = { zoomed = it },
                )
            }
            IconButton(
                onClick = onDismiss,
                // safeDrawingPadding et non statusBarsPadding : les barres système étant masquées,
                // l'inset de statut vaut zéro et ne protège plus rien. Ce qui reste à éviter, c'est
                // l'encoche — que la fenêtre couvre désormais volontairement, et sous laquelle la
                // croix serait à moitié illisible.
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(4.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Fermer", tint = Color.White)
            }
        }
    }
}

/**
 * RIC-43 : la visionneuse occupe réellement toute la dalle — barres système masquées, encoche
 * couverte, fond noir de bord à bord — et rend tout à la fermeture.
 *
 * Deux recettes successives ont mené ici. La première signalait, en paysage, la bande latérale de
 * l'encoche restée à la couleur du système : une fenêtre est par défaut mise en boîte autour de
 * l'encoche, bon comportement pour une interface, mauvais pour une visionneuse. Dessiner derrière
 * l'encoche a réglé ce bord-là, mais la seconde recette a montré que la barre de statut, elle,
 * restait en haut : couvrir l'encoche ne dit rien des barres, qui sont un réglage à part.
 *
 * D'où le mode immersif ici : les barres sont masquées tant que la visionneuse est ouverte, et un
 * balayage depuis le bord les fait revenir le temps de s'en servir (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
 * — les cacher sans porte de sortie serait s'arroger la navigation du téléphone.
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
@Composable
private fun ImmersiveBlackWindow() {
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

// RIC-43 : zoom pincé (1x à 5x) + panoramique une fois zoomé, remise à 1x automatique dès que le
// pincement repasse sous 1x — pas de double-tap dédié pour l'instant, pincer suffit dans les deux
// sens.
@Composable
private fun ZoomableAsyncPhoto(file: File, onZoomedChanged: (Boolean) -> Unit) {
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale > 1f) { onZoomedChanged(scale > 1f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Clé `file` et non `Unit` : un changement de fichier recrée scale/offset ci-dessus
            // (remember(file)) alors qu'un pointerInput déjà lancé garde sa closure sur les
            // anciens états — la boucle de gestes aurait alors piloté des états orphelins, et la
            // photo affichée n'aurait plus jamais zoomé.
            .pointerInput(file) {
                // detectTransformGestures consomme aussi un simple glissement à un doigt (c'est
                // un pan par définition) — ça cassait le défilement du HorizontalPager parent dès
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
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}

// L'agrandissement se fait autour du centre, donc il dégage (scale - 1) / 2 de chaque côté. Borne
// calculée sur le cadre, pas sur l'image rendue : ContentScale.Fit peut la laisser plus petite que
// son cadre (bandes noires), auquel cas la borne est un peu large — assumé, elle garantit qu'on ne
// peut jamais perdre la photo hors écran, ce qui est le seul rôle qu'on lui demande.
private fun clampToBounds(offset: Offset, scale: Float, widthPx: Int, heightPx: Int): Offset {
    val maxX = widthPx * (scale - 1f) / 2f
    val maxY = heightPx * (scale - 1f) / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

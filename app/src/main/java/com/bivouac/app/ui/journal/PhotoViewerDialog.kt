package com.bivouac.app.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableAsyncPhoto(
                    file = LoggedTrackPhotoStore.resolve(context, photos[page].filePath),
                    onZoomedChanged = { zoomed = it },
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(4.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Fermer", tint = Color.White)
            }
        }
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

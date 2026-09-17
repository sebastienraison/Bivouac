package com.bivouac.app.ui.journal

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import com.bivouac.app.data.db.LoggedTrackPhotoStore
import com.bivouac.app.data.photo.NormalizedCropRect
import com.bivouac.app.data.photo.PhotoAdjustments
import com.bivouac.app.data.photo.adjustedBy
import com.bivouac.app.data.photo.adjustments

/**
 * RIC-143 / RIC-144 : l'éditeur « Ajuster », fidèle à l'écran 1 de la maquette validée le
 * 2026-09-16.
 *
 * C'est le SEUL endroit de l'app où l'image entière reste visible quand elle est recadrée : partout
 * ailleurs, le recadrage EST la photo (voir PhotoAdjustments). Ici l'image entière s'affiche,
 * assombrie hors du cadre, pour qu'on puisse reprendre ce qu'on avait écarté.
 *
 * Rien n'est écrit en base ici : OK rend les ajustements à l'appelant, qui les pose dans le
 * brouillon d'édition en attente de la disquette, au même titre que la note, les tags et les photos
 * (RIC-149). Annuler ferme sans rien rendre.
 *
 * Écrit à la main plutôt que pris sur étagère : la bibliothèque évaluée en conception ne savait pas
 * faire cohabiter le geste des coins (homothétie) et celui des côtés (libre), qui est justement la
 * décision produit de ce lot.
 */
@Composable
internal fun PhotoAdjustDialog(
    photo: LoggedTrackPhotoEntity,
    onCancel: () -> Unit,
    onConfirm: (PhotoAdjustments) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val file = remember(photo.filePath) { LoggedTrackPhotoStore.resolve(context, photo.filePath) }

    // L'état de départ, ce sont les ajustements que la photo porte déjà, brouillon en cours compris
    // (currentPhotos superpose les ajustements en attente aux lignes relues). Rouvrir « Ajuster »
    // reprend donc là où on s'était arrêté, y compris avant d'avoir enregistré.
    var adjustments by remember(photo.id) { mutableStateOf(photo.adjustments) }

    // L'image est affichée TOURNÉE mais ENTIÈRE : le recadrage, lui, est dessiné par-dessus. C'est
    // la seule requête de l'app qui ne demande qu'une partie des ajustements.
    val rotationOnly = PhotoAdjustments(rotationQuarterTurns = adjustments.normalizedRotationQuarterTurns)
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context).data(file).adjustedBy(rotationOnly).build(),
    )

    val touchRadiusPx = with(density) { HANDLE_TOUCH_RADIUS_DP.dp.toPx() }
    val minSidePx = with(density) { MIN_CROP_SIDE_DP.dp.toPx() }
    val handleArmPx = with(density) { HANDLE_ARM_DP.dp.toPx() }
    val handleThicknessPx = with(density) { HANDLE_THICKNESS_DP.dp.toPx() }
    val sideBarLengthPx = with(density) { SIDE_HANDLE_LENGTH_DP.dp.toPx() }
    val borderPx = with(density) { 1.5.dp.toPx() }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImmersiveBlackWindow()
        Column(modifier = Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onCancel) {
                    Text("Annuler", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
                Text("Ajuster", color = Color.White, style = MaterialTheme.typography.titleLarge)
                TextButton(
                    onClick = {
                        onConfirm(adjustments)
                    },
                ) {
                    Text("OK", color = ACCENT, style = MaterialTheme.typography.labelLarge)
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val containerWidth = constraints.maxWidth.toFloat()
                val containerHeight = constraints.maxHeight.toFloat()
                val intrinsic = painter.intrinsicSize
                // Tant que l'image n'est pas décodée, on ne connaît pas ses proportions, donc pas
                // l'emplacement exact où elle se posera : dessiner un cadre avant ça le ferait
                // sauter à l'arrivée de l'image. On attend, sur fond noir.
                val bounds = if (intrinsic.isReady()) {
                    fitInside(containerWidth, containerHeight, intrinsic.width, intrinsic.height)
                } else {
                    null
                }

                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                if (bounds != null) {
                    val frame = (adjustments.cropRect ?: NormalizedCropRect.FULL).toFrame(bounds)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // Clé sur les bornes : une rotation change les proportions de l'image,
                            // donc son emplacement, et une boucle de gestes déjà lancée garderait
                            // les anciennes bornes dans sa fermeture (même piège que le pointerInput
                            // de la visionneuse, voir PhotoViewerDialog).
                            .pointerInput(bounds) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val startFrame =
                                        (adjustments.cropRect ?: NormalizedCropRect.FULL).toFrame(bounds)
                                    val handle = cropHandleAt(
                                        down.position.x,
                                        down.position.y,
                                        startFrame,
                                        touchRadiusPx,
                                    ) ?: return@awaitEachGesture
                                    down.consume()
                                    drag(down.id) { change ->
                                        val delta = change.positionChange()
                                        val current =
                                            (adjustments.cropRect ?: NormalizedCropRect.FULL).toFrame(bounds)
                                        val dragged = dragCropHandle(
                                            frame = current,
                                            bounds = bounds,
                                            handle = handle,
                                            dx = delta.x,
                                            dy = delta.y,
                                            minSide = minSidePx,
                                        )
                                        adjustments = adjustments.copy(
                                            cropRect = dragged.toNormalized(bounds).asStoredCrop(),
                                        )
                                        change.consume()
                                    }
                                }
                            },
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                            drawScrim(frame, containerWidth, containerHeight)
                            drawThirds(frame)
                            drawFrameBorder(frame, borderPx)
                            drawCornerHandles(frame, handleArmPx, handleThicknessPx)
                            drawSideHandles(frame, sideBarLengthPx, handleThicknessPx)
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1C19))
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // RIC-180 : « Réinitialiser » n'a de sens que si l'éditeur porte un ajustement, donc
                // grisé sur `isIdentity` (le même test que ce que la base considère comme « rien à
                // enregistrer », voir PhotoAdjustments.isIdentity), et non une égalité stricte à
                // NONE qui laisserait passer un compte de rotation non normalisé (ex. 4 quarts de
                // tour, visuellement identique à 0 mais différent par egalité de data class).
                val canReset = !adjustments.isIdentity
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        RotationButton(
                            icon = Icons.Default.RotateLeft,
                            label = "Tourner à gauche",
                            onClick = { adjustments = adjustments.rotatedLeft() },
                        )
                        RotationButton(
                            icon = Icons.Default.RotateRight,
                            label = "Tourner à droite",
                            onClick = { adjustments = adjustments.rotatedRight() },
                        )
                    }
                    // RIC-180 : remet rotation et cadre à l'identité DANS L'ÉDITEUR seulement. OK
                    // valide ensuite cet état, Annuler restaure l'ajustement précédent : ce bouton ne
                    // touche à rien au-delà de `adjustments`, exactement comme les deux boutons de
                    // rotation à sa gauche.
                    TextButton(onClick = { adjustments = PhotoAdjustments.NONE }, enabled = canReset) {
                        Text(
                            "Réinitialiser",
                            color = if (canReset) Color.White else Color.White.copy(alpha = 0.38f),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Text(
                    // RIC-179 : « Intérieur » ajouté au texte d'aide, même style de séparateur
                    // (point médian) que les deux autres, pour annoncer le troisième geste.
                    "Coins : proportions conservées · Côtés : recadrage libre · Intérieur : déplacer",
                    color = Color(0xFFC8C9BC),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Un cadre qui couvre toute l'image n'est PAS un recadrage : il se range en base comme « aucun
 * recadrage » (null), pas comme un rectangle 0..1.
 *
 * Sans ça, ouvrir « Ajuster » et valider sans rien toucher marquerait l'écran comme modifié et
 * écrirait quatre colonnes pour rien. La tolérance couvre l'aller-retour pixels / fractions, qui ne
 * retombe jamais sur 0 et 1 exactement.
 */
private fun NormalizedCropRect.asStoredCrop(): NormalizedCropRect? =
    if (coversWholeImage(FULL_FRAME_TOLERANCE)) null else this

private fun Size.isReady(): Boolean =
    this != Size.Unspecified && width > 0f && height > 0f && !width.isNaN() && !height.isNaN()

@Composable
private fun RotationButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.width(112.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color(0xFF2F312C),
            border = BorderStroke(0.dp, Color.Transparent),
            // 48 dp : au-dessus des 44 dp exigés pour une cible tactile.
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        Text(
            label,
            color = Color(0xFFE2E3D6),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Tout ce qui est hors du cadre est assombri, y compris la zone où l'image ne va pas (elle est noire
 * de toute façon) : quatre bandes, pas un trou dans un rectangle, parce que DrawScope n'a pas de
 * mode « soustraire » sans passer par une couche de composition.
 */
private fun DrawScope.drawScrim(frame: CropFrame, width: Float, height: Float) {
    val scrim = Color.Black.copy(alpha = 0.55f)
    drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(width, frame.top))
    drawRect(scrim, topLeft = Offset(0f, frame.bottom), size = Size(width, height - frame.bottom))
    drawRect(scrim, topLeft = Offset(0f, frame.top), size = Size(frame.left, frame.height))
    drawRect(
        scrim,
        topLeft = Offset(frame.right, frame.top),
        size = Size(width - frame.right, frame.height),
    )
}

/** La grille des tiers : deux traits dans chaque sens, discrets, pour composer sans masquer. */
private fun DrawScope.drawThirds(frame: CropFrame) {
    val color = Color.White.copy(alpha = 0.35f)
    for (step in 1..2) {
        val x = frame.left + frame.width * step / 3f
        val y = frame.top + frame.height * step / 3f
        drawLine(color, Offset(x, frame.top), Offset(x, frame.bottom), strokeWidth = 1f)
        drawLine(color, Offset(frame.left, y), Offset(frame.right, y), strokeWidth = 1f)
    }
}

private fun DrawScope.drawFrameBorder(frame: CropFrame, borderWidth: Float) {
    drawRect(
        Color.White.copy(alpha = 0.95f),
        topLeft = Offset(frame.left, frame.top),
        size = Size(frame.width, frame.height),
        style = Stroke(width = borderWidth),
    )
}

/** Les quatre coins en L, posés à cheval sur l'angle : ce sont les prises qui gardent les proportions. */
private fun DrawScope.drawCornerHandles(frame: CropFrame, arm: Float, thickness: Float) {
    val half = thickness / 2f
    val corners = listOf(
        Triple(frame.left, frame.top, 1f to 1f),
        Triple(frame.right, frame.top, -1f to 1f),
        Triple(frame.left, frame.bottom, 1f to -1f),
        Triple(frame.right, frame.bottom, -1f to -1f),
    )
    for ((x, y, direction) in corners) {
        val (dirX, dirY) = direction
        drawLine(
            Color.White,
            start = Offset(x - dirX * half, y - dirY * half),
            end = Offset(x + dirX * arm, y - dirY * half),
            strokeWidth = thickness,
        )
        drawLine(
            Color.White,
            start = Offset(x - dirX * half, y - dirY * half),
            end = Offset(x - dirX * half, y + dirY * arm),
            strokeWidth = thickness,
        )
    }
}

/** Les quatre côtés en barres, au milieu de chaque bord : ce sont les prises du recadrage libre. */
private fun DrawScope.drawSideHandles(frame: CropFrame, length: Float, thickness: Float) {
    val color = Color.White
    val half = length / 2f
    drawLine(
        color,
        Offset(frame.centerX - half, frame.top),
        Offset(frame.centerX + half, frame.top),
        strokeWidth = thickness,
    )
    drawLine(
        color,
        Offset(frame.centerX - half, frame.bottom),
        Offset(frame.centerX + half, frame.bottom),
        strokeWidth = thickness,
    )
    drawLine(
        color,
        Offset(frame.left, frame.centerY - half),
        Offset(frame.left, frame.centerY + half),
        strokeWidth = thickness,
    )
    drawLine(
        color,
        Offset(frame.right, frame.centerY - half),
        Offset(frame.right, frame.centerY + half),
        strokeWidth = thickness,
    )
}

// Le vert clair de la maquette pour le seul bouton qui valide, sur un fond noir qui n'a pas de
// thème : même parti pris que la visionneuse, qui pose ses couleurs en dur pour la même raison.
private val ACCENT = Color(0xFFB7EFCB)

// 22 dp de rayon, donc 44 dp de cible : le minimum recommandé, et le minimum exigé par la spec.
private const val HANDLE_TOUCH_RADIUS_DP = 22f

// Le cadre ne descend pas sous ça : en dessous, les poignées opposées se recouvrent et le geste
// devient un tirage au sort.
private const val MIN_CROP_SIDE_DP = 64f

private const val HANDLE_ARM_DP = 22f
private const val HANDLE_THICKNESS_DP = 4f
private const val SIDE_HANDLE_LENGTH_DP = 28f

// L'aller-retour pixels / fractions ne retombe jamais sur 0 et 1 exactement : un demi-pour-cent de
// marge suffit à reconnaître « le cadre couvre toute l'image » sans jamais avaler un vrai recadrage.
private const val FULL_FRAME_TOLERANCE = 0.005f

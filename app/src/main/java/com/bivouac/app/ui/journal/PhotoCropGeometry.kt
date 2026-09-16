package com.bivouac.app.ui.journal

import com.bivouac.app.data.photo.NormalizedCropRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * RIC-143 : toute la géométrie de l'éditeur « Ajuster », en fonctions PURES.
 *
 * La composable ne fait que dessiner et router les gestes : ce qu'un doigt fait au cadre se décide
 * ici, où ça se teste sans monter d'écran. C'est la partie qui a des règles (proportions conservées
 * ou non, taille minimale, cadre toujours contenu dans l'image), et donc la partie où une erreur ne
 * se voit pas forcément à l'œil nu en recette.
 *
 * Tout est en PIXELS de l'écran, dans le repère du conteneur qui affiche l'image : c'est la seule
 * unité où « 44 dp de cible tactile » et « ne pas sortir de l'image » veulent dire quelque chose.
 * La conversion vers le rectangle normalisé stocké en base se fait aux frontières, voir
 * [toNormalized] et [toFrame].
 */

/** Un rectangle en pixels d'écran. Volontairement distinct des types Compose : ceci se teste en JVM. */
data class CropFrame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Les huit prises du cadre.
 *
 * Décision du propriétaire (2026-09-16), et c'est le cœur du geste : un COIN met le cadre à
 * l'échelle en conservant ses proportions, un CÔTÉ ne bouge qu'une dimension. Les deux gestes
 * cohabitent sur le même cadre, sans mode à choisir, sans ratio prédéfini. C'est ce mode mixte qui
 * a fait écarter la bibliothèque évaluée en conception : elle ne savait faire que l'un ou l'autre.
 */
enum class CropHandle {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    LEFT,
    TOP,
    RIGHT,
    BOTTOM,
    ;

    val isCorner: Boolean
        get() = this == TOP_LEFT || this == TOP_RIGHT || this == BOTTOM_LEFT || this == BOTTOM_RIGHT
}

/**
 * Quelle poignée ce contact saisit-il, s'il en saisit une ?
 *
 * Les coins passent AVANT les côtés : près d'un coin, les deux zones se recouvrent, et c'est le
 * geste homothétique qu'on veut alors, pas un côté. Un contact loin de tout bord ne saisit rien
 * (null) : l'image est fixe dans cet éditeur, on ajuste le cadre, il n'y a donc rien à faire d'un
 * glissement au milieu.
 *
 * [touchRadius] est un rayon, donc la cible fait deux fois ça de côté : l'appelant passe 22 dp pour
 * obtenir les 44 dp exigés. Les côtés sont saisissables sur TOUTE leur longueur et pas seulement au
 * niveau de la barre dessinée : la barre dit où est la poignée, elle ne doit pas être la seule
 * façon de l'atteindre.
 */
fun cropHandleAt(x: Float, y: Float, frame: CropFrame, touchRadius: Float): CropHandle? {
    val nearLeft = abs(x - frame.left) <= touchRadius
    val nearRight = abs(x - frame.right) <= touchRadius
    val nearTop = abs(y - frame.top) <= touchRadius
    val nearBottom = abs(y - frame.bottom) <= touchRadius
    val withinX = x >= frame.left - touchRadius && x <= frame.right + touchRadius
    val withinY = y >= frame.top - touchRadius && y <= frame.bottom + touchRadius

    if (nearLeft && nearTop) return CropHandle.TOP_LEFT
    if (nearRight && nearTop) return CropHandle.TOP_RIGHT
    if (nearLeft && nearBottom) return CropHandle.BOTTOM_LEFT
    if (nearRight && nearBottom) return CropHandle.BOTTOM_RIGHT
    if (nearLeft && withinY) return CropHandle.LEFT
    if (nearRight && withinY) return CropHandle.RIGHT
    if (nearTop && withinX) return CropHandle.TOP
    if (nearBottom && withinX) return CropHandle.BOTTOM
    return null
}

/**
 * Le cadre après un déplacement de [dx], [dy] sur la poignée saisie.
 *
 * Deux gestes, et deux seulement :
 * - COIN : homothétie autour du coin OPPOSÉ, donc les proportions du cadre sont conservées. Le
 *   facteur d'échelle est la projection du déplacement sur la diagonale : tirer perpendiculairement
 *   à la diagonale ne change rien, ce qui est exactement ce qu'on attend d'une mise à l'échelle.
 * - CÔTÉ : un seul bord bouge, les trois autres ne bougent pas. Recadrage libre.
 *
 * Dans les deux cas, deux invariants ne sont jamais violés : le cadre reste entièrement dans
 * [bounds] (l'image affichée), et aucun de ses côtés ne descend sous [minSide]. Ils sont appliqués
 * en bornant le geste, pas en le refusant : tirer au-delà de l'image fait buter le cadre sur le
 * bord, il ne se fige pas au premier pixel de trop.
 */
fun dragCropHandle(
    frame: CropFrame,
    bounds: CropFrame,
    handle: CropHandle,
    dx: Float,
    dy: Float,
    minSide: Float,
): CropFrame = if (handle.isCorner) {
    scaleAroundOppositeCorner(frame, bounds, handle, dx, dy, minSide)
} else {
    moveSide(frame, bounds, handle, dx, dy, minSide)
}

private fun moveSide(
    frame: CropFrame,
    bounds: CropFrame,
    handle: CropHandle,
    dx: Float,
    dy: Float,
    minSide: Float,
): CropFrame = when (handle) {
    CropHandle.LEFT -> frame.copy(
        left = (frame.left + dx).coerceIn(bounds.left, frame.right - minSide),
    )
    CropHandle.RIGHT -> frame.copy(
        right = (frame.right + dx).coerceIn(frame.left + minSide, bounds.right),
    )
    CropHandle.TOP -> frame.copy(
        top = (frame.top + dy).coerceIn(bounds.top, frame.bottom - minSide),
    )
    CropHandle.BOTTOM -> frame.copy(
        bottom = (frame.bottom + dy).coerceIn(frame.top + minSide, bounds.bottom),
    )
    else -> frame
}

private fun scaleAroundOppositeCorner(
    frame: CropFrame,
    bounds: CropFrame,
    handle: CropHandle,
    dx: Float,
    dy: Float,
    minSide: Float,
): CropFrame {
    // Le coin qui ne bouge pas, et le vecteur qui va de lui vers le coin saisi.
    val anchorX = if (handle == CropHandle.TOP_LEFT || handle == CropHandle.BOTTOM_LEFT) frame.right else frame.left
    val anchorY = if (handle == CropHandle.TOP_LEFT || handle == CropHandle.TOP_RIGHT) frame.bottom else frame.top
    val vectorX = (if (handle == CropHandle.TOP_LEFT || handle == CropHandle.BOTTOM_LEFT) frame.left else frame.right) - anchorX
    val vectorY = (if (handle == CropHandle.TOP_LEFT || handle == CropHandle.TOP_RIGHT) frame.top else frame.bottom) - anchorY
    val squaredLength = vectorX * vectorX + vectorY * vectorY
    if (squaredLength <= 0f) return frame

    // Projection du déplacement sur la diagonale : c'est ce qui donne « les proportions sont
    // conservées » sans avoir à décider quel axe commande l'autre.
    val scale = 1f + (dx * vectorX + dy * vectorY) / squaredLength

    // Plancher : aucun côté sous minSide. Plafond : le coin mobile reste dans l'image.
    val minScale = max(minSide / abs(vectorX), minSide / abs(vectorY))
    val maxScaleX = if (vectorX > 0f) (bounds.right - anchorX) / vectorX else (bounds.left - anchorX) / vectorX
    val maxScaleY = if (vectorY > 0f) (bounds.bottom - anchorY) / vectorY else (bounds.top - anchorY) / vectorY
    val maxScale = min(maxScaleX, maxScaleY)
    // minScale peut dépasser maxScale sur une image plus petite que la taille minimale demandée :
    // le plafond gagne alors, parce que sortir de l'image est le seul des deux défauts qui se voit.
    val clamped = scale.coerceAtMost(maxScale).coerceAtLeast(min(minScale, maxScale))

    val cornerX = anchorX + clamped * vectorX
    val cornerY = anchorY + clamped * vectorY
    return CropFrame(
        left = min(anchorX, cornerX),
        top = min(anchorY, cornerY),
        right = max(anchorX, cornerX),
        bottom = max(anchorY, cornerY),
    )
}

/**
 * Où l'image vient se poser dans son conteneur, en « contenir » (ContentScale.Fit) : centrée, mise
 * à l'échelle du plus contraignant des deux côtés.
 *
 * Recalculé ici plutôt que lu sur le composable Image : le cadre de recadrage, l'assombrissement et
 * les poignées doivent coïncider au pixel près avec l'image affichée, et le seul moyen d'en être
 * sûr est que les deux viennent de la même formule.
 */
fun fitInside(containerWidth: Float, containerHeight: Float, contentWidth: Float, contentHeight: Float): CropFrame {
    if (contentWidth <= 0f || contentHeight <= 0f || containerWidth <= 0f || containerHeight <= 0f) {
        return CropFrame(0f, 0f, containerWidth, containerHeight)
    }
    val scale = min(containerWidth / contentWidth, containerHeight / contentHeight)
    val width = contentWidth * scale
    val height = contentHeight * scale
    val left = (containerWidth - width) / 2f
    val top = (containerHeight - height) / 2f
    return CropFrame(left, top, left + width, top + height)
}

/** Le cadre écran, ramené en fractions de l'image affichée : c'est ce qui entre en base. */
fun CropFrame.toNormalized(bounds: CropFrame): NormalizedCropRect {
    if (bounds.width <= 0f || bounds.height <= 0f) return NormalizedCropRect.FULL
    return NormalizedCropRect(
        left = (left - bounds.left) / bounds.width,
        top = (top - bounds.top) / bounds.height,
        right = (right - bounds.left) / bounds.width,
        bottom = (bottom - bounds.top) / bounds.height,
    ).sanitized()
}

/** L'inverse : le rectangle stocké, replacé sur l'image telle qu'elle est affichée maintenant. */
fun NormalizedCropRect.toFrame(bounds: CropFrame): CropFrame {
    val safe = sanitized()
    return CropFrame(
        left = bounds.left + safe.left * bounds.width,
        top = bounds.top + safe.top * bounds.height,
        right = bounds.left + safe.right * bounds.width,
        bottom = bounds.top + safe.bottom * bounds.height,
    )
}

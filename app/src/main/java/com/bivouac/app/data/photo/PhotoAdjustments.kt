package com.bivouac.app.data.photo

import com.bivouac.app.data.db.LoggedTrackPhotoEntity

/**
 * RIC-143 / RIC-144 : ce que l'utilisateur a demandé à voir d'une photo, sans jamais toucher au
 * fichier.
 *
 * Deux ajustements, et un seul principe : ils sont NON DESTRUCTIFS. Le fichier reste l'original
 * octet pour octet (c'est ce que RIC-157 va rechercher dans la galerie, et ce qu'une sauvegarde
 * emporte), les ajustements sont des attributs d'affichage en base. Défaire un recadrage ne coûte
 * donc aucune perte de qualité, et il n'existe aucun moment où une photo puisse être abîmée par un
 * geste d'édition.
 *
 * Ils s'appliquent dans cet ordre, et l'ordre compte :
 * 1. l'orientation EXIF, que le décodeur applique déjà tout seul (Coil, la galerie du système, tout
 *    le monde) : elle n'est pas un ajustement, c'est la façon dont la photo se lit ;
 * 2. [rotationQuarterTurns], un DELTA par-dessus, en quarts de tour horaires ;
 * 3. [cropRect], exprimé dans le repère de l'image déjà tournée.
 *
 * Décision produit (2026-09-16) : le recadrage DEVIENT la photo, partout. Vignettes, bulle de la
 * carte, visionneuse : toutes ne montrent que la zone retenue, comme s'il s'agissait d'une image à
 * part entière. L'image entière n'est visible que dans l'éditeur « Ajuster ».
 */
data class PhotoAdjustments(
    val rotationQuarterTurns: Int = 0,
    val cropRect: NormalizedCropRect? = null,
) {
    /**
     * Le nombre de quarts de tour ramené dans 0..3, y compris pour un compte négatif (« tourner à
     * gauche » depuis 0 donne -1, qui vaut 3).
     *
     * Toute la mécanique de rendu passe par ici plutôt que par le champ brut : c'est ce qui rend
     * inoffensif un compte accumulé sans borne côté éditeur, où l'on peut appuyer dix fois de suite
     * sur le même bouton.
     */
    val normalizedRotationQuarterTurns: Int
        get() = ((rotationQuarterTurns % 4) + 4) % 4

    /**
     * Aucun ajustement : l'image s'affiche telle que le décodeur la rend.
     *
     * C'est le cas de l'écrasante majorité des photos, et le seul cas où l'on peut se dispenser de
     * toute transformation. La chaîne de rendu s'en sert pour ne PAS poser de transformation Coil
     * du tout : un no-op qui recopierait un bitmap pour rien reste un no-op qui coûte.
     */
    val isIdentity: Boolean
        get() = normalizedRotationQuarterTurns == 0 && cropRect == null

    /** Tourner à gauche : un quart de tour anti-horaire, le cadre de recadrage suit l'image. */
    fun rotatedLeft(): PhotoAdjustments = rotatedBy(-1)

    /** Tourner à droite : un quart de tour horaire, le cadre de recadrage suit l'image. */
    fun rotatedRight(): PhotoAdjustments = rotatedBy(1)

    /**
     * Le cadre de recadrage vit dans le repère de l'image TOURNÉE : tourner l'image sans tourner le
     * cadre ferait sauter le recadrage ailleurs sur la photo, ou le rendrait invalide (un cadre
     * paysage dans une image devenue portrait). Le cadre pivote donc avec elle, et la zone retenue
     * reste exactement la même portion de paysage.
     *
     * Décidé en conception : surtout pas de réinitialisation du cadre à chaque rotation. Recadrer
     * puis redresser est un enchaînement naturel ; perdre le cadre au passage serait une punition.
     */
    private fun rotatedBy(quarterTurns: Int): PhotoAdjustments = PhotoAdjustments(
        rotationQuarterTurns = normalizeQuarterTurns(rotationQuarterTurns + quarterTurns),
        cropRect = cropRect?.rotatedByQuarterTurns(quarterTurns),
    )

    companion object {
        val NONE = PhotoAdjustments()

        fun normalizeQuarterTurns(quarterTurns: Int): Int = ((quarterTurns % 4) + 4) % 4
    }
}

/**
 * RIC-143 : la zone retenue, en fractions de 0 à 1 des côtés de l'image TOURNÉE.
 *
 * Normalisé et non en pixels, parce que la même photo est affichée depuis deux fichiers de
 * dimensions différentes : la copie locale (éventuellement réduite, RIC-157) et l'original en
 * pleine résolution que la visionneuse fait apparaître en fondu par-dessus. Un rectangle en pixels
 * décrirait deux cadrages différents, et la photo sauterait au fondu ; une fraction décrit le même
 * cadrage sur les deux, quelles que soient leurs tailles.
 *
 * [left] < [right] et [top] < [bottom], toujours : c'est [sanitized] qui le garantit, et c'est la
 * forme sous laquelle un rectangle entre en base.
 */
data class NormalizedCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Le rectangle couvre-t-il l'image entière ? Alors il n'y a rien à rogner. */
    val isFullFrame: Boolean
        get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    /**
     * Remis dans les clous : bornes dans 0..1, côtés remis dans le bon ordre, et une largeur/hauteur
     * jamais nulle.
     *
     * Appelé à la lecture de la base autant qu'à l'écriture : une ligne peut venir d'une
     * restauration de sauvegarde, donc d'un fichier qu'on n'a pas écrit soi-même, et un rectangle
     * dégénéré ferait planter le découpage du bitmap plutôt que d'afficher quelque chose.
     */
    fun sanitized(): NormalizedCropRect {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        val b = bottom.coerceIn(0f, 1f)
        val (minX, maxX) = if (l <= r) l to r else r to l
        val (minY, maxY) = if (t <= b) t to b else b to t
        return NormalizedCropRect(
            left = minX.coerceAtMost(1f - MIN_SIDE),
            top = minY.coerceAtMost(1f - MIN_SIDE),
            right = maxX.coerceAtLeast(minX + MIN_SIDE).coerceAtMost(1f),
            bottom = maxY.coerceAtLeast(minY + MIN_SIDE).coerceAtMost(1f),
        )
    }

    /**
     * Le même rectangle, vu depuis l'image tournée de [quarterTurns] quarts de tour horaires.
     *
     * Un quart de tour horaire envoie le point normalisé (x, y) sur (1 - y, x) : le coin haut-gauche
     * de l'image part en haut à droite. Le reste se déduit en répétant, ce qui évite quatre
     * formules à vérifier une à une.
     */
    fun rotatedByQuarterTurns(quarterTurns: Int): NormalizedCropRect {
        var result = this
        repeat(PhotoAdjustments.normalizeQuarterTurns(quarterTurns)) { result = result.rotatedOnce() }
        return result
    }

    private fun rotatedOnce(): NormalizedCropRect = NormalizedCropRect(
        left = 1f - bottom,
        top = left,
        right = 1f - top,
        bottom = right,
    )

    companion object {
        /** L'image entière : ce dont l'éditeur repart quand la photo n'a jamais été recadrée. */
        val FULL = NormalizedCropRect(0f, 0f, 1f, 1f)

        /**
         * Le plancher, en fraction, sous lequel un cadre ne descend pas. Il ne dit pas ce qui est
         * confortable à manipuler (c'est l'éditeur qui le sait, en dp), il garantit seulement qu'un
         * rectangle stocké reste découpable en pixels quelle que soit la taille du bitmap.
         */
        const val MIN_SIDE = 0.01f
    }
}

/** Un rectangle en pixels, bornes incluses à gauche/en haut et exclues à droite/en bas. */
data class CropPixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Les dimensions de ce que l'utilisateur voit, à partir de celles du fichier décodé (EXIF déjà
 * appliqué). Un nombre impair de quarts de tour échange largeur et hauteur : c'est tout.
 */
fun PhotoAdjustments.displayedSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
    val (rotatedWidth, rotatedHeight) = rotatedSize(sourceWidth, sourceHeight)
    val crop = cropRect?.sanitized() ?: return rotatedWidth to rotatedHeight
    val pixels = cropPixelRect(rotatedWidth, rotatedHeight, crop)
    return pixels.width to pixels.height
}

/** Les dimensions après rotation seule, avant recadrage. */
fun PhotoAdjustments.rotatedSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> =
    if (normalizedRotationQuarterTurns % 2 == 0) {
        sourceWidth to sourceHeight
    } else {
        sourceHeight to sourceWidth
    }

/**
 * Le rectangle en pixels à découper dans l'image DÉJÀ TOURNÉE.
 *
 * Bornes : jamais hors de l'image, jamais vide. L'arrondi se fait vers l'intérieur pour le coin
 * haut-gauche et vers l'extérieur pour le coin bas-droit, puis on garantit au moins un pixel de
 * côté : sur une vignette de 72 dp recadrée serré, la fraction peut valoir moins d'un pixel, et
 * Bitmap.createBitmap refuse une largeur nulle par une exception, pas par une image vide.
 */
fun cropPixelRect(width: Int, height: Int, crop: NormalizedCropRect): CropPixelRect {
    val safe = crop.sanitized()
    val left = (safe.left * width).toInt().coerceIn(0, (width - 1).coerceAtLeast(0))
    val top = (safe.top * height).toInt().coerceIn(0, (height - 1).coerceAtLeast(0))
    val right = Math.round(safe.right * width).coerceIn(left + 1, width)
    val bottom = Math.round(safe.bottom * height).coerceIn(top + 1, height)
    return CropPixelRect(left, top, right, bottom)
}

/**
 * RIC-143 / RIC-144 : les ajustements portés par une ligne de la base, vus comme un objet.
 *
 * Les quatre colonnes de recadrage valent null ensemble (voir LoggedTrackPhotoEntity) ; une ligne
 * qui n'en porterait qu'une partie (restauration d'une sauvegarde bricolée, futur bug) est traitée
 * comme « pas de recadrage » plutôt que de composer un rectangle à partir de trous.
 */
val LoggedTrackPhotoEntity.adjustments: PhotoAdjustments
    get() {
        val l = cropLeft
        val t = cropTop
        val r = cropRight
        val b = cropBottom
        val crop = if (l != null && t != null && r != null && b != null) {
            NormalizedCropRect(l, t, r, b).sanitized()
        } else {
            null
        }
        return PhotoAdjustments(
            rotationQuarterTurns = PhotoAdjustments.normalizeQuarterTurns(rotationQuarterTurns),
            cropRect = crop,
        )
    }

/**
 * La même ligne, portant d'autres ajustements. Sert à l'affichage pendant une édition : les
 * ajustements en attente se superposent aux lignes relues sans rien écrire en base (voir
 * JournalViewModel.currentPhotos), exactement comme les ajouts en transit se superposent déjà.
 */
fun LoggedTrackPhotoEntity.withAdjustments(adjustments: PhotoAdjustments): LoggedTrackPhotoEntity {
    val crop = adjustments.cropRect?.sanitized()
    return copy(
        rotationQuarterTurns = adjustments.normalizedRotationQuarterTurns,
        cropLeft = crop?.left,
        cropTop = crop?.top,
        cropRight = crop?.right,
        cropBottom = crop?.bottom,
    )
}

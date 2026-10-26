package com.bivouac.app.ui.map

/**
 * RIC-216 : où se posent les étiquettes de durée des pauses sur la carte d'analyse, quand deux
 * pauses sont proches et que leurs étiquettes se recouvriraient.
 *
 * Fonction pure, sans Canvas ni MapView, pour être vérifiée en JVM : [AnalysisPauseLabelsOverlay]
 * (HikeMapView.kt) projette les marqueurs en pixels écran, mesure les textes, appelle [stack] puis
 * dessine les rectangles rendus. Le calcul se refait à chaque `draw` : le zoom change les positions
 * écran, donc aussi les recouvrements, et rien n'est mis en cache.
 *
 * Règle (décision de Seb) : on empile. Une étiquette dont le rectangle en recouvre un déjà placé
 * monte juste au-dessus de lui (avec un petit écart, `gap` dans [stack]), et ainsi de suite jusqu'à
 * ne plus rien recouvrir. Elle garde l'abscisse de son marqueur : seule la hauteur change.
 *
 * Ordre de placement : celui de la liste fournie, qui suit la trace (jours dans l'ordre, pauses dans
 * l'ordre). Il est déterministe et ne dépend pas du zoom, donc l'empilement ne saute pas d'une
 * étiquette à l'autre quand on zoome ; la pause la plus ancienne garde sa place naturelle et les
 * suivantes montent. Un tri par position écran (par exemple de bas en haut) changerait avec le
 * zoom et ferait clignoter l'ordre des étiquettes.
 */
internal object PauseLabelLayout {

    /** Un marqueur de pause projeté à l'écran : [x], [y] en pixels, et la largeur du texte mesuré. */
    data class Anchor(val x: Float, val y: Float, val textWidth: Float)

    /** Un rectangle d'étiquette en pixels écran ([top] < [bottom]). */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        /** Recouvrement strict : deux rectangles qui se touchent seulement par un bord ne se recouvrent pas. */
        fun overlaps(other: Box): Boolean =
            left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }

    /**
     * Un rectangle par élément de [anchors], dans le même ordre.
     *
     * Position de départ : le bas du rectangle est [baseOffset] au-dessus du marqueur, sa hauteur
     * est [height], et il déborde de [horizontalPadding] de chaque côté du texte. Tant qu'il en
     * recouvre un déjà placé, il est remonté juste au-dessus du plus haut de ceux qu'il recouvre
     * (bas du rectangle = haut de celui-là moins [gap]). Chaque remontée strictement vers le haut
     * et les positions possibles étant en nombre fini, la boucle termine.
     */
    fun stack(
        anchors: List<Anchor>,
        horizontalPadding: Float,
        height: Float,
        baseOffset: Float,
        gap: Float,
    ): List<Box> {
        val placed = ArrayList<Box>(anchors.size)
        for (anchor in anchors) {
            val left = anchor.x - anchor.textWidth / 2 - horizontalPadding
            val right = anchor.x + anchor.textWidth / 2 + horizontalPadding
            var bottom = anchor.y - baseOffset
            while (true) {
                val candidate = Box(left, bottom - height, right, bottom)
                val highestHit = placed.filter { it.overlaps(candidate) }.minOfOrNull { it.top } ?: break
                bottom = highestHit - gap
            }
            placed += Box(left, bottom - height, right, bottom)
        }
        return placed
    }
}

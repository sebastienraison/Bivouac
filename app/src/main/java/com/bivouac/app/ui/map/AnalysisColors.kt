package com.bivouac.app.ui.map

import androidx.compose.ui.graphics.Color

/**
 * RIC-146 lot 3 : palette du mode Analyse, regroupée dans ce seul fichier pour être retouchée
 * facilement après essai sur téléphone (conception section 6, décision de Seb "réglage final sur
 * un vrai téléphone"). Les trois listes ont 5 couleurs, une par classe (0 à 4) rendue par
 * [com.bivouac.app.data.gpx.AnalysisParameters.paceClassOf]/[slopeClassOf]/[speedClassOf].
 *
 * [neutral] habille un tronçon sans classe (pas de référence, ou trace sans horodatage hors pente) :
 * même couleur sur la carte et sur le profil (lot 4), jamais une des cinq couleurs de classe.
 */
object AnalysisColors {

    /** Allure : de plus lent (classe 0) à plus rapide (classe 4) que la référence. */
    val pace = listOf(
        Color(0xFFB85C12),
        Color(0xFFE39A55),
        Color(0xFF8E9186),
        Color(0xFF6FA8CC),
        Color(0xFF2B6F9E),
    )

    /** Pente : de faible (classe 0) à forte (classe 4), montée et descente confondues. */
    val slope = listOf(
        Color(0xFFA9C6A0),
        Color(0xFFD4B94E),
        Color(0xFFD98E48),
        Color(0xFFA85A1E),
        Color(0xFF5E2E0C),
    )

    /** Vitesse en marche : de lente (classe 0) à rapide (classe 4). */
    val speed = listOf(
        Color(0xFFA9CBE0),
        Color(0xFF7FB2D3),
        Color(0xFF4F93C0),
        Color(0xFF2B72A3),
        Color(0xFF154F7A),
    )

    /** Tronçon sans classe (référence absente, ou trace sans horodatage hors coloration Pente). */
    val neutral = Color(0xFF8E9186)

    /** [classIndex] de 0 à 4, ou `null` (voir [neutral]), pour la [coloring] choisie. */
    fun colorFor(coloring: AnalysisColoring, classIndex: Int?): Color {
        if (classIndex == null) return neutral
        val palette = when (coloring) {
            AnalysisColoring.PACE -> pace
            AnalysisColoring.SLOPE -> slope
            AnalysisColoring.SPEED -> speed
        }
        return palette.getOrElse(classIndex) { neutral }
    }
}

/** Les trois colorations proposées par le mode Analyse (conception section 2, décision de Seb). */
enum class AnalysisColoring { PACE, SLOPE, SPEED }

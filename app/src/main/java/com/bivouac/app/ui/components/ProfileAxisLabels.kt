package com.bivouac.app.ui.components

import kotlin.math.max

/*
 * RIC-215 : résolution des collisions entre libellés des axes du profil d'élévation. Fonctions pures
 * (flottants en pixels, aucun type Compose) pour être testées en JVM. Le composant mesure ses textes,
 * remplit ces structures avec les positions RÉELLEMENT dessinées (après alignement et coerceIn), et
 * ne dessine que ce que la fonction garde : tester sur une position théorique laisserait passer le
 * cas où le coerceIn repousse un libellé sur son voisin.
 */

/** Le libellé de l'axe vertical est centré sur sa ligne puis rabattu dans le graphe : même règle pour
 * la mesure et pour le dessin. */
internal fun altitudeLabelTop(y: Float, labelHeight: Float, plotHeight: Float): Float =
    (y - labelHeight / 2f).coerceIn(0f, max(plotHeight - labelHeight, 0f))

/**
 * Parmi les repères intermédiaires d'altitude [candidates], ceux dont le libellé ne chevauche ni le
 * libellé du minimum, ni celui du maximum, ni celui d'un repère déjà gardé. Le minimum et le maximum
 * exacts sont toujours affichés et ne sont jamais retirés. Distance minimale entre deux libellés :
 * [minGap] (la hauteur mesurée du texte est déjà dans [labelHeight]).
 *
 * Les candidats sont examinés du plus haut au plus bas : en cas de conflit entre deux intermédiaires,
 * le plus haut reste. Le résultat garde l'ordre d'entrée.
 */
internal fun resolveAltitudeMarks(
    min: Double,
    max: Double,
    candidates: List<Double>,
    plotHeight: Float,
    labelHeight: Float,
    minGap: Float,
): List<Double> {
    val range = (max - min).coerceAtLeast(1.0)
    fun top(elevation: Double): Float {
        val y = (plotHeight - (elevation - min) / range * plotHeight).toFloat()
        return altitudeLabelTop(y, labelHeight, plotHeight)
    }

    val occupied = mutableListOf(top(max), top(min))
    val kept = mutableSetOf<Double>()
    candidates.sortedDescending().forEach { elevation ->
        val t = top(elevation)
        val collides = occupied.any { other -> t < other + labelHeight + minGap && other < t + labelHeight + minGap }
        if (!collides) {
            occupied += t
            kept += elevation
        }
    }
    return candidates.filter { it in kept }
}

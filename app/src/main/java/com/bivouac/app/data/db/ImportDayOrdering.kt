package com.bivouac.app.data.db

import java.time.Instant

/**
 * RIC-41 : dans quel ordre les fichiers d'un import multi-jours deviennent les jours 1..N.
 *
 * L'ordre de sélection n'est pas fiable : plusieurs sélecteurs système Android ne conservent pas
 * l'ordre des taps (certains rendent la liste triée alphabétiquement), donc s'y fier revient à
 * numéroter les jours au hasard. La référence est donc l'horodatage du premier point GPX de
 * chaque fichier.
 *
 * Repli assumé : dès qu'un seul fichier du lot n'a pas d'horodatage (GPX n'en impose aucun),
 * l'ordre de sélection reprend la main pour tout le lot, plutôt que d'intercaler ce fichier à une
 * position devinée. Un lot entièrement sans horodatage retombe donc naturellement sur ce même
 * comportement.
 */
object ImportDayOrdering {

    /**
     * Les indices de [firstTimestamps] réordonnés en ordre de jour : le premier élément du
     * résultat est la position, dans la sélection d'origine, du fichier qui devient le jour 1.
     * Le tri est stable, donc deux fichiers au même horodatage gardent leur ordre de sélection.
     */
    fun orderIndices(firstTimestamps: List<Instant?>): List<Int> {
        val selectionOrder = firstTimestamps.indices.toList()
        if (firstTimestamps.any { it == null }) return selectionOrder
        return selectionOrder.sortedBy { firstTimestamps[it] }
    }
}

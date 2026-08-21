package com.bivouac.app.data.model

/**
 * RIC-40 : où tomber les points de bivouac quand une trace multi-jours du Journal est dupliquée
 * vers la Planification. Aucune heuristique n'est nécessaire — la coupure entre deux fichiers
 * importés *est* la nuit passée dehors, donc chaque jonction devient un bivouac.
 *
 * L'index retenu est le dernier point du jour qui se termine, et non le premier du jour suivant :
 * c'est la convention que la Planification applique déjà (un point de bivouac est la borne
 * partagée entre deux segments, cf. GpxImportViewModel.computeSegments), donc les segments
 * obtenus correspondent un pour un aux fichiers d'origine.
 */
object DayJunctions {

    /**
     * Les index, dans la trace concaténée, des points de bivouac déduits de [dayPointCounts]
     * (nombre de points de chaque jour, dans l'ordre des jours). Vide pour une trace d'un seul
     * jour. Un jour vide (ne devrait pas arriver, le parseur refuse un GPX sans point) ne produit
     * pas de jonction en double ni d'index négatif.
     */
    fun bivouacTrackPointIndices(dayPointCounts: List<Int>): List<Int> {
        if (dayPointCounts.size < 2) return emptyList()
        return dayPointCounts.runningFold(0) { total, dayPoints -> total + dayPoints }
            .drop(1)
            .dropLast(1)
            .map { it - 1 }
            .filter { it > 0 }
            .distinct()
    }
}

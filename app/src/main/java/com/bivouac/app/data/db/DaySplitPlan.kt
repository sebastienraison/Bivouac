package com.bivouac.app.data.db

import com.bivouac.app.data.gpx.DetectedBivouac

/**
 * RIC-226 : les plages de points des jours qui remplacent un jour découpé à ses bivouacs (voir
 * [LoggedTrackRepository.splitDay]). Calcul pur, séparé du dépôt pour être testé sans base.
 */
internal object DaySplitPlan {

    /**
     * Plages, en index du FICHIER, des N + 1 jours issus d'un jour de [dayPointCount] points découpé
     * à ses N [bivouacs] : `[0, arrêt 1]`, `[reprise 1, arrêt 2]`, ..., `[reprise N, dernier]` en
     * index du jour, décalés de [fileOffset] (le `firstPointIndex` du jour s'il était déjà une plage,
     * 0 sinon). Contiguës et sans recouvrement : leur concaténation est exactement le jour d'origine.
     *
     * [bivouacs] en index du jour, tels que [com.bivouac.app.data.gpx.BivouacDetector] les rend sur
     * les points de ce jour, dans n'importe quel ordre.
     *
     * @throws IllegalArgumentException si [bivouacs] est vide, si un bivouac sort du jour (arrêt
     * négatif, reprise au-delà du dernier point) ou ne reprend pas au point qui suit son arrêt, ou si
     * deux bivouacs se chevauchent (même arrêt).
     */
    fun fileRanges(dayPointCount: Int, fileOffset: Int, bivouacs: List<DetectedBivouac>): List<IntRange> {
        require(bivouacs.isNotEmpty()) { "Aucun bivouac : rien à découper" }
        val sorted = bivouacs.sortedBy { it.stopIndex }
        for (bivouac in sorted) {
            require(bivouac.stopIndex >= 0 && bivouac.resumeIndex <= dayPointCount - 1) {
                "Bivouac [${bivouac.stopIndex}, ${bivouac.resumeIndex}] hors du jour ($dayPointCount points)"
            }
            require(bivouac.resumeIndex == bivouac.stopIndex + 1) {
                "Bivouac [${bivouac.stopIndex}, ${bivouac.resumeIndex}] : la reprise doit suivre l'arrêt"
            }
        }
        sorted.zipWithNext().forEach { (previous, next) ->
            require(next.stopIndex >= previous.resumeIndex) {
                "Bivouacs qui se chevauchent : [${previous.stopIndex}, ${previous.resumeIndex}] et [${next.stopIndex}, ${next.resumeIndex}]"
            }
        }
        return localRanges(dayPointCount, sorted).map { (it.first + fileOffset)..(it.last + fileOffset) }
    }

    /**
     * RIC-229 : les arrêts refusés (« Pause ») de chacun des jours issus du découpage, dans l'ordre
     * de [fileRanges], en index des points DU MORCEAU. [refusedStops] en index du jour d'origine,
     * comme [bivouacs], supposés déjà validés par [fileRanges].
     *
     * Un refus va au morceau qui contient son arrêt ET sa reprise, ramené à l'origine de ce
     * morceau. Un refus sur un arrêt découpé ici disparaît (la nuit devient une fin de journée,
     * c'est le cas « Bivouac » répondu à une nuit refusée), comme un refus hors du jour.
     */
    fun refusedStopsByPiece(dayPointCount: Int, bivouacs: List<DetectedBivouac>, refusedStops: Set<Int>): List<Set<Int>> =
        localRanges(dayPointCount, bivouacs.sortedBy { it.stopIndex }).map { piece ->
            refusedStops.filter { it >= piece.first && it + 1 <= piece.last }.map { it - piece.first }.toSortedSet()
        }

    private fun localRanges(dayPointCount: Int, sorted: List<DetectedBivouac>): List<IntRange> {
        val local = mutableListOf<IntRange>()
        var start = 0
        for (bivouac in sorted) {
            local += start..bivouac.stopIndex
            start = bivouac.resumeIndex
        }
        local += start until dayPointCount
        return local
    }
}

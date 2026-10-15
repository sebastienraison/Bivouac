package com.bivouac.app.data.db

/**
 * RIC-229 (lot 7 de la 2.7.0, conception RIC-222 §10) : lecture et écriture de la colonne
 * `logged_track_day.refusedBivouacStops`, les arrêts prolongés d'un jour auxquels l'utilisateur a
 * répondu « Pause », en index des points du jour.
 *
 * Format : entiers croissants, sans doublon, séparés par des virgules (« 14,29 »). Un ensemble vide
 * s'écrit `null`, jamais une chaîne vide : une seule forme pour « aucun refus ». À la lecture, nul,
 * vide ou blanc vaut aucun refus, et un élément illisible (négatif, non numérique) est ignoré
 * plutôt que de faire échouer l'ouverture du jour : un refus perdu redevient au pire un bivouac,
 * l'état d'avant toute réponse, que l'action de menu permet de corriger.
 */
object RefusedBivouacStops {

    /** Les arrêts refusés portés par [column], triés. */
    fun decode(column: String?): Set<Int> {
        if (column.isNullOrBlank()) return emptySet()
        return column.split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { index -> index >= 0 } }.toSortedSet()
    }

    /** La valeur de colonne pour [stops] : `null` quand il n'y en a aucun. */
    fun encode(stops: Collection<Int>): String? =
        stops.filter { it >= 0 }.toSortedSet().takeIf { it.isNotEmpty() }?.joinToString(",")
}

/** Les arrêts refusés de ce jour (voir [RefusedBivouacStops]). */
fun LoggedTrackDayEntity.refusedStops(): Set<Int> = RefusedBivouacStops.decode(refusedBivouacStops)

package com.bivouac.app.journal

import com.bivouac.app.data.model.DayJunctions

/**
 * RIC-227 (suite du lot 2) : les points de bivouac d'une rando du Journal, pour les marqueurs de la
 * carte de la vue détail et pour la duplication vers la Planification. Une nuit est un bivouac
 * qu'elle sépare deux jours (jonction entre deux fichiers, ou jour découpé) ou qu'elle soit restée
 * à l'intérieur d'un jour (réponse « Ne pas découper », ou rando pas encore passée au crible).
 */
internal object JournalBivouacPoints {

    /**
     * Index, dans la trace concaténée, des points de bivouac : les jonctions entre jours
     * ([DayJunctions.bivouacTrackPointIndices] sur [dayPointCounts]) et les arrêts des nuits
     * internes ([bivouacStopIndices], voir LoggedTrackDetail.bivouacStopIndices), même convention
     * (dernier point avant la nuit), triés et sans doublon.
     */
    fun trackPointIndices(dayPointCounts: List<Int>, bivouacStopIndices: List<Int>): List<Int> =
        (DayJunctions.bivouacTrackPointIndices(dayPointCounts) + bivouacStopIndices).distinct().sorted()
}

package com.bivouac.app.data.gpx

/**
 * RIC-146 lot 3 : ce que la carte du mode Analyse fait des [DayAnalysis] rendus par
 * [TrackAnalysisCalculator.compute] (conception section 7.2, brief lot 3). Calculateurs purs
 * (aucune dépendance osmdroid/Compose), pour rester vérifiables en test JVM : [HikeMapView]
 * n'en consomme que le résultat.
 */
object TrackAnalysisMapMapping {

    /**
     * Décalage (nombre de points des jours précédents) de chaque jour dans la trace concaténée de
     * l'écran, à partir du nombre de points de chaque jour (même convention que
     * [com.bivouac.app.data.model.DayJunctions.bivouacTrackPointIndices]) : `offsets[i]` est le
     * premier index du jour `i` dans la liste de points affichée.
     */
    fun dayOffsets(dayPointCounts: List<Int>): List<Int> =
        dayPointCounts.runningFold(0) { total, count -> total + count }.dropLast(1)

    /**
     * Index d'un tronçon/pause local au jour [dayIndex] (voir [AnalyzedSegment.startIndex] et
     * [AnalyzedSegment.endIndex], locaux à chaque jour, conception section 5.2), converti vers la
     * liste de points concaténée de l'écran (celle que [com.bivouac.app.ui.map.HikeMapView] trace),
     * en réutilisant les jonctions de jours déjà calculées par [dayOffsets].
     */
    fun toScreenIndex(dayOffsets: List<Int>, dayIndex: Int, localIndex: Int): Int =
        dayOffsets[dayIndex] + localIndex

    /**
     * Un regroupement de tronçons consécutifs de même classe de couleur (brief lot 3 §3 "Regroupe
     * les tronçons consécutifs de même classe en une seule polyligne, pour limiter le nombre de
     * calques") : bornes en index de la trace affichée par l'écran (voir [toScreenIndex]),
     * [colorClass] `null` pour un tronçon sans classe (couleur neutre, brief lot 3 §3).
     */
    data class ColorGroup(val startScreenIndex: Int, val endScreenIndex: Int, val colorClass: Int?)

    /**
     * Regroupe les tronçons d'un jour selon [classOf] (choisit paceClass, slopeClass ou speedClass
     * selon la coloration active) : deux tronçons consécutifs (le [AnalyzedSegment.endIndex] de
     * l'un égale le [AnalyzedSegment.startIndex] du suivant) de même classe (y compris deux `null`
     * consécutifs, tous deux neutres) fusionnent en un seul [ColorGroup]. Un trou entre deux
     * tronçons (ne devrait pas arriver, [TrackSegmenter.segment] couvre toute la trace) ferme le
     * groupe courant plutôt que de fusionner à tort.
     */
    fun colorGroups(
        segments: List<AnalyzedSegment>,
        dayOffsets: List<Int>,
        dayIndex: Int,
        classOf: (AnalyzedSegment) -> Int?,
    ): List<ColorGroup> {
        if (segments.isEmpty()) return emptyList()
        val groups = mutableListOf<ColorGroup>()
        var groupStartLocal = segments.first().startIndex
        var groupEndLocal = segments.first().endIndex
        var groupClass = classOf(segments.first())
        for (segment in segments.drop(1)) {
            val contiguous = segment.startIndex == groupEndLocal
            val sameClass = classOf(segment) == groupClass
            if (contiguous && sameClass) {
                groupEndLocal = segment.endIndex
            } else {
                groups += ColorGroup(
                    toScreenIndex(dayOffsets, dayIndex, groupStartLocal),
                    toScreenIndex(dayOffsets, dayIndex, groupEndLocal),
                    groupClass,
                )
                groupStartLocal = segment.startIndex
                groupEndLocal = segment.endIndex
                groupClass = classOf(segment)
            }
        }
        groups += ColorGroup(
            toScreenIndex(dayOffsets, dayIndex, groupStartLocal),
            toScreenIndex(dayOffsets, dayIndex, groupEndLocal),
            groupClass,
        )
        return groups
    }

    /** Taille d'un marqueur de pause sur la carte et le profil (conception section 7.2/7.4). */
    enum class PauseMarkerKind { DOT, ICON, ICON_WITH_DURATION }

    // Bornes de la conception section 7.2 : "point simple sous 5 min, pictogramme à partir de
    // 5 min, durée affichée à partir de 10 min".
    private const val ICON_THRESHOLD_SECONDS = 5 * 60.0
    private const val DURATION_THRESHOLD_SECONDS = 10 * 60.0

    /** [seconds] : durée de la pause. Bornes inclusives côté "à partir de", comme la conception. */
    fun pauseMarkerKind(seconds: Double): PauseMarkerKind = when {
        seconds < ICON_THRESHOLD_SECONDS -> PauseMarkerKind.DOT
        seconds < DURATION_THRESHOLD_SECONDS -> PauseMarkerKind.ICON
        else -> PauseMarkerKind.ICON_WITH_DURATION
    }
}

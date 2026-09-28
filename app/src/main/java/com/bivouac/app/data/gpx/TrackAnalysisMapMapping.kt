package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * RIC-146 lot 3/4 : ce que la carte et le profil du mode Analyse font des [DayAnalysis] rendus par
 * [TrackAnalysisCalculator.compute] (conception section 7.2/7.4, brief lot 3/4). Calculateurs purs
 * (aucune dépendance osmdroid/Compose), pour rester vérifiables en test JVM : [HikeMapView] et
 * [com.bivouac.app.ui.components.ElevationProfile] n'en consomment que le résultat.
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

    // --- Axe en durée du profil (lot 4, conception section 7.4, brief §5) -----------------------

    /**
     * Inverse de [toScreenIndex] : le jour et l'index local d'un index de l'écran, à partir des
     * décalages [dayOffsets] (mêmes conventions). Le dernier jour dont le décalage ne dépasse pas
     * [screenIndex] est le bon, l'écran étant la concaténation des jours dans l'ordre.
     */
    fun dayAndLocalIndex(dayOffsets: List<Int>, screenIndex: Int): Pair<Int, Int> {
        val dayIndex = dayOffsets.indexOfLast { it <= screenIndex }.coerceAtLeast(0)
        return dayIndex to (screenIndex - dayOffsets[dayIndex])
    }

    /**
     * Le [AnalyzedSegment] de [segments] (triés, contigus, locaux à un jour) qui couvre l'index
     * local [localIndex] : le dernier tronçon dont le début ne dépasse pas cet index, comme la
     * ligne de lecture du profil (brief lot 4 §7) le demande. `null` si [segments] est vide.
     */
    fun segmentAt(segments: List<AnalyzedSegment>, localIndex: Int): AnalyzedSegment? =
        segments.lastOrNull { it.startIndex <= localIndex } ?: segments.firstOrNull()

    /**
     * Temps écoulé, en secondes, depuis le départ de la rando, jour par jour, pour chaque point de
     * la trace concaténée de l'écran (mêmes conventions que [dayOffsets] : [dayBoundaryIndices] est
     * le dernier point de chaque jour qui s'achève). La nuit entre deux jours n'est jamais comptée :
     * chaque jour reprend là où le précédent s'est arrêté sur cet axe (conception section 7.4,
     * "journées bout à bout, sans la nuit"). `null` si un point n'a pas d'horodatage : ne devrait
     * pas arriver quand l'appelant propose la bascule d'axe (une trace porte un horodatage partout
     * ou nulle part, voir [TrackAnalysisCalculator]), auquel cas l'axe reste en distance (brief §5).
     */
    fun elapsedSecondsSinceStart(points: List<TrackPoint>, dayBoundaryIndices: List<Int>): DoubleArray? {
        if (points.isEmpty()) return null
        val result = DoubleArray(points.size)
        val boundaries = (dayBoundaryIndices.sorted() + points.lastIndex).distinct()
        var dayStart = 0
        var carry = 0.0
        for (boundary in boundaries) {
            val end = boundary.coerceIn(dayStart, points.lastIndex)
            val startTime = points[dayStart].time ?: return null
            for (i in dayStart..end) {
                val t = points[i].time ?: return null
                result[i] = carry + (t.toEpochMilli() - startTime.toEpochMilli()) / 1_000.0
            }
            carry = result[end]
            dayStart = end + 1
            if (dayStart > points.lastIndex) break
        }
        return result
    }

    /** Une graduation de l'axe en durée : sa position sur cet axe (secondes écoulées depuis le
     * départ, mêmes valeurs que [elapsedSecondsSinceStart]) et l'heure d'horloge qu'elle porte. */
    data class ClockGridline(val elapsedSeconds: Double, val time: LocalTime)

    // Lu sur la maquette validée (2026-09-28) : l'heure ronde la plus proche du départ (08:11 ->
    // 09:00, à 49 min) comme celle la plus proche de l'arrivée (15:51 -> 15:00, à 51 min) sont
    // absentes, la suivante (1h49, respectivement 1h51) reste. Une heure pleine d'écart, la même
    // unité que l'espacement des graduations elles-mêmes : une marque à moins d'une heure du bord
    // chevaucherait le libellé de départ ou d'arrivée.
    private const val EDGE_GAP_SECONDS = 3_600.0

    /**
     * Graduations en heures d'horloge rondes de l'axe en durée (conception section 7.4, brief §5),
     * une par jour, dans le fuseau [zone] : l'heure de départ et d'arrivée de la rando sont à la
     * charge de l'appelant (toujours dessinées, jamais arrondies, voir [ElevationProfile]). Une
     * graduation à moins de [EDGE_GAP_SECONDS] d'une des deux extrémités de la rando entière est
     * retirée. `null` si un jour n'a pas d'horodatage.
     */
    fun clockGridlines(points: List<TrackPoint>, dayBoundaryIndices: List<Int>, zone: ZoneId): List<ClockGridline>? {
        if (points.isEmpty()) return null
        val boundaries = (dayBoundaryIndices.sorted() + points.lastIndex).distinct()
        val marks = mutableListOf<ClockGridline>()
        var dayStart = 0
        var carry = 0.0
        for (boundary in boundaries) {
            val end = boundary.coerceIn(dayStart, points.lastIndex)
            val startInstant = points[dayStart].time ?: return null
            val endInstant = points[end].time ?: return null
            var hour = startInstant.atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1)
            while (!hour.toInstant().isAfter(endInstant)) {
                val elapsed = carry + (hour.toInstant().toEpochMilli() - startInstant.toEpochMilli()) / 1_000.0
                marks += ClockGridline(elapsed, hour.toLocalTime())
                hour = hour.plusHours(1)
            }
            carry += (endInstant.toEpochMilli() - startInstant.toEpochMilli()) / 1_000.0
            dayStart = end + 1
            if (dayStart > points.lastIndex) break
        }
        val total = carry
        return marks.filter { it.elapsedSeconds > EDGE_GAP_SECONDS && it.elapsedSeconds < total - EDGE_GAP_SECONDS }
    }

    /** Position en secondes écoulées la plus proche de [instant] dans [values] (recherche binaire,
     * même égalité de distance qu'un index de distance : le plus petit index gagne l'égalité). */
    fun nearestIndex(values: DoubleArray, target: Double): Int {
        if (values.isEmpty()) return 0
        var lo = 0
        var hi = values.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (values[mid] < target) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && kotlin.math.abs(values[lo - 1] - target) <= kotlin.math.abs(values[lo] - target)) return lo - 1
        return lo
    }
}

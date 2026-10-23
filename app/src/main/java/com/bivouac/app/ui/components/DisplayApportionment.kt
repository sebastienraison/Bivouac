package com.bivouac.app.ui.components

import com.bivouac.app.data.gpx.TrackStats
import kotlin.math.floor
import kotlin.math.round

/**
 * Comment le total affiché est arrondi au pas : [NEAREST] pour la distance (un chiffre après la
 * virgule en km, [formatKm1]), [TRUNCATE] pour les dénivelés, que l'affichage actuel tronque
 * ([formatGroupedInt] sur un Double passe par `toLong`) au lieu de les arrondir.
 */
enum class TotalRounding { NEAREST, TRUNCATE }

/**
 * RIC-234 : arrondi d'affichage « au plus grand reste » (méthode de Hamilton). Chaque part est
 * rendue en nombre de pas (multiples de [step]) et la somme de ces nombres est exactement le
 * [total] arrondi au pas.
 *
 * Pourquoi : arrondir chaque valeur à part ne se somme pas. Un total de 11,76 km en deux parts de
 * 10,24 et 1,52 km s'affiche 11,8 alors que les parts s'affichent 10,2 et 1,5, soit 11,7. Un
 * lecteur vérifie cette somme de tête. Les calculs eux sont justes (la somme réelle des parts est
 * le total) : cette fonction ne touche ni aux calculs ni aux fichiers exportés, seulement aux
 * valeurs que l'écran affiche.
 *
 * Méthode : chaque part prend sa partie entière en pas ; les pas manquants (total arrondi moins
 * somme des parties entières) vont aux parts de plus grand reste fractionnaire, dans l'ordre
 * décroissant des restes. À égalité, la première de la liste passe d'abord. Une part nulle reste
 * nulle (son reste est nul, elle passe en dernier). Chaque part reste à moins d'un pas de sa valeur
 * réelle.
 *
 * Si les parts ne somment pas le total (cas défensif, non rencontré tant que le total est la somme
 * des parts), les pas en trop sont retirés aux parts de plus petit reste ayant encore un pas, la
 * dernière de la liste d'abord à égalité.
 *
 * @param step pas d'affichage, dans l'unité de [total] et [parts] : 100.0 pour une distance en
 *   mètres affichée au dixième de km, 1.0 pour un dénivelé en mètres.
 */
fun apportionToDisplayedTotal(
    total: Double,
    parts: List<Double>,
    step: Double,
    totalRounding: TotalRounding = TotalRounding.NEAREST,
): List<Long> {
    // i18n-ok : message d'exception technique, jamais affiché (RIC-234).
    require(step > 0.0) { "le pas doit être strictement positif : $step" }
    if (parts.isEmpty()) return emptyList()
    val totalSteps = when (totalRounding) {
        // round (kotlin.math) : arrondi pair sur les égalités exactes, comme le HALF_EVEN de NumberFormat.
        TotalRounding.NEAREST -> round(total / step)
        TotalRounding.TRUNCATE -> floor(total / step)
    }.toLong().coerceAtLeast(0L)
    val quotients = parts.map { (it / step).coerceAtLeast(0.0) }
    val counts = LongArray(parts.size) { floor(quotients[it]).toLong() }
    val remainders = DoubleArray(parts.size) { quotients[it] - counts[it] }

    var gap = totalSteps - counts.sum()
    if (gap > 0) {
        // sortedByDescending est stable : à reste égal, l'ordre de la liste est conservé.
        val order = parts.indices.sortedByDescending { remainders[it] }
        var k = 0
        while (gap > 0) {
            counts[order[k % order.size]]++
            gap--
            k++
        }
    } else if (gap < 0) {
        val order = parts.indices
            .filter { counts[it] > 0 }
            .sortedWith(compareBy<Int> { remainders[it] }.thenByDescending { it })
        // Un tour de la liste retire au plus un pas par part ; on s'arrête quand plus rien n'est retirable.
        while (gap < 0) {
            var removed = false
            for (i in order) {
                if (gap < 0 && counts[i] > 0) {
                    counts[i]--
                    gap++
                    removed = true
                }
            }
            if (!removed) break
        }
    }
    return counts.toList()
}

/** Pas d'affichage de la distance : 0,1 km. */
internal const val DISPLAY_DISTANCE_STEP_METERS = 100.0

/** Pas d'affichage des dénivelés : 1 m. */
internal const val DISPLAY_ELEVATION_STEP_METERS = 1.0

/** Les trois chiffres répartis d'un affichage, en mètres. */
internal data class DisplayFigures(
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
)

/**
 * RIC-234 : [apportionToDisplayedTotal] appliqué aux trois chiffres (distance au dixième de km,
 * D+ et D- au mètre) : les valeurs rendues, multiples du pas, somment exactement le [total]
 * affiché.
 */
internal fun apportionFigures(total: DisplayFigures, parts: List<DisplayFigures>): List<DisplayFigures> {
    val distances = apportionToDisplayedTotal(
        total.distanceMeters,
        parts.map { it.distanceMeters },
        DISPLAY_DISTANCE_STEP_METERS,
    )
    val gains = apportionToDisplayedTotal(
        total.elevationGainMeters,
        parts.map { it.elevationGainMeters },
        DISPLAY_ELEVATION_STEP_METERS,
        TotalRounding.TRUNCATE,
    )
    val losses = apportionToDisplayedTotal(
        total.elevationLossMeters,
        parts.map { it.elevationLossMeters },
        DISPLAY_ELEVATION_STEP_METERS,
        TotalRounding.TRUNCATE,
    )
    return parts.indices.map { i ->
        DisplayFigures(
            distanceMeters = distances[i] * DISPLAY_DISTANCE_STEP_METERS,
            elevationGainMeters = gains[i] * DISPLAY_ELEVATION_STEP_METERS,
            elevationLossMeters = losses[i] * DISPLAY_ELEVATION_STEP_METERS,
        )
    }
}

internal fun TrackStats.toDisplayFigures() = DisplayFigures(distanceMeters, elevationGainMeters, elevationLossMeters)

/**
 * RIC-234 : les statistiques des segments de la Planification telles qu'on les affiche, pour que
 * leur somme affichée tombe sur le [total] affiché. Seules distance, D+ et D- changent ; la durée
 * estimée et l'indicateur d'altitude restent ceux du segment. Sans altitude exploitable
 * ([TrackStats.elevationAvailable] faux sur le total), les dénivelés (0, affichés « indisponible »)
 * ne sont pas touchés.
 */
internal fun apportionedSegmentStats(total: TrackStats, segments: List<TrackStats>): List<TrackStats> {
    val shown = apportionFigures(total.toDisplayFigures(), segments.map { it.toDisplayFigures() })
    return segments.mapIndexed { i, segment ->
        val figures = shown[i]
        if (total.elevationAvailable) {
            segment.copy(
                distanceMeters = figures.distanceMeters,
                elevationGainMeters = figures.elevationGainMeters,
                elevationLossMeters = figures.elevationLossMeters,
            )
        } else {
            segment.copy(distanceMeters = figures.distanceMeters)
        }
    }
}

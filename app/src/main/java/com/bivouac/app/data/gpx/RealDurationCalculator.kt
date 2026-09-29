package com.bivouac.app.data.gpx

import com.bivouac.app.data.db.LoggedTrackDayEntity
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * RIC-209 (brief Partie B, chantier RIC-146 lot 5) : durée réelle d'une ou plusieurs randos, à
 * partir des colonnes déjà écrites par le lot 1 (`logged_track_day.elapsedSeconds`/
 * `pausedSeconds`) plutôt que recalculée : la donnée existe déjà, ce calculateur ne rouvre aucun
 * fichier et ne dépend pas de [TrackStatsCalculator] (l'estimation du lot 2, qui reste le repli
 * quand aucune durée réelle n'existe, brief §Règles "rando sans horodatage").
 *
 * Calculateur pur, aucune I/O : testable en JVM comme [TrackStatsCalculator]/
 * [com.bivouac.app.bilan.BilanStatsCalculator].
 */
object RealDurationCalculator {

    /**
     * Durée réelle d'une rando ou d'un jour. [elapsedSeconds] : somme des `elapsedSeconds` des
     * jours, les nuits ne sont jamais comptées (brief §Règles). [walkingSeconds] : temps de marche
     * (élapsé moins pauses), `null` si au moins un jour n'a pas encore `pausedSeconds` (brief
     * §Règles, rattrapage RIC-146 lot 1 pas encore passé sur cette trace).
     */
    data class RealDuration(val elapsedSeconds: Long, val walkingSeconds: Long?) {
        /**
         * RIC-209 (brief Partie C, lot 9) : part de marche en pour-cent entier, brief §Règles
         * "(élapsé moins pauses) / élapsé, arrondie au pour cent entier". `null` dans les mêmes cas
         * que [walkingSeconds] (pauses pas encore rattrapées sur ce jour).
         */
        val walkingSharePercent: Int?
            get() = walkingSeconds?.takeIf { elapsedSeconds > 0 }
                ?.let { (it.toDouble() / elapsedSeconds * 100).roundToInt() }
    }

    /**
     * `null` si [days] est vide ou si au moins un jour n'a pas d'horodatage exploitable
     * (`elapsedSeconds` nul) : brief §Règles, "rando sans horodatage : l'app n'a pas de durée
     * réelle", l'appelant retombe alors sur l'estimation existante.
     */
    fun forDays(days: List<LoggedTrackDayEntity>): RealDuration? {
        if (days.isEmpty()) return null
        val elapsedTotal = days.sumOf { it.elapsedSeconds ?: return null }
        val walkingTotal = if (days.all { it.pausedSeconds != null }) {
            elapsedTotal - days.sumOf { it.pausedSeconds ?: 0.0 }.roundToLong()
        } else {
            null
        }
        return RealDuration(elapsedTotal, walkingTotal)
    }

    /**
     * Durée réelle agrégée de plusieurs randos (total d'année, cartouche) : brief §Règles, "un
     * total additionne les durées réelles des randos horodatées et les estimations des autres. S'il
     * contient au moins une estimation, il est lui aussi précédé de « ≈ »."
     *
     * [totalSeconds] : somme, rando par rando, de sa durée réelle ([RealDuration.elapsedSeconds])
     * si elle en a une, de son estimation (en secondes, calculée par l'appelant avec la
     * calibration active) sinon. [isEstimated] : vrai si au moins une rando a dû recourir à
     * l'estimation. [walkingSeconds] : somme du temps de marche des SEULES randos horodatées ;
     * `null` si aucune rando n'est horodatée, ou si au moins un jour horodaté n'a pas encore
     * `pausedSeconds` (brief §Règles, seconde ligne du cartouche).
     */
    data class AggregatedDuration(
        val totalSeconds: Long,
        val isEstimated: Boolean,
        val walkingSeconds: Long?,
        // RIC-209 (brief Partie C, lot 9) : somme des elapsedSeconds des SEULES randos horodatées,
        // dénominateur de walkingSharePercent. Distinct de totalSeconds, qui peut mélanger réel et
        // estimé (brief §Règles, "un total qui contient au moins une rando sans horodatage") : la
        // part de marche ne doit compter QUE sur les randos horodatées, jamais sur les estimations.
        val timestampedElapsedSeconds: Long,
    ) {
        /** Même règle d'arrondi que [RealDuration.walkingSharePercent], sur l'agrégat. */
        val walkingSharePercent: Int?
            get() = walkingSeconds?.takeIf { timestampedElapsedSeconds > 0 }
                ?.let { (it.toDouble() / timestampedElapsedSeconds * 100).roundToInt() }
    }

    /**
     * [items] : une paire par rando, sa durée réelle ([forDays], `null` si non horodatée) et
     * l'estimation à utiliser à la place le cas échéant (en secondes, calibration active).
     */
    fun aggregate(items: List<Pair<RealDuration?, Long>>): AggregatedDuration {
        var totalSeconds = 0L
        var anyEstimated = false
        var anyTimestamped = false
        var walkingKnown = true
        var walkingTotal = 0L
        var timestampedElapsedTotal = 0L
        items.forEach { (real, estimatedSeconds) ->
            if (real != null) {
                totalSeconds += real.elapsedSeconds
                anyTimestamped = true
                timestampedElapsedTotal += real.elapsedSeconds
                if (real.walkingSeconds != null) walkingTotal += real.walkingSeconds else walkingKnown = false
            } else {
                totalSeconds += estimatedSeconds
                anyEstimated = true
            }
        }
        return AggregatedDuration(
            totalSeconds = totalSeconds,
            isEstimated = anyEstimated,
            walkingSeconds = if (anyTimestamped && walkingKnown) walkingTotal else null,
            timestampedElapsedSeconds = timestampedElapsedTotal,
        )
    }
}

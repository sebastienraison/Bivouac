package com.bivouac.app.data.gpx

import com.bivouac.app.data.model.TrackPoint
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Un bivouac détecté dans une liste de points : le trou d'enregistrement entre le point
 * [stopIndex] (dernier point avant le trou) et le point [resumeIndex] (premier point après,
 * toujours `stopIndex + 1`). [zone] est le fuseau retenu pour la décision.
 */
data class DetectedBivouac(
    val stopIndex: Int,
    val resumeIndex: Int,
    val stopAt: Instant,
    val resumeAt: Instant,
    val zone: ZoneId,
)

/**
 * RIC-225 (lot 1 de la 2.7.0, conception RIC-222 / RIC-223) : détection des bivouacs dans les points
 * d'un fichier GPX.
 *
 * Règle, fixée par Seb le 14/10/2026 : un trou d'enregistrement d'au moins [MIN_GAP_SECONDS] (4 h)
 * entre deux points consécutifs horodatés, qui touche la fenêtre de nuit de [NIGHT_START_HOUR] h à
 * [NIGHT_END_HOUR] h en heure locale du lieu, est un bivouac.
 *
 *   - trou : deux points consécutifs de la liste, tous deux horodatés. Un point sans heure coupe la
 *     chaîne, aucun trou n'est mesuré à travers lui ;
 *   - durée : au moins 4 h exactement (borne incluse), en secondes réelles ;
 *   - fenêtre de nuit : chaque jour de 19:00:00 inclus à 05:00:00 exclu le lendemain, en heure
 *     locale ;
 *   - touche : l'intervalle fermé [arrêt, reprise] contient au moins un instant de cette fenêtre.
 *     Un trou de 05:00:00 à 09:00:00 ne la touche pas ;
 *   - fuseau : celui du point d'arrêt, l'heure d'été étant gérée par `java.time`.
 *
 * La règle ne regarde ni la distance entre l'arrêt et la reprise, ni le nombre de bivouacs. Les
 * constantes sont nommées et non réglables. La définition de la pause ([TrackPauseDetector]) ne
 * change pas : elle sert à l'Analyse et à la calibration.
 */
object BivouacDetector {

    /** Durée minimale d'un trou d'enregistrement pour être un bivouac : 4 h, bornes incluses. */
    const val MIN_GAP_SECONDS = 4L * 3_600L

    /** Début de la fenêtre de nuit, en heure locale, inclus (19:00:00). */
    const val NIGHT_START_HOUR = 19

    /** Fin de la fenêtre de nuit, en heure locale, le lendemain, exclue (05:00:00). */
    const val NIGHT_END_HOUR = 5

    /** Au-delà de ce trou, une fenêtre de nuit est touchée quel que soit le fuseau ou le changement d'heure. */
    private const val ALWAYS_TOUCHES_SECONDS = 24L * 3_600L

    /**
     * Bivouacs de [points], dans l'ordre. [zoneFor] n'est appelée que pour un trou d'au moins
     * [MIN_GAP_SECONDS], avec le point d'arrêt.
     */
    fun detect(
        points: List<TrackPoint>,
        zoneFor: (TrackPoint) -> ZoneId = LocalZone::forPoint,
    ): List<DetectedBivouac> {
        val found = mutableListOf<DetectedBivouac>()
        for (k in 0 until points.size - 1) {
            val stopAt = points[k].time ?: continue
            val resumeAt = points[k + 1].time ?: continue
            val gap = Duration.between(stopAt, resumeAt)
            if (gap < Duration.ofSeconds(MIN_GAP_SECONDS)) continue
            val zone = zoneFor(points[k])
            if (touchesNight(stopAt, resumeAt, gap, zone)) {
                found += DetectedBivouac(k, k + 1, stopAt, resumeAt, zone)
            }
        }
        return found
    }

    /**
     * Vrai si l'intervalle fermé [[stopAt], [resumeAt]] contient un instant d'une fenêtre de nuit
     * [19:00 d, 05:00 d+1[ en heure locale de [zone]. Les fenêtres des jours locaux de la veille de
     * l'arrêt à la reprise suffisent : la fenêtre de la veille de l'arrêt peut encore le couvrir.
     */
    private fun touchesNight(stopAt: Instant, resumeAt: Instant, gap: Duration, zone: ZoneId): Boolean {
        if (gap.seconds > ALWAYS_TOUCHES_SECONDS) return true
        val first = stopAt.atZone(zone).toLocalDate().minusDays(1)
        val last = resumeAt.atZone(zone).toLocalDate()
        var day: LocalDate = first
        while (!day.isAfter(last)) {
            val windowStart = ZonedDateTime.of(day, LocalTime.of(NIGHT_START_HOUR, 0), zone).toInstant()
            val windowEnd = ZonedDateTime.of(day.plusDays(1), LocalTime.of(NIGHT_END_HOUR, 0), zone).toInstant()
            // Intersection de [stopAt, resumeAt] (fermé) et [windowStart, windowEnd[ non vide.
            if (stopAt < windowEnd && windowStart <= resumeAt) return true
            day = day.plusDays(1)
        }
        return false
    }
}

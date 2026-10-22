package com.bivouac.app.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * RIC-233 : les dates d'une rando, telles que la ligne de dates du Journal ([TrekDatesFormatter])
 * les reçoit, pour la vue détail ([of], depuis les points) comme pour la liste
 * ([ofStartAndElapsed], depuis les colonnes de logged_track_day). Les deux passent par [ofBounds] :
 * une seule règle.
 *
 * Décision de Seb (22/10) : un jour est daté par son premier ET son dernier point horodaté. Les
 * dates d'une rando sont l'union, par jour, de ces deux dates. Jusqu'ici seule la date du premier
 * point comptait : un jour qui finit le lendemain (fichier gardé en une journée par « Pause », nuit
 * de moins de quatre heures) n'affichait que sa date de début, « 31 décembre 2025 » pour une sortie
 * qui finit le 1er janvier. Un jour contenu dans une seule date ne change rien.
 */
object TrekDayDates {

    /**
     * Union, dédoublonnée et triée, des dates du premier et du dernier point horodaté de chaque
     * jour de [days] (les points d'un jour par entrée), dans [zone]. Un jour sans aucun point
     * horodaté est écarté plutôt qu'inventé, comme avant ce ticket.
     */
    fun of(days: List<List<TrackPoint>>, zone: ZoneId): List<LocalDate> =
        ofBounds(
            days.map { points ->
                points.firstOrNull { it.time != null }?.time to points.lastOrNull { it.time != null }?.time
            },
            zone,
        )

    /**
     * Les dates d'une rando pour la liste du Journal, qui n'a pas les points : une paire
     * (startedAtMillis, elapsedSeconds) par jour, colonnes de logged_track_day, dans [zone].
     *
     * La fin du jour est dérivée, sans colonne dédiée (pilotage, 22/10) : startedAtMillis +
     * elapsedSeconds. Nulle si l'une des deux manque (jour sans horodatage, jour d'un seul point,
     * dernier point sans heure, jour pas encore rattrapé) : le jour garde alors sa seule date de
     * début, comme avant ce ticket. Ces deux colonnes sont déjà remplies sur toutes les lignes
     * rattrapées : la correction vaut pour tout le Journal existant, sans migration ni recalcul.
     *
     * Cas limite connu : elapsedSeconds exclut les nuits au bivouac EFFECTIVES du jour
     * (DayBivouacs.elapsedSeconds). Un jour qui contient encore une nuit détectée sans réponse
     * (cas transitoire, avant le crible du premier lancement) a donc une fin dérivée trop tôt, et
     * peut perdre sa dernière date. Une nuit refusée (« Pause ») n'est pas retirée, le cas ne la
     * concerne pas. Marginal aussi : elapsedSeconds est tronqué à la seconde, une fin réelle entre
     * 00:00:00 et 00:00:01 (millisecondes comprises) retomberait la veille.
     */
    fun ofStartAndElapsed(days: List<Pair<Long?, Long?>>, zone: ZoneId): List<LocalDate> =
        ofBounds(
            days.map { (startedAtMillis, elapsedSeconds) ->
                val start = startedAtMillis?.let(Instant::ofEpochMilli)
                val end = if (startedAtMillis != null && elapsedSeconds != null) {
                    Instant.ofEpochMilli(startedAtMillis + elapsedSeconds * 1_000)
                } else {
                    null
                }
                start to end
            },
            zone,
        )

    /** Union, dédoublonnée et triée, des dates de début et de fin connues de chaque jour. */
    private fun ofBounds(days: List<Pair<Instant?, Instant?>>, zone: ZoneId): List<LocalDate> =
        days.flatMap { (start, end) -> listOfNotNull(start, end) }
            .map { it.atZone(zone).toLocalDate() }
            .distinct()
            .sorted()
}

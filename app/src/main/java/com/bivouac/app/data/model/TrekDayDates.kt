package com.bivouac.app.data.model

import java.time.LocalDate
import java.time.ZoneId

/**
 * RIC-233 : les dates d'une rando, telles que la ligne de dates du Journal ([TrekDatesFormatter])
 * les reçoit, tirées des points de chaque jour.
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
        days.flatMap { points ->
            listOfNotNull(
                points.firstOrNull { it.time != null }?.time,
                points.lastOrNull { it.time != null }?.time,
            ).map { it.atZone(zone).toLocalDate() }
        }
            .distinct()
            .sorted()
}

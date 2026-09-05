package com.bivouac.app.data.model

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Met en forme les jours d'une sortie du Journal pour la liste : « 12 et 13 mai 2025 », « 31 mars,
 * 1er et 2 avril 2026 ». Jusqu'ici une sortie de trois jours s'y affichait comme une sortie d'un
 * jour, avec la seule date de départ, et rien ne la distinguait.
 *
 * Objet pur, sans dépendance Android, pour être testable hors appareil : même raison que
 * [com.bivouac.app.data.db.ImportDayOrdering] et [DayJunctions].
 */
object TrekDatesFormatter {

    /**
     * Au-delà de ce nombre de jours, l'énumération cède la place à une plage « du X au Y ».
     * Deux jours se citent, trois s'énumèrent déjà moins bien qu'ils ne s'encadrent, et le gain
     * d'information est nul dès lors que les jours d'un trek sont contigus.
     */
    const val MAX_ENUMERATED_DAYS = 2

    /**
     * [days] dans l'ordre chronologique, une entrée par jour de la sortie. Les jours dont
     * l'horodatage est inconnu (GPX sans date) sont absents de la liste : mieux vaut afficher les
     * dates connues que d'inventer les autres.
     *
     * Renvoie null quand il n'y a rien d'utile à dire, c'est-à-dire aucune date ou une seule : la
     * date de départ seule est déjà affichée par ailleurs, la répéter n'apprendrait rien.
     *
     * Deux jours se citent (« 12 et 13 mai 2025 »), au-delà on encadre (« du 3 au 6 mars 2026 »).
     */
    fun format(days: List<LocalDate>, locale: Locale = Locale.FRANCE): String? {
        val distinct = days.distinct().sorted()
        if (distinct.size < 2) return null
        val first = distinct.first()
        val last = distinct.last()
        // Le premier jour ne porte son mois que si celui d'arrivée diffère, et jamais son année.
        // « du 3 mars au 6 mars 2026 » rallonge sans rien ajouter, alors que « du 30 mars au
        // 1er avril 2020 » a besoin des deux mois.
        //
        // L'année de départ est toujours tue, y compris à cheval sur deux années : une sortie ne
        // dure pas onze mois, donc un changement d'année ne peut être que décembre vers janvier,
        // et « du 31 décembre au 2 janvier 2021 » ne se lit pas autrement.
        val start = if (first.month == last.month && first.year == last.year) {
            dayNumber(first, locale)
        } else {
            dayAndMonth(first, locale)
        }
        val end = full(last, locale)
        return if (distinct.size <= MAX_ENUMERATED_DAYS) "$start et $end" else "du $start au $end"
    }

    private fun full(day: LocalDate, locale: Locale): String =
        "${dayAndMonth(day, locale)} ${day.year}"

    private fun dayAndMonth(day: LocalDate, locale: Locale): String =
        "${dayNumber(day, locale)} ${day.month.getDisplayName(TextStyle.FULL, locale)}"

    // « 1er » et non « 1 » : c'est la forme ordinale attendue en français pour le premier du mois,
    // et elle seule. Les autres langues n'ont pas cette exception, d'où le test sur la locale.
    private fun dayNumber(day: LocalDate, locale: Locale): String =
        if (day.dayOfMonth == 1 && locale.language == "fr") "1er" else day.dayOfMonth.toString()
}

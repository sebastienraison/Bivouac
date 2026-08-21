package com.bivouac.app.data.model

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Met en forme les jours d'une sortie du Journal pour la liste : « 12 et 13 mai 2025 », « 31 mars,
 * 1er et 2 avril 2026 ». Jusqu'ici une sortie de trois jours s'y affichait comme une sortie d'un
 * jour, avec la seule date de départ, et rien ne la distinguait.
 *
 * Objet pur, sans dépendance Android, pour être testable hors appareil — même raison que
 * [com.bivouac.app.data.db.ImportDayOrdering] et [DayJunctions].
 */
object TrekDatesFormatter {

    /**
     * Au-delà de ce nombre de jours, l'énumération cède la place à une plage « du X au Y ».
     * Énumérer six dates tient sur trois lignes dans une liste et se lit moins bien qu'un
     * intervalle, alors que sur deux ou trois jours l'énumération dit exactement ce qui s'est
     * passé, week-end de trois jours compris.
     */
    const val MAX_ENUMERATED_DAYS = 3

    /**
     * [days] dans l'ordre chronologique, une entrée par jour de la sortie. Les jours dont
     * l'horodatage est inconnu (GPX sans date) sont absents de la liste : mieux vaut afficher les
     * dates connues que d'inventer les autres.
     *
     * Renvoie null quand il n'y a rien d'utile à dire, c'est-à-dire aucune date ou une seule — la
     * date de départ seule est déjà affichée par ailleurs, la répéter n'apprendrait rien.
     */
    fun format(days: List<LocalDate>, locale: Locale = Locale.FRANCE): String? {
        val distinct = days.distinct().sorted()
        if (distinct.size < 2) return null
        if (distinct.size > MAX_ENUMERATED_DAYS) {
            return "du ${dayAndMonth(distinct.first(), locale)} au ${full(distinct.last(), locale)}"
        }
        return enumerate(distinct, locale)
    }

    // Le mois et l'année ne se répètent que quand ils changent : « 12 et 13 mai 2025 » plutôt que
    // « 12 mai 2025 et 13 mai 2025 », mais « 31 décembre 2025 et 1er janvier 2026 » en entier.
    private fun enumerate(days: List<LocalDate>, locale: Locale): String {
        val parts = days.mapIndexed { index, day ->
            val next = days.getOrNull(index + 1)
            when {
                next == null -> full(day, locale)
                next.year != day.year -> full(day, locale)
                next.month != day.month -> dayAndMonth(day, locale)
                else -> dayNumber(day, locale)
            }
        }
        return when (parts.size) {
            2 -> "${parts[0]} et ${parts[1]}"
            else -> parts.dropLast(1).joinToString(", ") + " et ${parts.last()}"
        }
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

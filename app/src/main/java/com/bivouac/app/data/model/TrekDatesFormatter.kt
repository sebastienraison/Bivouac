package com.bivouac.app.data.model

import android.content.Context
import com.bivouac.app.R
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Met en forme les jours d'une sortie du Journal pour la liste : « 12 et 13 mai 2025 », « 31 mars,
 * 1er et 2 avril 2026 ». Jusqu'ici une sortie de trois jours s'y affichait comme une sortie d'un
 * jour, avec la seule date de départ, et rien ne la distinguait.
 *
 * RIC-191 (lot 4 i18n) : l'objet prend un [Context] et n'est donc plus testable hors Android,
 * contrairement à [com.bivouac.app.data.db.ImportDayOrdering] et [DayJunctions]. C'était la
 * condition pour sortir la phrase du code : le lot 0 avait explicitement gardé Locale.FRANCE ici
 * plutôt que de produire un résultat mi-français mi-anglais (« 12 et 13 May 2025 ») sur un appareil
 * anglais. Le test correspondant est passé sous Robolectric, avec les deux langues.
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
     *
     * RIC-191 : QUATRE ressources et non des connecteurs isolés (« et », « du », « au ») assemblés
     * par du code commun. L'anglais ne place ni le mois ni l'année au même endroit que le français :
     * un assemblage commun donnerait « 3 and March 6, 2026 ». Chaque langue compose donc sa phrase
     * entière, à partir des mêmes morceaux (jour, mois, année).
     *
     * Le premier jour ne porte son mois que si celui d'arrivée diffère, et jamais son année :
     * « du 3 mars au 6 mars 2026 » rallonge sans rien ajouter, alors que « du 30 mars au
     * 1er avril 2020 » a besoin des deux mois. L'année de départ est toujours tue, y compris à
     * cheval sur deux années : une sortie ne dure pas onze mois, donc un changement d'année ne peut
     * être que décembre vers janvier, et « du 31 décembre au 2 janvier 2021 » ne se lit pas
     * autrement.
     *
     * [locale] pilote le nom des mois ; les ressources, elles, sont choisies par la configuration
     * de [context]. Les deux entrées sont distinctes, d'où le paramètre : un test peut fixer l'une
     * et l'autre indépendamment (voir TrekDatesFormatterTest).
     */
    fun format(
        context: Context,
        days: List<LocalDate>,
        locale: Locale = Locale.getDefault(),
    ): String? {
        val distinct = days.distinct().sorted()
        if (distinct.size < 2) return null
        val first = distinct.first()
        val last = distinct.last()
        val sameMonth = first.month == last.month && first.year == last.year
        val framed = distinct.size > MAX_ENUMERATED_DAYS

        val firstDay = dayNumber(context, first)
        val lastDay = dayNumber(context, last)
        val year = last.year

        return if (sameMonth) {
            val month = monthName(last, locale)
            val template = if (framed) {
                R.string.journal_trek_dates_range_same_month
            } else {
                R.string.journal_trek_dates_pair_same_month
            }
            context.getString(template, firstDay, lastDay, month, year)
        } else {
            val template = if (framed) {
                R.string.journal_trek_dates_range_cross_month
            } else {
                R.string.journal_trek_dates_pair_cross_month
            }
            context.getString(
                template,
                firstDay,
                monthName(first, locale),
                lastDay,
                monthName(last, locale),
                year,
            )
        }
    }

    private fun monthName(day: LocalDate, locale: Locale): String =
        day.month.getDisplayName(TextStyle.FULL, locale)

    // « 1er » et non « 1 » : c'est la forme ordinale attendue en français pour le premier du mois,
    // et elle seule. RIC-191 : l'exception est devenue une ressource (« 1er » en français, « 1 » en
    // anglais), ce qui retire du code le test locale.language == "fr" : une langue ajoutée plus
    // tard n'aura qu'à renseigner sa propre forme.
    private fun dayNumber(context: Context, day: LocalDate): String =
        if (day.dayOfMonth == 1) {
            context.getString(R.string.journal_trek_dates_first_day_of_month)
        } else {
            day.dayOfMonth.toString()
        }
}

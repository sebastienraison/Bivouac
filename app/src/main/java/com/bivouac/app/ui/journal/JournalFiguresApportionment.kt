package com.bivouac.app.ui.journal

import com.bivouac.app.ui.components.DisplayFigures
import com.bivouac.app.ui.components.apportionFigures

/** Le total d'une année et les chiffres de ses randos, dans l'ordre d'affichage. */
internal data class JournalYearFigures(
    val total: DisplayFigures,
    val rows: List<DisplayFigures>,
)

/**
 * RIC-234 : les chiffres que la liste du Journal affiche, répartis de haut en bas pour que chaque
 * niveau somme au niveau du dessus tel qu'il est AFFICHÉ (voir
 * [com.bivouac.app.ui.components.apportionToDisplayedTotal]) : d'abord les années contre la
 * [capsule], puis les randos de chaque année contre la valeur déjà ajustée de leur année.
 *
 * [capsule] null : les années ne sont pas ajustées. C'est le cas d'un filtre par tags actif : la
 * capsule compte alors tout le Journal (RIC-197), pas les années affichées, et leur somme n'a pas à
 * tomber sur elle. Les randos, elles, sont toujours réparties contre le total de leur année.
 *
 * Ne touche qu'aux valeurs affichées : les totaux réels, calculés par somme, restent justes.
 */
internal fun apportionJournalFigures(
    capsule: DisplayFigures?,
    years: List<JournalYearFigures>,
): List<JournalYearFigures> {
    val yearTotals = if (capsule != null) apportionFigures(capsule, years.map { it.total }) else years.map { it.total }
    return years.mapIndexed { i, year ->
        JournalYearFigures(total = yearTotals[i], rows = apportionFigures(yearTotals[i], year.rows))
    }
}

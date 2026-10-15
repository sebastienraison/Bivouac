package com.bivouac.app.ui.gpximport

/**
 * RIC-224 : le calcul des trois crans du tiroir de la Planification (Synthèse, Profil, Détails),
 * isolé du composable pour être testé en JVM avec une barre de navigation de 0 px (gestes, ou
 * paysage) comme de 48 dp (navigation à trois boutons, opaque).
 *
 * Toutes les grandeurs sont en pixels. Les constantes en dp (8 dp sous la Synthèse, 106 dp du bloc
 * profil, 1 dp du séparateur) sont converties par l'appelant, qui détient la densité.
 *
 * Disposition verticale du tiroir, de haut en bas, telle que le composable la dessine :
 * Synthèse (contenu + bande barre de navigation + 8 dp), bloc profil, séparateur de 1 dp, espace
 * de la hauteur de la barre de navigation (inconditionnel, RIC-224), puis, s'il y a des bivouacs,
 * la table des segments (contenu mesuré + padding barre de navigation en bas).
 * Les deux premiers crans montrent les lignes jusqu'à l'espace compris ; la table commence donc
 * exactement sous le bord bas visible du cran Profil, et ne se dessine plus sous la barre.
 */
internal data class PlanificationDrawerHeights(
    /** Hauteur visible du cran Synthèse. */
    val summaryHeightPx: Float,
    /** Hauteur visible du cran Profil (jusqu'au bas de l'espace barre de navigation compris). */
    val profileHeightPx: Float,
    /** Hauteur totale de la feuille, cran Détails déployé. */
    val detailHeightPx: Float,
    /** Plafond de hauteur de la table des segments, qui défile au-delà. */
    val segmentsMaxHeightPx: Float,
)

@Suppress("LongParameterList")
internal fun computePlanificationDrawerHeights(
    fullHeightPx: Float,
    /** Mesure du bloc Synthèse hors padding, 0 tant qu'elle n'est pas arrivée. */
    measuredSummaryHeightPx: Int,
    /** Mesure de la table des segments hors padding barre de navigation, 0 sans bivouac. */
    measuredSegmentsAdditionPx: Int,
    navigationBarHeightPx: Float,
    statusBarHeightPx: Float,
    fallbackSummaryHeightPx: Float,
    summaryBottomMarginPx: Float,
    profileBlockHeightPx: Float,
    dividerHeightPx: Float,
): PlanificationDrawerHeights {
    // Chaque cran se dimensionne sur ce que son contenu mesure, borné seulement par la hauteur de
    // l'écran en filet de sécurité (BIV-57, recette sur appareil : un plafond en fraction
    // d'écran rognait la courbe et la table).
    val summaryHeightPx = (
        if (measuredSummaryHeightPx > 0) {
            measuredSummaryHeightPx.toFloat() + navigationBarHeightPx + summaryBottomMarginPx
        } else {
            fallbackSummaryHeightPx + navigationBarHeightPx
        }
        ).coerceAtMost(fullHeightPx)
    // Séparateur + hauteur de la barre de navigation sous la courbe, comptés dans le cran Profil
    // (RIC-95 : sans eux, les libellés de l'axe X passaient sous la barre). Inconditionnels, avec
    // ou sans bivouac (RIC-95, 2e passe : une réserve conditionnelle faisait sauter le tiroir à
    // l'ajout du premier bivouac). RIC-224 : cette zone est maintenant VIDE dans les deux cas, la
    // table des segments commence en dessous au lieu de la remplir par son haut.
    val profileBottomReservePx = navigationBarHeightPx + dividerHeightPx
    val profileHeightPx = (summaryHeightPx + profileBlockHeightPx + profileBottomReservePx)
        .coerceIn(summaryHeightPx, fullHeightPx)
    // Le cran Détails plafonne juste sous la barre d'état (RIC-95 recette), la table défile
    // au-delà. profileHeightPx contient déjà l'espace barre de navigation : le calcul ne change
    // pas par rapport à avant RIC-224.
    val segmentsMaxHeightPx = (fullHeightPx - profileHeightPx - statusBarHeightPx).coerceAtLeast(0f)
    // La table porte en bas un padding barre de navigation que sa mesure n'inclut pas. Avant
    // RIC-224, la réserve du cran Profil le couvrait (la table commençait dans la réserve) ; la
    // zone est maintenant vide, il faut donc le compter ici, une fois la table mesurée (sans
    // bivouac, measuredSegmentsAdditionPx vaut 0 et il n'y a pas de table).
    val segmentsBottomPaddingPx = if (measuredSegmentsAdditionPx > 0) navigationBarHeightPx else 0f
    val detailHeightPx = (profileHeightPx + measuredSegmentsAdditionPx + segmentsBottomPaddingPx)
        .coerceIn(profileHeightPx, fullHeightPx)
    return PlanificationDrawerHeights(summaryHeightPx, profileHeightPx, detailHeightPx, segmentsMaxHeightPx)
}

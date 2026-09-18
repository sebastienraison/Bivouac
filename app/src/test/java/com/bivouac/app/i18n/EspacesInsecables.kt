package com.bivouac.app.i18n

/**
 * RIC-192 : en français, le séparateur de milliers est une espace insécable, mais son codet dépend
 * de la version d'ICU embarquée (U+202F fine insécable depuis CLDR 34, U+00A0 ordinaire avant).
 * Comparer sur le codet exact ferait échouer ces tests à la prochaine montée d'Android ou de
 * Robolectric sans qu'aucun rendu n'ait changé. Toutes les espaces insécables sont donc ramenées à
 * l'espace ordinaire avant comparaison : ce qui est vérifié, c'est que le groupement a bien eu
 * lieu, pas quel caractère d'espace ICU a choisi.
 */
internal fun String.espacesNormalisees(): String =
    // Codets écrits en \u : invisibles autrement dans le source, et impossibles à relire.
    replace('\u202F', ' ').replace('\u00A0', ' ').replace('\u2009', ' ')

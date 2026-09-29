package com.bivouac.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RIC-210 : l'année de la mention de copyright vient de BuildConfig.BUILD_DATE. Le repli défini
 * est de ne rien afficher (null) : une année inventée ou tirée de l'horloge serait fausse en
 * silence, une ligne absente ne trompe personne.
 */
class BuildYearTest {

    @Test
    fun `date de build nominale, on garde l'annee`() {
        assertEquals("2026", buildYearOrNull("2026-09-29"))
    }

    @Test
    fun `le passage d'annee ne decale rien, on lit l'annee de la date et non un calcul`() {
        assertEquals("2026", buildYearOrNull("2026-01-01"))
        assertEquals("2025", buildYearOrNull("2025-12-31"))
    }

    @Test
    fun `chaine vide, pas d'annee`() {
        assertNull(buildYearOrNull(""))
    }

    @Test
    fun `format inattendu, pas d'annee`() {
        assertNull(buildYearOrNull("unknown"))
        assertNull(buildYearOrNull("29/09/2026"))
        assertNull(buildYearOrNull("26-09-29"))
        assertNull(buildYearOrNull("2026-9-29"))
        assertNull(buildYearOrNull("20260929"))
    }

    @Test
    fun `espaces ou suffixe autour de la date, pas d'annee`() {
        assertNull(buildYearOrNull(" 2026-09-29"))
        assertNull(buildYearOrNull("2026-09-29 "))
        assertNull(buildYearOrNull("2026-09-29T10:00:00Z"))
        assertNull(buildYearOrNull("12026-09-29"))
    }
}

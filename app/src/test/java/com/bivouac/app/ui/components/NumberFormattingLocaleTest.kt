package com.bivouac.app.ui.components

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * RIC-187 (lot 0 i18n) : formatKm1/formatGroupedInt suivent désormais Locale.getDefault() au lieu
 * de Locale.FRANCE figé (NumberFormatting.kt). Locale explicitement posée et restaurée à chaque
 * test (Locale.setDefault dans @Before/@After) : ces tests doivent passer sur une machine réglée
 * dans n'importe quelle locale, pas seulement celle du poste qui les a écrits.
 */
class NumberFormattingLocaleTest {

    private lateinit var originalLocale: Locale

    @Before
    fun sauvegarderLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restaurerLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `formatKm1 utilise la virgule francaise sous Locale FRANCE`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("12,3", formatKm1(12.34))
    }

    @Test
    fun `formatKm1 utilise le point decimal sous Locale US`() {
        Locale.setDefault(Locale.US)
        assertEquals("12.3", formatKm1(12.34))
    }

    @Test
    fun `formatGroupedInt separe les milliers avec l espace insecable francaise`() {
        Locale.setDefault(Locale.FRANCE)
        // Espace fine insécable (U+202F), pas une espace ordinaire : piège RIC-136.
        assertEquals("1 234", formatGroupedInt(1234))
    }

    @Test
    fun `formatGroupedInt separe les milliers avec la virgule anglaise`() {
        Locale.setDefault(Locale.US)
        assertEquals("1,234", formatGroupedInt(1234))
    }
}

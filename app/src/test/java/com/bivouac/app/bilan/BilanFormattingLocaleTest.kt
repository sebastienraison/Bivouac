package com.bivouac.app.bilan

import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * RIC-187 (lot 0 i18n) : formatMonthYear suivait Locale.FRENCH en dur ; suit maintenant
 * Locale.getDefault() (paramètre par défaut, explicité ici pour ne pas dépendre de la locale de la
 * machine qui exécute le test). monthInitial remplace le when-expression figé sur le français par
 * Month.getDisplayName(TextStyle.NARROW, locale), qui donne la même initiale dans les deux langues
 * pour ce jeu de 12 mois (vérifié : "JFMAMJJASOND" en fr comme en en, y compris août/August).
 *
 * formatInsight n'est volontairement PAS testé ici sous Locale.US : il compose une phrase française
 * codée en dur ("Tu sors surtout en ..."), voir le commentaire sur formatInsight dans
 * BilanFormatting.kt -- traduire son résultat sous une autre locale n'a pas de sens avant la
 * migration de l'écran Bilan (lots 1 à 4).
 */
class BilanFormattingLocaleTest {

    private lateinit var originalLocale: Locale
    private val zone = ZoneId.systemDefault()
    private val instant = ZonedDateTime.of(2026, 4, 14, 12, 0, 0, 0, zone).toInstant()

    @Before
    fun sauvegarderLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restaurerLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `formatMonthYear donne le mois en toutes lettres en francais`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("avril 2026", formatMonthYear(instant.toEpochMilli(), zone))
    }

    @Test
    fun `formatMonthYear donne le mois en toutes lettres en anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals("April 2026", formatMonthYear(instant.toEpochMilli(), zone))
    }

    @Test
    fun `monthInitial donne la meme initiale en francais et en anglais`() {
        assertEquals("A", monthInitial(Month.AUGUST, Locale.FRANCE))
        assertEquals("A", monthInitial(Month.AUGUST, Locale.US))
        assertEquals("J", monthInitial(Month.JUNE, Locale.FRANCE))
        assertEquals("J", monthInitial(Month.JUNE, Locale.US))
    }
}

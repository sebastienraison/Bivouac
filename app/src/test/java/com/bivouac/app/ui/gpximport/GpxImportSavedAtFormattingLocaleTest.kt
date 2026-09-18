package com.bivouac.app.ui.gpximport

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RIC-187 (lot 0 i18n) : formatSavedAt suivait Locale.FRANCE (motif "d MMMM" en dur) pour la
 * branche "pas aujourd'hui" ; suit maintenant Locale.getDefault(), avec un motif jour+mois choisi
 * à la main selon la langue ("d MMMM" en français, "MMMM d" en anglais) car FormatStyle n'a pas de
 * style "jour+mois sans année" tout fait. Le préfixe "aujourd'hui à " reste en dur (texte d'écran,
 * migration lots 1 à 4).
 */
class GpxImportSavedAtFormattingLocaleTest {

    private lateinit var originalLocale: Locale
    private val zone = ZoneId.systemDefault()

    @Before
    fun sauvegarderLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restaurerLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `date passee au format jour puis mois en francais`() {
        Locale.setDefault(Locale.FRANCE)
        val instant = ZonedDateTime.of(2020, 8, 3, 10, 0, 0, 0, zone).toInstant()
        assertEquals("3 août", formatSavedAt(instant.toEpochMilli()))
    }

    @Test
    fun `date passee au format mois puis jour en anglais`() {
        Locale.setDefault(Locale.US)
        val instant = ZonedDateTime.of(2020, 8, 3, 10, 0, 0, 0, zone).toInstant()
        assertEquals("August 3", formatSavedAt(instant.toEpochMilli()))
    }

    @Test
    fun `date du jour prefixee par aujourd'hui suivie de l'heure localisee`() {
        Locale.setDefault(Locale.FRANCE)
        val now = ZonedDateTime.now(zone).withHour(19).withMinute(0).withSecond(0).withNano(0)
        assertTrue(now.toLocalDate() == LocalDate.now(zone))
        assertEquals("aujourd'hui à 19:00", formatSavedAt(now.toInstant().toEpochMilli()))
    }
}

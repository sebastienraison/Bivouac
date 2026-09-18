package com.bivouac.app.ui.journal

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * RIC-187 (lot 0 i18n) : formatTimeOfDay/formatDayLabel/formatStartedAt/formatStartedAtWithTime
 * suivaient Locale.FRANCE en dur (motifs "d MMMM yyyy", 'à' littéral) ; ils suivent maintenant
 * Locale.getDefault() avec des styles localisés (DateTimeFormatter.ofLocalizedDate/DateTime).
 *
 * L'instant de test est construit via ZonedDateTime.of(..., ZoneId.systemDefault()) : le résultat
 * ne dépend donc que de la locale (posée explicitement dans @Before/@After), jamais du fuseau
 * horaire de la machine qui exécute le test.
 */
class JournalDateFormattingLocaleTest {

    private lateinit var originalLocale: Locale
    private val zone = ZoneId.systemDefault()
    // Mardi 14 avril 2026, 19:00 : jour de semaine et mois fixés à la main pour que les assertions
    // ci-dessous n'aient pas à recalculer un calendrier.
    private val instant = ZonedDateTime.of(2026, 4, 14, 19, 0, 0, 0, zone).toInstant()

    @Before
    fun sauvegarderLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restaurerLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `formatTimeOfDay en 24h sous Locale FRANCE`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("19:00", formatTimeOfDay(instant))
    }

    @Test
    fun `formatTimeOfDay en 12h AM-PM sous Locale US`() {
        Locale.setDefault(Locale.US)
        // U+202F (espace fine insécable) avant PM, pas une espace ordinaire : donnée par le CLDR
        // du JDK utilisé pour les tests JVM, comme le séparateur de milliers RIC-136 en français.
        assertEquals("7:00 PM", formatTimeOfDay(instant))
    }

    @Test
    fun `formatDayLabel donne le jour de semaine francais avec majuscule`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("Mardi 14", formatDayLabel(instant))
    }

    @Test
    fun `formatDayLabel donne le jour de semaine anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals("Tuesday 14", formatDayLabel(instant))
    }

    @Test
    fun `formatStartedAt donne la date complete en francais`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("14 avril 2026", formatStartedAt(instant.toEpochMilli()))
    }

    @Test
    fun `formatStartedAt donne la date complete en anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals("April 14, 2026", formatStartedAt(instant.toEpochMilli()))
    }

    @Test
    fun `formatStartedAtWithTime combine date et heure en francais`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("14 avril 2026 19:00", formatStartedAtWithTime(instant.toEpochMilli()))
    }

    @Test
    fun `formatStartedAtWithTime combine date et heure en anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals("April 14, 2026, 7:00 PM", formatStartedAtWithTime(instant.toEpochMilli()))
    }
}

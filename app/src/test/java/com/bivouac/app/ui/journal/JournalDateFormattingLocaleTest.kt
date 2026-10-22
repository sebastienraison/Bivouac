package com.bivouac.app.ui.journal

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-187 (lot 0 i18n) : formatTimeOfDay/formatDayLabel/formatStartedAt/formatStartedAtWithTime
 * suivaient Locale.FRANCE en dur (motifs "d MMMM yyyy", 'à' littéral) ; ils passent par des styles
 * localisés (DateTimeFormatter.ofLocalizedDate/DateTime).
 *
 * RIC-245 : la locale n'est plus lue sur la JVM (Locale.getDefault()) mais passée à l'appel (la
 * locale des dates de l'app, AppLocale) : le test la fixe donc à l'appel, mêmes cas qu'avant.
 *
 * L'instant de test est construit via ZonedDateTime.of(..., ZoneId.systemDefault()) : le résultat
 * ne dépend donc que de la locale, jamais du fuseau horaire de la machine qui exécute le test.
 */
class JournalDateFormattingLocaleTest {

    private val zone = ZoneId.systemDefault()
    // Mardi 14 avril 2026, 19:00 : jour de semaine et mois fixés à la main pour que les assertions
    // ci-dessous n'aient pas à recalculer un calendrier.
    private val instant = ZonedDateTime.of(2026, 4, 14, 19, 0, 0, 0, zone).toInstant()

    @Test
    fun `formatTimeOfDay en 24h sous Locale FRANCE`() {
        assertEquals("19:00", formatTimeOfDay(instant, Locale.FRANCE))
    }

    @Test
    fun `formatTimeOfDay en 12h AM-PM sous Locale US`() {
        // U+202F (espace fine insécable) avant PM, pas une espace ordinaire : donnée par le CLDR
        // du JDK utilisé pour les tests JVM, comme le séparateur de milliers RIC-136 en français.
        assertEquals("7:00 PM", formatTimeOfDay(instant, Locale.US))
    }

    @Test
    fun `formatDayLabel donne le jour de semaine francais avec majuscule`() {
        assertEquals("Mardi 14", formatDayLabel(instant, Locale.FRANCE))
    }

    @Test
    fun `formatDayLabel donne le jour de semaine anglais`() {
        assertEquals("Tuesday 14", formatDayLabel(instant, Locale.US))
    }

    @Test
    fun `formatStartedAt donne la date complete en francais`() {
        assertEquals("14 avril 2026", formatStartedAt(instant.toEpochMilli(), Locale.FRANCE))
    }

    @Test
    fun `formatStartedAt donne la date complete en anglais`() {
        assertEquals("April 14, 2026", formatStartedAt(instant.toEpochMilli(), Locale.US))
    }

    @Test
    fun `formatStartedAtWithTime combine date et heure en francais`() {
        assertEquals("14 avril 2026 19:00", formatStartedAtWithTime(instant.toEpochMilli(), Locale.FRANCE))
    }

    @Test
    fun `formatStartedAtWithTime combine date et heure en anglais`() {
        assertEquals("April 14, 2026, 7:00 PM", formatStartedAtWithTime(instant.toEpochMilli(), Locale.US))
    }
}

package com.bivouac.app.ui.settings

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * RIC-187 (lot 0 i18n) : formatBytes et formatBackupTimestamp suivaient Locale.FRANCE en dur.
 *
 * formatBytes : seul le séparateur décimal suit désormais la locale de l'appareil, les unités
 * "Go"/"Mo"/"Ko"/"o" restent en dur (texte d'écran, migration lots 1 à 4 -- voir le commentaire sur
 * formatBytes dans SettingsScreen.kt et settings_format_bytes_go/mo/ko/o dans l'inventaire i18n).
 *
 * formatBackupTimestamp : motif "d MMMM 'à' HH:mm" remplacé par un style localisé
 * (FormatStyle.LONG + FormatStyle.SHORT), comme JournalScreen.formatStartedAtWithTime.
 */
class SettingsFormattingLocaleTest {

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
    fun `formatBytes utilise la virgule francaise, unite en dur`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("12,3 Mo", formatBytes(12_345_678L))
    }

    @Test
    fun `formatBytes utilise le point anglais, meme unite en dur`() {
        Locale.setDefault(Locale.US)
        assertEquals("12.3 Mo", formatBytes(12_345_678L))
    }

    @Test
    fun `formatBytes sous le Ko, sans decimale, inchange par la locale`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("2 Ko", formatBytes(2_000L))
    }

    @Test
    fun `formatBackupTimestamp combine date longue et heure courte en francais`() {
        Locale.setDefault(Locale.FRANCE)
        val instant = ZonedDateTime.of(2026, 4, 14, 19, 0, 0, 0, zone).toInstant()
        assertEquals("14 avril 2026 19:00", formatBackupTimestamp(instant.toEpochMilli()))
    }

    @Test
    fun `formatBackupTimestamp combine date longue et heure courte en anglais`() {
        Locale.setDefault(Locale.US)
        val instant = ZonedDateTime.of(2026, 4, 14, 19, 0, 0, 0, zone).toInstant()
        assertEquals("April 14, 2026, 7:00 PM", formatBackupTimestamp(instant.toEpochMilli()))
    }
}

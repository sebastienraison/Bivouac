package com.bivouac.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIC-187 (lot 0 i18n) : formatBytes et formatBackupTimestamp suivaient Locale.FRANCE en dur.
 *
 * formatBackupTimestamp : motif "d MMMM 'à' HH:mm" remplacé par un style localisé
 * (FormatStyle.LONG + FormatStyle.SHORT), comme JournalScreen.formatStartedAtWithTime.
 *
 * RIC-190 (lot 3 i18n) : les unités "Go"/"Mo"/"Ko"/"o", laissées en dur au lot 0, viennent
 * maintenant des ressources, d'où le Context passé à formatBytes et le passage sous Robolectric
 * avec @Config(qualifiers). La locale JVM est posée EN PLUS des qualifiers : les qualifiers
 * choisissent le fichier de ressources (l'unité), Locale.getDefault() pilote le séparateur décimal
 * de String.format et DateTimeFormatter.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsFormattingLocaleTest {

    private lateinit var originalLocale: Locale
    private val zone = ZoneId.systemDefault()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun sauvegarderLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restaurerLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `formatBytes utilise la virgule et l unite francaises`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("12,3 Mo", formatBytes(context, 12_345_678L))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `formatBytes utilise le point et l unite anglaise`() {
        Locale.setDefault(Locale.US)
        assertEquals("12.3 MB", formatBytes(context, 12_345_678L))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `formatBytes sous le Ko, sans decimale`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("2 Ko", formatBytes(context, 2_000L))
    }

    /** Sous le kilo-octet, l'unité seule change de langue : « o » devient « B ». */
    @Test
    @Config(qualifiers = "en-rUS")
    fun `formatBytes sous le kilo-octet en anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals("512 B", formatBytes(context, 512L))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `formatBackupTimestamp combine date longue et heure courte en francais`() {
        Locale.setDefault(Locale.FRANCE)
        val instant = ZonedDateTime.of(2026, 4, 14, 19, 0, 0, 0, zone).toInstant()
        assertEquals("14 avril 2026 19:00", formatBackupTimestamp(instant.toEpochMilli()))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `formatBackupTimestamp combine date longue et heure courte en anglais`() {
        Locale.setDefault(Locale.US)
        val instant = ZonedDateTime.of(2026, 4, 14, 19, 0, 0, 0, zone).toInstant()
        assertEquals("April 14, 2026, 7:00 PM", formatBackupTimestamp(instant.toEpochMilli()))
    }
}

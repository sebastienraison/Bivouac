package com.bivouac.app.ui.map

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * RIC-187 (lot 0 i18n) : formatPhotoTimeOfDay (bulle du curseur carte) suivait Locale.FRANCE en
 * dur ("HH:mm") ; suit maintenant Locale.getDefault() via DateTimeFormatter.ofLocalizedTime, comme
 * JournalScreen.formatTimeOfDay.
 */
class HikeMapViewPhotoTimeFormattingLocaleTest {

    private lateinit var originalLocale: Locale
    private val zone = ZoneId.systemDefault()
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
    fun `heure de prise de vue en 24h sous Locale FRANCE`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("19:00", formatPhotoTimeOfDay(instant.toEpochMilli()))
    }

    @Test
    fun `heure de prise de vue en 12h AM-PM sous Locale US`() {
        Locale.setDefault(Locale.US)
        assertEquals("7:00 PM", formatPhotoTimeOfDay(instant.toEpochMilli()))
    }
}

package com.bivouac.app.ui.map

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIC-187 (lot 0 i18n) : formatPhotoTimeOfDay (bulle du curseur carte) suivait Locale.FRANCE en
 * dur ("HH:mm") ; suit maintenant Locale.getDefault() via DateTimeFormatter.ofLocalizedTime, comme
 * JournalScreen.formatTimeOfDay.
 *
 * RIC-245 : la locale est passée à l'appel (locale des dates de l'app, AppLocale), plus lue sur la
 * JVM ; le test la fixe donc à l'appel, mêmes cas qu'avant.
 */
class HikeMapViewPhotoTimeFormattingLocaleTest {

    private val zone = ZoneId.systemDefault()
    private val instant = ZonedDateTime.of(2026, 4, 14, 19, 0, 0, 0, zone).toInstant()

    @Test
    fun `heure de prise de vue en 24h sous Locale FRANCE`() {
        assertEquals("19:00", formatPhotoTimeOfDay(instant.toEpochMilli(), Locale.FRANCE))
    }

    @Test
    fun `heure de prise de vue en 12h AM-PM sous Locale US`() {
        assertEquals("7:00 PM", formatPhotoTimeOfDay(instant.toEpochMilli(), Locale.US))
    }
}

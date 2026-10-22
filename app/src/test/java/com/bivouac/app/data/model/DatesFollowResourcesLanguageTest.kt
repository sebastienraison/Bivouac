package com.bivouac.app.data.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.bilan.formatMonthYear
import com.bivouac.app.R
import com.bivouac.app.ui.gpximport.formatSavedAt
import com.bivouac.app.ui.journal.formatBivouacDateTimes
import java.time.LocalDateTime
import java.time.LocalDate
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
 * RIC-245 : le cas du testeur F-Droid (21/10). Sur un appareil allemand, les chaînes retombent sur
 * l'anglais (aucun values-de) mais les dates sortaient en allemand : « From Samstag 18 Juli at
 * 11:42 ». Les dates suivent maintenant la langue des ressources chargées (AppLocale).
 *
 * La locale JVM est posée en plus des qualifiers, comme dans les autres tests de locale du
 * chantier i18n : c'est elle que lisaient les formateurs avant ce ticket, et le test doit prouver
 * qu'elle ne décide plus de la langue des dates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-rDE")
class DatesFollowResourcesLanguageTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.systemDefault()
    private lateinit var originalLocale: Locale

    @Before
    fun poserLocaleAllemande() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restaurerLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `ligne de dates du Journal en anglais sur un appareil allemand`() {
        val days = listOf(LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 19))
        assertEquals("July 18 and 19, 2026", TrekDatesFormatter.format(context, days))
    }

    @Test
    fun `date d'enregistrement de la banque en anglais sur un appareil allemand`() {
        // Mars et non août : « August » s'écrit pareil en allemand, le cas ne prouverait rien.
        val instant = ZonedDateTime.of(2020, 3, 3, 10, 0, 0, 0, zone).toInstant()
        assertEquals("March 3", formatSavedAt(context, instant.toEpochMilli()))
    }

    @Test
    fun `mois du Bilan en anglais sur un appareil allemand`() {
        val instant = ZonedDateTime.of(2026, 7, 14, 12, 0, 0, 0, zone).toInstant()
        assertEquals("July 2026", formatMonthYear(context, instant.toEpochMilli(), zone))
    }

    /** La phrase exacte du testeur, à l'heure près : « From Saturday 18 July at 11:42 ... ». */
    @Test
    fun `instants du dialogue Arret prolonge en anglais sur un appareil allemand`() {
        val paris = ZoneId.of("Europe/Paris")
        val result = formatBivouacDateTimes(
            stopAt = LocalDateTime.parse("2026-07-18T11:42:00").atZone(paris).toInstant(),
            resumeAt = LocalDateTime.parse("2026-07-19T09:30:00").atZone(paris).toInstant(),
            zone = paris,
            locale = AppLocale.of(context),
            pattern = context.getString(R.string.bivouac_detected_datetime_pattern),
            patternWithYear = context.getString(R.string.bivouac_detected_datetime_pattern_with_year),
            firstDayOfMonth = context.getString(R.string.journal_trek_dates_first_day_of_month),
        )
        assertEquals("Saturday 18 July at 11:42", result.stop)
        assertEquals("Sunday 19 July at 09:30", result.resume)
    }
}

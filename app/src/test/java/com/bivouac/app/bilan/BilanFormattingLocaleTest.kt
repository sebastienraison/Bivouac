package com.bivouac.app.bilan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.R
import com.bivouac.app.i18n.espacesNormalisees
import com.bivouac.app.ui.components.formatGroupedInt
import java.time.Month
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
 * RIC-187 (lot 0 i18n) : formatMonthYear suivait Locale.FRENCH en dur ; suit maintenant
 * Locale.getDefault() (paramètre par défaut, explicité ici pour ne pas dépendre de la locale de la
 * machine qui exécute le test). monthInitial remplace le when-expression figé sur le français par
 * Month.getDisplayName(TextStyle.NARROW, locale), qui donne la même initiale dans les deux langues
 * pour ce jeu de 12 mois (vérifié : "JFMAMJJASOND" en fr comme en en, y compris août/August).
 *
 * RIC-190 (lot 3 i18n) : formatMonthYear prend un Context (le « mois année » est un format de
 * ressource), et formatInsight, que le lot 0 avait laissé en français en dur, est maintenant un
 * <plurals> à paramètres : il est donc testable dans les deux langues, ce qu'il n'était pas.
 *
 * D'où RobolectricTestRunner et @Config(qualifiers) : ce qui se vérifie ici est le câblage complet
 * (la bonne ressource selon la langue de l'appareil), pas seulement la formule. La locale JVM est
 * posée EN PLUS des qualifiers, parce que les deux entrées ne sont pas la même : les qualifiers
 * choisissent le fichier de ressources, Locale.getDefault() pilote java.time (nom du mois).
 *
 * RIC-192 : le cumul de sorties de l'insight et le total du Bilan portent le séparateur de
 * milliers de la locale. L'année de l'insight (%3$d), elle, reste un entier brut.
 */
@RunWith(RobolectricTestRunner::class)
class BilanFormattingLocaleTest {

    private lateinit var originalLocale: Locale
    private val zone = ZoneId.systemDefault()
    private val instant = ZonedDateTime.of(2026, 4, 14, 12, 0, 0, 0, zone).toInstant()
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
    fun `formatMonthYear donne le mois en toutes lettres en francais`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("avril 2026", formatMonthYear(context, instant.toEpochMilli(), zone))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `formatMonthYear donne le mois en toutes lettres en anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals("April 2026", formatMonthYear(context, instant.toEpochMilli(), zone))
    }

    @Test
    fun `monthInitial donne la meme initiale en francais et en anglais`() {
        assertEquals("A", monthInitial(Month.AUGUST, Locale.FRANCE))
        assertEquals("A", monthInitial(Month.AUGUST, Locale.US))
        assertEquals("J", monthInitial(Month.JUNE, Locale.FRANCE))
        assertEquals("J", monthInitial(Month.JUNE, Locale.US))
    }

    /** Une seule sortie : la forme "one" du pluriel, et le mois dans la langue de l'appareil. */
    @Test
    @Config(qualifiers = "fr-rFR")
    fun `formatInsight au singulier en francais`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals(
            "Mois le plus actif : juillet (1 sortie depuis 2021)",
            formatInsight(context, MostActiveMonthInsight(monthOfYear = 7, cumulativeCount = 1, sinceYear = 2021)),
        )
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `formatInsight au pluriel en francais`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals(
            "Mois le plus actif : juillet (12 sorties depuis 2021)",
            formatInsight(context, MostActiveMonthInsight(monthOfYear = 7, cumulativeCount = 12, sinceYear = 2021)),
        )
    }

    /**
     * La phrase ET le nom du mois suivent la langue : c'est exactement ce que le lot 0 ne pouvait
     * pas faire, le mois traduit dans une phrase française en dur donnant « You mostly go out en
     * juillet ».
     */
    @Test
    @Config(qualifiers = "en-rUS")
    fun `formatInsight en anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals(
            "Most active month: July (12 hikes since 2021)",
            formatInsight(context, MostActiveMonthInsight(monthOfYear = 7, cumulativeCount = 12, sinceYear = 2021)),
        )
    }

    /**
     * Un cumul à 4 chiffres, le seul cas qui distingue « 1 234 sorties » de « 1234 sorties ».
     * L'année « depuis 2021 » prouve au passage qu'elle n'a PAS été groupée.
     */
    @Test
    @Config(qualifiers = "fr-rFR")
    fun `formatInsight groupe les milliers du cumul en francais`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals(
            "Mois le plus actif : juillet (1 234 sorties depuis 2021)",
            formatInsight(context, MostActiveMonthInsight(monthOfYear = 7, cumulativeCount = 1234, sinceYear = 2021))
                .espacesNormalisees(),
        )
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `formatInsight groupe les milliers du cumul en anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals(
            "Most active month: July (1,234 hikes since 2021)",
            formatInsight(context, MostActiveMonthInsight(monthOfYear = 7, cumulativeCount = 1234, sinceYear = 2021)),
        )
    }

    /** Le total de la capsule est consommé depuis un @Composable : rendu ici comme l'écran le fait. */
    @Test
    @Config(qualifiers = "fr-rFR")
    fun `le total du Bilan est groupe en francais`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals(
            "1 234 randos au total",
            context.resources
                .getQuantityString(R.plurals.bilan_totals_label, 1234, formatGroupedInt(1234))
                .espacesNormalisees(),
        )
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `le total du Bilan est groupe en anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals(
            "1,234 hikes in total",
            context.resources.getQuantityString(R.plurals.bilan_totals_label, 1234, formatGroupedInt(1234)),
        )
    }
}

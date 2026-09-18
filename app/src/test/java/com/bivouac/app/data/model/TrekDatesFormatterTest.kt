package com.bivouac.app.data.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIC-191 (lot 4 i18n) : TrekDatesFormatter composait sa phrase en dur sous Locale.FRANCE. Les
 * connecteurs sont maintenant dans quatre ressources, une par cas, et le nom des mois suit la
 * locale : d'où RobolectricTestRunner et @Config(qualifiers), comme
 * GpxImportSavedAtFormattingLocaleTest.
 *
 * Les qualifiers choisissent le fichier de ressources, Locale.setDefault pilote le nom du mois
 * rendu par java.time : ce sont deux entrées distinctes et il faut poser les deux.
 *
 * Les cas français sont ceux du test d'origine, à l'identique : ce lot ne change pas le rendu
 * français, il le rend traduisible. Les cas anglais sont nouveaux.
 */
@RunWith(RobolectricTestRunner::class)
class TrekDatesFormatterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var originalLocale: Locale

    @Before
    fun sauvegarderLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restaurerLocale() {
        Locale.setDefault(originalLocale)
    }

    private fun format(days: List<LocalDate>): String? = TrekDatesFormatter.format(context, days)

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `deux jours du meme mois se citent, sans repeter le mois`() {
        Locale.setDefault(Locale.FRANCE)
        val days = listOf(LocalDate.of(2025, 5, 12), LocalDate.of(2025, 5, 13))
        assertEquals("12 et 13 mai 2025", format(days))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `deux jours a cheval sur deux mois gardent les deux mois`() {
        Locale.setDefault(Locale.FRANCE)
        val days = listOf(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 1))
        assertEquals("31 mars et 1er avril 2026", format(days))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `trois jours s'encadrent au lieu de s'enumerer`() {
        Locale.setDefault(Locale.FRANCE)
        val days = listOf(
            LocalDate.of(2026, 3, 3),
            LocalDate.of(2026, 3, 4),
            LocalDate.of(2026, 3, 6),
        )
        assertEquals("du 3 au 6 mars 2026", format(days))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `une plage du meme mois ne le mentionne qu'une fois`() {
        Locale.setDefault(Locale.FRANCE)
        val days = (0..4).map { LocalDate.of(2025, 6, 11).plusDays(it.toLong()) }
        assertEquals("du 11 au 15 juin 2025", format(days))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `une plage a cheval sur deux mois garde les deux mois`() {
        Locale.setDefault(Locale.FRANCE)
        val days = (0..2).map { LocalDate.of(2020, 3, 30).plusDays(it.toLong()) }
        assertEquals("du 30 mars au 1er avril 2020", format(days))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `l'annee de depart est tue meme a cheval sur deux annees`() {
        // Une sortie ne dure pas onze mois : un changement d'année ne peut être que décembre vers
        // janvier, donc « du 31 décembre au 2 janvier 2021 » ne se lit pas autrement.
        Locale.setDefault(Locale.FRANCE)
        val days = (0..2).map { LocalDate.of(2020, 12, 31).plusDays(it.toLong()) }
        assertEquals("du 31 décembre au 2 janvier 2021", format(days))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `deux jours a cheval sur deux annees suivent la meme regle`() {
        Locale.setDefault(Locale.FRANCE)
        val days = listOf(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1))
        assertEquals("31 décembre et 1er janvier 2026", format(days))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `le premier du mois s'ecrit 1er en fin de plage`() {
        Locale.setDefault(Locale.FRANCE)
        val days = (0..3).map { LocalDate.of(2025, 7, 29).plusDays(it.toLong()) }
        assertEquals("du 29 juillet au 1er août 2025", format(days))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `le premier du mois s'ecrit 1er en debut de plage`() {
        Locale.setDefault(Locale.FRANCE)
        val days = (0..3).map { LocalDate.of(2025, 8, 1).plusDays(it.toLong()) }
        assertEquals("du 1er au 4 août 2025", format(days))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `un seul jour ne dit rien de plus que la date de depart deja affichee`() {
        Locale.setDefault(Locale.FRANCE)
        assertNull(format(listOf(LocalDate.of(2025, 5, 12))))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `aucune date connue ne produit rien`() {
        Locale.setDefault(Locale.FRANCE)
        assertNull(format(emptyList()))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `deux jours tombant sur la meme date ne comptent que pour un`() {
        // Deux fichiers d'une même journée : rien à encadrer, ce n'est pas un trek de deux jours.
        Locale.setDefault(Locale.FRANCE)
        val days = listOf(LocalDate.of(2025, 5, 12), LocalDate.of(2025, 5, 12))
        assertNull(format(days))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `les dates sont remises dans l'ordre avant mise en forme`() {
        Locale.setDefault(Locale.FRANCE)
        val days = listOf(LocalDate.of(2025, 5, 13), LocalDate.of(2025, 5, 12))
        assertEquals("12 et 13 mai 2025", format(days))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `le seuil separe bien la citation de l'encadrement`() {
        Locale.setDefault(Locale.FRANCE)
        val cited = (0 until TrekDatesFormatter.MAX_ENUMERATED_DAYS)
            .map { LocalDate.of(2025, 6, 1).plusDays(it.toLong()) }
        assertEquals("1er et 2 juin 2025", format(cited))

        val framed = (0..TrekDatesFormatter.MAX_ENUMERATED_DAYS)
            .map { LocalDate.of(2025, 6, 1).plusDays(it.toLong()) }
        assertEquals("du 1er au 3 juin 2025", format(framed))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `l'anglais place le mois avant le jour et l'annee apres une virgule`() {
        Locale.setDefault(Locale.US)
        val days = listOf(LocalDate.of(2025, 5, 12), LocalDate.of(2025, 5, 13))
        assertEquals("May 12 and 13, 2025", format(days))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `l'anglais encadre une plage sans du ni au`() {
        Locale.setDefault(Locale.US)
        val days = (0..3).map { LocalDate.of(2026, 3, 3).plusDays(it.toLong()) }
        assertEquals("March 3 to 6, 2026", format(days))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `l'anglais garde les deux mois a cheval, sans ordinal`() {
        // Le « 1er » français n'a pas d'équivalent ici : la ressource anglaise vaut « 1 ».
        Locale.setDefault(Locale.US)
        val days = (0..2).map { LocalDate.of(2020, 3, 30).plusDays(it.toLong()) }
        assertEquals("March 30 to April 1, 2020", format(days))
    }
}

package com.bivouac.app.ui.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.R
import com.bivouac.app.i18n.espacesNormalisees
import com.bivouac.app.ui.components.formatGroupedInt
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIC-192 : les comptes du Journal (total, en-tête d'année, en-tête multi-traces, les trois
 * libellés du bouton carte) et le titre de la galerie plate portent le séparateur de milliers de
 * la locale. Tous sont consommés depuis un @Composable : c'est la ressource qui est vérifiée ici,
 * rendue exactement comme l'écran le fait, entier pour la quantité et chaîne formatée pour
 * l'affichage.
 *
 * Le piège que ce test garde fermé est l'en-tête d'année : elle porte DEUX nombres, et seul le
 * second est un compte. « 2 026 · 1 234 randos » serait une régression, pas une correction.
 */
@RunWith(RobolectricTestRunner::class)
class JournalCountsLocaleTest {

    private lateinit var originalLocale: Locale
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
    fun `les comptes du Journal sont groupes en francais`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("1 234 randos au total", compte(R.plurals.journal_list_total_hikes_count, 1234))
        assertEquals("1 234 traces affichées", compte(R.plurals.journal_list_multitrack_header_count, 1234))
        assertEquals(
            "Afficher les 1 234 résultats sur la carte",
            compte(R.plurals.journal_list_show_results_on_map_button, 1234),
        )
        assertEquals("Confirmer la sélection (1 234)", chaine(R.string.journal_list_confirm_selection_button, 1234))
        assertEquals("Afficher la sélection (1 234)", chaine(R.string.journal_list_show_selection_on_map_button, 1234))
        assertEquals("Photos (1 234)", chaine(R.string.photo_gallery_title_count, 1234))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `les comptes du Journal sont groupes en anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals("1,234 hikes in total", compte(R.plurals.journal_list_total_hikes_count, 1234))
        assertEquals("1,234 tracks shown", compte(R.plurals.journal_list_multitrack_header_count, 1234))
        assertEquals("Show 1,234 results on map", compte(R.plurals.journal_list_show_results_on_map_button, 1234))
        assertEquals("Confirm selection (1,234)", chaine(R.string.journal_list_confirm_selection_button, 1234))
        assertEquals("Show selection (1,234)", chaine(R.string.journal_list_show_selection_on_map_button, 1234))
        assertEquals("Photos (1,234)", chaine(R.string.photo_gallery_title_count, 1234))
    }

    /** L'année n'est JAMAIS groupée : « 2026 · 1 234 randos », pas « 2 026 ». */
    @Test
    @Config(qualifiers = "fr-rFR")
    fun `l en-tete d annee groupe le compte mais pas l annee en francais`() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals(
            "2026 · 1 234 randos",
            context.resources
                .getQuantityString(R.plurals.journal_list_year_header_count, 1234, 2026, formatGroupedInt(1234))
                .espacesNormalisees(),
        )
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `l en-tete d annee groupe le compte mais pas l annee en anglais`() {
        Locale.setDefault(Locale.US)
        assertEquals(
            "2026 · 1,234 hikes",
            context.resources
                .getQuantityString(R.plurals.journal_list_year_header_count, 1234, 2026, formatGroupedInt(1234)),
        )
    }

    private fun compte(plurals: Int, valeur: Int): String =
        context.resources.getQuantityString(plurals, valeur, formatGroupedInt(valeur)).espacesNormalisees()

    private fun chaine(id: Int, valeur: Int): String =
        context.getString(id, formatGroupedInt(valeur)).espacesNormalisees()
}

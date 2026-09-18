package com.bivouac.app.ui.nav

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bivouac.app.R
import com.bivouac.app.ui.theme.BivouacTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RIC-108 : ce que le dialogue de choix d'univers (RIC-104) fait d'un lot de plusieurs fichiers.
 *
 * Le défaut reproduit : avec trois fichiers reçus, choisir « Planification » n'en ouvrait qu'un et
 * jetait les deux autres sans un mot (MainActivity, `uris.first()`). La branche est désormais
 * inerte au-delà d'un fichier, et ces tests tiennent les deux moitiés du contrat : un fichier, rien
 * ne change ; plusieurs, le choix ne part pas.
 *
 * RIC-190 (lot 3 i18n) : les textes cherchés viennent des ressources et non plus de littéraux
 * français, le GMD tournant en en-US (l'app y est donc en anglais).
 */
@RunWith(AndroidJUnit4::class)
class UniverseChoiceDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var journalChosen = 0
    private var planificationChosen = 0

    private fun text(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun setContent(fileCount: Int) {
        composeRule.setContent {
            BivouacTheme {
                UniverseChoiceDialog(
                    fileCount = fileCount,
                    onJournalChosen = { journalChosen++ },
                    onPlanificationChosen = { planificationChosen++ },
                    onCancel = {},
                )
            }
        }
    }

    @Test
    fun singleFile_keepsBothBranchesLive() {
        setContent(fileCount = 1)

        composeRule.onNodeWithText(text(R.string.nav_universe_choice_option_planification_subtitle))
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.nav_section_planification)).performClick()

        assertEquals("un seul fichier : le choix Planification doit partir normalement", 1, planificationChosen)
    }

    @Test
    fun severalFiles_planificationIsInertAndSaysWhy() {
        setContent(fileCount = 3)

        composeRule.onNodeWithText(text(R.string.nav_universe_choice_planification_disabled_subtitle))
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.nav_section_planification)).performClick()

        assertEquals(
            "un lot ne doit pas pouvoir partir en Planification, qui n'en ouvrirait qu'un",
            0,
            planificationChosen,
        )
    }

    /** L'option qui reste doit rester praticable : sans elle, le lot n'aurait plus aucun chemin. */
    @Test
    fun severalFiles_journalStaysAvailable() {
        setContent(fileCount = 3)

        composeRule.onNodeWithText(text(R.string.journal_list_screen_title)).performClick()

        assertEquals(1, journalChosen)
    }
}

package com.bivouac.app.ui.journal

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bivouac.app.R
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.ui.theme.BivouacTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RIC-189 (lot 2 i18n) : les descriptions de contenu visées viennent des ressources. Le GMD tourne
 * en en-US, donc l'app y est en anglais et un test écrit sur « Modifier » ne trouverait plus rien.
 *
 * RIC-191 (lot 4 i18n) : le dernier littéral, « Solo », est lui aussi passé en ressource. Le
 * libellé de SystemTag est désormais traduit à l'affichage, sa valeur stockée en base restant
 * « solo » : c'est bien le libellé traduit que le test doit chercher à l'écran.
 */
@RunWith(AndroidJUnit4::class)
class ThreeStopJournalDetailDirtyIndicatorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun string(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val entry = LoggedTrackEntity(
        id = "track-test",
        name = "Trace test",
        startedAt = 1_700_000_000_000L,
        contentHash = "hash-test",
        distanceMeters = 5000.0,
        elevationGainMeters = 300.0,
        elevationLossMeters = 200.0,
        pointCount = 0,
        estimatedDurationMinutes = 90,
        note = "",
    )

    private fun setContent() {
        composeRule.setContent {
            BivouacTheme {
                ThreeStopJournalDetail(
                    entry = entry,
                    track = HikeTrack(name = entry.name, points = emptyList()),
                    onCloseClick = {},
                    onRenameClick = {},
                    onDeleteClick = {},
                    onSheetTopMeasured = {},
                    cursorIndex = null,
                    onCursorDragged = {},
                    currentTags = emptyList(),
                    tagsByTrackId = emptyMap(),
                    // RIC-149 : le troisième paramètre est le rappel de fin d'enregistrement, que
                    // seule la sortie d'écran utilise. Appelé tout de suite ici, comme le ferait un
                    // enregistrement instantané : ce test ne porte que sur l'icône de sauvegarde.
                    onSaveDetails = { _, _, onFinished -> onFinished() },
                )
            }
        }
    }

    @Test
    fun saveIcon_neutralInitially_orangeWhenDirty_backToEditIconAfterSave() {
        setContent()

        // Not editing yet: neutral "Modifier" (edit) icon, no save icon at all.
        composeRule.onNodeWithContentDescription(string(R.string.journal_detail_edit_description)).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(string(R.string.journal_detail_edit_description)).performClick()

        // Editing started, nothing changed yet: neutral save icon, plain label.
        composeRule.onNodeWithContentDescription(string(R.string.common_save_button)).assertIsDisplayed()

        // Toggling a system tag makes the draft diverge from the saved state.
        composeRule.onNodeWithText(string(R.string.tag_system_solo)).performClick()

        // Dirty: orange-tinted save icon with the accessibility label reflecting unsaved changes.
        composeRule.onNodeWithContentDescription(string(R.string.journal_detail_save_dirty_description))
            .assertIsDisplayed()
            .performClick()

        // Saving stops editing immediately, returning to the neutral edit icon.
        composeRule.onNodeWithContentDescription(string(R.string.journal_detail_edit_description)).assertIsDisplayed()
    }
}

package com.bivouac.app.ui.journal

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.ui.theme.BivouacTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreeStopJournalDetailDirtyIndicatorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val entry = LoggedTrackEntity(
        id = "track-test",
        name = "Trace test",
        sourceFileName = null,
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
                    onSaveDetails = { _, _ -> },
                )
            }
        }
    }

    @Test
    fun saveIcon_neutralInitially_orangeWhenDirty_backToEditIconAfterSave() {
        setContent()

        // Not editing yet: neutral "Modifier" (edit) icon, no save icon at all.
        composeRule.onNodeWithContentDescription("Modifier").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Modifier").performClick()

        // Editing started, nothing changed yet: neutral save icon, plain label.
        composeRule.onNodeWithContentDescription("Enregistrer").assertIsDisplayed()

        // Toggling a system tag makes the draft diverge from the saved state.
        composeRule.onNodeWithText("Solo").performClick()

        // Dirty: orange-tinted save icon with the accessibility label reflecting unsaved changes.
        composeRule.onNodeWithContentDescription("Enregistrer (modifications non sauvegardées)")
            .assertIsDisplayed()
            .performClick()

        // Saving stops editing immediately, returning to the neutral edit icon.
        composeRule.onNodeWithContentDescription("Modifier").assertIsDisplayed()
    }
}

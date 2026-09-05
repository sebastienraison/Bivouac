package com.bivouac.app.ui.journal

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.ui.theme.BivouacTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RIC-149 : on ne quitte pas la trace ouverte pendant qu'une opération photo est en vol.
 *
 * Signalé en recette sur l'import : pendant la copie des photos choisies vers le transit (plusieurs
 * secondes sur un lot réel), la croix de l'en-tête fermait tout sans un mot, emportant un travail
 * en cours sur des fichiers.
 *
 * Le dialogue bloquant de JournalScreen couvre ce cas par construction : c'est une fenêtre par-
 * dessus l'écran entier, la croix n'est plus atteignable, mais cette garantie-là repose sur la
 * seule présence d'une fenêtre. Ce test porte sur le second verrou, celui qui tient même si un
 * chemin échappait au dialogue : la sortie elle-même refuse de partir.
 */
@RunWith(AndroidJUnit4::class)
class ThreeStopJournalDetailExitGuardTest {

    @get:Rule
    val composeRule = createComposeRule()

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

    private var closeCount = 0

    private fun setContent(photoOperationInFlight: Boolean) {
        composeRule.setContent {
            BivouacTheme {
                ThreeStopJournalDetail(
                    entry = entry,
                    track = HikeTrack(name = entry.name, points = emptyList()),
                    onCloseClick = { closeCount++ },
                    onRenameClick = {},
                    onDeleteClick = {},
                    onSheetTopMeasured = {},
                    cursorIndex = null,
                    onCursorDragged = {},
                    currentTags = emptyList(),
                    tagsByTrackId = emptyMap(),
                    onSaveDetails = { _, _, onFinished -> onFinished() },
                    photoOperationInFlight = photoOperationInFlight,
                )
            }
        }
    }

    @Test
    fun closingIsRefusedWhileAPhotoOperationIsInFlight() {
        setContent(photoOperationInFlight = true)

        composeRule.onNodeWithContentDescription("Fermer").performClick()

        assertEquals("la croix ne doit pas fermer pendant un import ou un enregistrement", 0, closeCount)
    }

    /**
     * Le témoin : sans opération en vol, la même croix ferme bel et bien. Sans lui, le test
     * ci-dessus passerait tout aussi bien sur une croix cassée.
     */
    @Test
    fun closingWorksAsUsualWhenNoPhotoOperationIsRunning() {
        setContent(photoOperationInFlight = false)

        composeRule.onNodeWithContentDescription("Fermer").performClick()

        assertEquals(1, closeCount)
    }
}

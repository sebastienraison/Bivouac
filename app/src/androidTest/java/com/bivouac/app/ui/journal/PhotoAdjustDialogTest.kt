package com.bivouac.app.ui.journal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.bivouac.app.R
import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import com.bivouac.app.data.photo.PhotoAdjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * RIC-143 / RIC-144 : l'éditeur « Ajuster » se monte, et ses trois sorties font ce qu'elles
 * annoncent.
 *
 * Ce que ce test ne fait PAS : vérifier le dessin ni les gestes. La géométrie est ailleurs, en
 * fonctions pures (PhotoCropGeometryTest), et c'est exprès : c'est là qu'elle est vérifiable
 * sérieusement. Ici on tient le reste, c'est-à-dire ce qu'aucun test pur ne peut attraper : le
 * dialogue compose sans planter, Annuler ne rend rien, OK rend l'état courant, et les boutons de
 * rotation modifient bien cet état.
 *
 * La photo pointe volontairement vers un fichier absent : le chargement d'image échoue, l'éditeur
 * n'affiche donc ni cadre ni poignées (il attend de connaître les proportions réelles), et la
 * barre reste utilisable. C'est aussi un cas réel, celui d'une copie locale disparue.
 *
 * RIC-189 (lot 2 i18n) : les textes visés passent par les ressources et non plus par des littéraux
 * français. Le GMD tourne en en-US, donc l'app y est en anglais : un test écrit en dur sur
 * « Ajuster » ne trouverait plus rien. `targetContext` et non le contexte du test : c'est celui de
 * l'application sous test qui porte ses ressources.
 */
class PhotoAdjustDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val photo = LoggedTrackPhotoEntity(
        id = 1L,
        trackId = "track-1",
        filePath = "photos/track-1-absente.jpg",
        addedAtMillis = 1_780_300_900_000L,
        contentHash = "abc123",
    )

    @Test
    fun laBarreEtLesBoutonsDeRotationSontLa() {
        composeTestRule.setContent {
            PhotoAdjustDialog(photo = photo, onCancel = {}, onConfirm = {})
        }

        composeTestRule.onNodeWithText(string(R.string.photo_adjust_dialog_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.common_cancel_button)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.common_ok_button)).assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.photo_adjust_rotate_left_button))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.photo_adjust_rotate_right_button))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.photo_adjust_gesture_hint))
            .assertIsDisplayed()
    }

    @Test
    fun annulerNeRendAucunAjustement() {
        var cancelled = false
        var confirmed: PhotoAdjustments? = null
        composeTestRule.setContent {
            PhotoAdjustDialog(photo = photo, onCancel = { cancelled = true }, onConfirm = { confirmed = it })
        }

        composeTestRule.onNodeWithText(string(R.string.common_cancel_button)).performClick()

        assertTrue(cancelled)
        assertNull("Annuler ne doit rien valider", confirmed)
    }

    @Test
    fun okRendLesAjustementsDeDepartQuandOnNAToucheARien() {
        var confirmed: PhotoAdjustments? = null
        composeTestRule.setContent {
            PhotoAdjustDialog(photo = photo, onCancel = {}, onConfirm = { confirmed = it })
        }

        composeTestRule.onNodeWithText(string(R.string.common_ok_button)).performClick()

        // Exactement ce que la photo portait : c'est ce qui permet au ViewModel de reconnaître
        // « rien n'a changé » et de ne pas marquer l'écran comme modifié.
        assertEquals(PhotoAdjustments.NONE, confirmed)
    }

    @Test
    fun lesBoutonsDeRotationSAccumulentEtSeCompensent() {
        var confirmed: PhotoAdjustments? = null
        composeTestRule.setContent {
            PhotoAdjustDialog(photo = photo, onCancel = {}, onConfirm = { confirmed = it })
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.photo_adjust_rotate_right_button)).performClick()
        composeTestRule.onNodeWithContentDescription(string(R.string.photo_adjust_rotate_right_button)).performClick()
        composeTestRule.onNodeWithContentDescription(string(R.string.photo_adjust_rotate_left_button)).performClick()
        composeTestRule.onNodeWithText(string(R.string.common_ok_button)).performClick()

        assertEquals(PhotoAdjustments(rotationQuarterTurns = 1), confirmed)
    }

    @Test
    fun uneRotationDejaEnregistreeEstLePointDeDepart() {
        var confirmed: PhotoAdjustments? = null
        val alreadyTurned = photo.copy(rotationQuarterTurns = 3)
        composeTestRule.setContent {
            PhotoAdjustDialog(photo = alreadyTurned, onCancel = {}, onConfirm = { confirmed = it })
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.photo_adjust_rotate_right_button)).performClick()
        composeTestRule.onNodeWithText(string(R.string.common_ok_button)).performClick()

        // 3 + 1 = 4 quarts de tour, ramenés à 0 : l'éditeur repart bien de l'état existant, il ne
        // recommence pas à zéro.
        assertEquals(PhotoAdjustments(rotationQuarterTurns = 0), confirmed)
    }

    @Test
    fun reinitialiserEstGriseTantQuAucunAjustementNAEteFait() {
        composeTestRule.setContent {
            PhotoAdjustDialog(photo = photo, onCancel = {}, onConfirm = {})
        }

        composeTestRule.onNodeWithText(string(R.string.photo_adjust_reset_button)).assertIsNotEnabled()
    }

    @Test
    fun reinitialiserSActiveApresUneRotationEtRameneANone() {
        var confirmed: PhotoAdjustments? = null
        composeTestRule.setContent {
            PhotoAdjustDialog(photo = photo, onCancel = {}, onConfirm = { confirmed = it })
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.photo_adjust_rotate_right_button)).performClick()
        composeTestRule.onNodeWithText(string(R.string.photo_adjust_reset_button)).assertIsEnabled()

        composeTestRule.onNodeWithText(string(R.string.photo_adjust_reset_button)).performClick()
        composeTestRule.onNodeWithText(string(R.string.photo_adjust_reset_button)).assertIsNotEnabled()

        // Le retour à l'identité ne se voit pas qu'au bouton : OK doit désormais rendre NONE, comme
        // si la rotation n'avait jamais eu lieu.
        composeTestRule.onNodeWithText(string(R.string.common_ok_button)).performClick()
        assertEquals(PhotoAdjustments.NONE, confirmed)
    }
}

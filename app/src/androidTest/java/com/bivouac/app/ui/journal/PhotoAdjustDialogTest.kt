package com.bivouac.app.ui.journal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 */
class PhotoAdjustDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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

        composeTestRule.onNodeWithText("Ajuster").assertIsDisplayed()
        composeTestRule.onNodeWithText("Annuler").assertIsDisplayed()
        composeTestRule.onNodeWithText("OK").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Tourner à gauche").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Tourner à droite").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Coins : proportions conservées · Côtés : recadrage libre · Intérieur : déplacer")
            .assertIsDisplayed()
    }

    @Test
    fun annulerNeRendAucunAjustement() {
        var cancelled = false
        var confirmed: PhotoAdjustments? = null
        composeTestRule.setContent {
            PhotoAdjustDialog(photo = photo, onCancel = { cancelled = true }, onConfirm = { confirmed = it })
        }

        composeTestRule.onNodeWithText("Annuler").performClick()

        assertTrue(cancelled)
        assertNull("Annuler ne doit rien valider", confirmed)
    }

    @Test
    fun okRendLesAjustementsDeDepartQuandOnNAToucheARien() {
        var confirmed: PhotoAdjustments? = null
        composeTestRule.setContent {
            PhotoAdjustDialog(photo = photo, onCancel = {}, onConfirm = { confirmed = it })
        }

        composeTestRule.onNodeWithText("OK").performClick()

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

        composeTestRule.onNodeWithContentDescription("Tourner à droite").performClick()
        composeTestRule.onNodeWithContentDescription("Tourner à droite").performClick()
        composeTestRule.onNodeWithContentDescription("Tourner à gauche").performClick()
        composeTestRule.onNodeWithText("OK").performClick()

        assertEquals(PhotoAdjustments(rotationQuarterTurns = 1), confirmed)
    }

    @Test
    fun uneRotationDejaEnregistreeEstLePointDeDepart() {
        var confirmed: PhotoAdjustments? = null
        val alreadyTurned = photo.copy(rotationQuarterTurns = 3)
        composeTestRule.setContent {
            PhotoAdjustDialog(photo = alreadyTurned, onCancel = {}, onConfirm = { confirmed = it })
        }

        composeTestRule.onNodeWithContentDescription("Tourner à droite").performClick()
        composeTestRule.onNodeWithText("OK").performClick()

        // 3 + 1 = 4 quarts de tour, ramenés à 0 : l'éditeur repart bien de l'état existant, il ne
        // recommence pas à zéro.
        assertEquals(PhotoAdjustments(rotationQuarterTurns = 0), confirmed)
    }

    @Test
    fun reinitialiserEstGriseTantQuAucunAjustementNAEteFait() {
        composeTestRule.setContent {
            PhotoAdjustDialog(photo = photo, onCancel = {}, onConfirm = {})
        }

        composeTestRule.onNodeWithText("Réinitialiser").assertIsNotEnabled()
    }

    @Test
    fun reinitialiserSActiveApresUneRotationEtRameneANone() {
        var confirmed: PhotoAdjustments? = null
        composeTestRule.setContent {
            PhotoAdjustDialog(photo = photo, onCancel = {}, onConfirm = { confirmed = it })
        }

        composeTestRule.onNodeWithContentDescription("Tourner à droite").performClick()
        composeTestRule.onNodeWithText("Réinitialiser").assertIsEnabled()

        composeTestRule.onNodeWithText("Réinitialiser").performClick()
        composeTestRule.onNodeWithText("Réinitialiser").assertIsNotEnabled()

        // Le retour à l'identité ne se voit pas qu'au bouton : OK doit désormais rendre NONE, comme
        // si la rotation n'avait jamais eu lieu.
        composeTestRule.onNodeWithText("OK").performClick()
        assertEquals(PhotoAdjustments.NONE, confirmed)
    }
}

package com.bivouac.app.ui.journal

import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bivouac.app.data.photo.PhotoPickerScope
import com.bivouac.app.ui.theme.BivouacTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RIC-162 : bout en bout de la visionneuse de sélection ouverte depuis la grille du sélecteur de
 * photos : l'icône « étendre » ouvre bien la bonne candidate, et surtout, cocher une photo DEPUIS
 * la visionneuse puis refermer ramène à une grille dont la sélection est déjà à jour. C'est le
 * comportement que la logique pure (PhotoSelectionViewerLogicTest) ne peut pas garantir à elle
 * seule : il dépend de la façon dont l'état est hoisté entre les deux composables.
 */
@RunWith(AndroidJUnit4::class)
class PhotoPickerSelectionViewerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val candidates = listOf(
        Uri.parse("content://media/external/images/media/1"),
        Uri.parse("content://media/external/images/media/2"),
        Uri.parse("content://media/external/images/media/3"),
    )

    private fun setContent() {
        composeRule.setContent {
            BivouacTheme {
                PhotoPickerDialog(
                    candidates = candidates,
                    loading = false,
                    scope = PhotoPickerScope.TRACK_DATES,
                    partialAccess = false,
                    onScopeChange = {},
                    onSelectMorePhotos = {},
                    onOpenAppSettings = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun expandIconOpensTheTappedPhoto_toggleFromViewer_updatesTheGridOnClose() {
        setContent()

        composeRule.onNodeWithText("Aucune sélection").assertIsDisplayed()

        // Ouvre la visionneuse depuis la DEUXIÈME vignette : le compteur doit refléter cette
        // candidate précise, pas juste "une visionneuse s'est ouverte".
        composeRule.onAllNodesWithContentDescription("Agrandir la photo")[1].performClick()
        composeRule.onNodeWithText("2 / 3").assertIsDisplayed()

        // Coche la photo courante depuis le bandeau de la visionneuse, puis referme par sa croix.
        composeRule.onNodeWithText("Sélectionner cette photo").performClick()
        composeRule.onNodeWithContentDescription("Fermer la visionneuse").performClick()

        // La grille sous-jacente reflète la sélection faite depuis la visionneuse, sans code de
        // synchronisation dédié : c'était le même état pendant tout le temps où elle était ouverte.
        composeRule.onNodeWithText("1 sélectionnée(s)").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Sélectionnée").assertCountEquals(1)
    }
}

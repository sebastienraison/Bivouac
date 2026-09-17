package com.bivouac.app.ui.journal

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bivouac.app.data.photo.PhotoPickerScope
import com.bivouac.app.ui.theme.BivouacTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RIC-162 / RIC-182 : bout en bout de la visionneuse de sélection ouverte depuis la grille du
 * sélecteur de photos : l'icône « étendre » ouvre bien la bonne candidate, un tap sur la photo
 * bascule sa sélection (et la coche le dit), et refermer ramène à une grille dont la sélection est
 * déjà à jour. C'est le comportement que la logique pure (PhotoSelectionViewerLogicTest) ne peut
 * pas garantir à elle seule : il dépend de la façon dont l'état est hoisté entre les deux
 * composables, et de l'arbitrage de gestes que RIC-182 introduit (le tap ne doit jamais se
 * déclencher pour un pincement ou un balayage entre photos).
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
    fun expandIconOpensTheTappedPhoto_tapTogglesSelection_updatesTheGridOnClose() {
        setContent()

        composeRule.onAllNodesWithContentDescription("Sélectionnée").assertCountEquals(0)

        // Ouvre la visionneuse depuis la DEUXIÈME vignette : le compteur doit refléter cette
        // candidate précise, pas juste "une visionneuse s'est ouverte".
        composeRule.onAllNodesWithContentDescription("Agrandir la photo")[1].performClick()
        composeRule.onNodeWithText("2 / 3").assertIsDisplayed()

        // RIC-182 : le tap est sur la photo elle-même, plus de bandeau ni de case à cocher au bas
        // de l'écran.
        composeRule.onNodeWithTag("selection-viewer-photo").performTouchInput { click() }

        // Deux coches existent maintenant dans l'arbre de sémantique pour le même état partagé :
        // celle de la visionneuse, superposée à la photo, et celle de la grille sous-jacente, qui
        // reste composée (donc interrogeable) tant que le dialogue au-dessus est ouvert, même si
        // elle est visuellement couverte.
        composeRule.onAllNodesWithContentDescription("Sélectionnée").assertCountEquals(2)

        // Un second tap désélectionne : la coche disparaît des deux côtés à la fois, sans code de
        // synchronisation dédié entre la grille et la visionneuse.
        composeRule.onNodeWithTag("selection-viewer-photo").performTouchInput { click() }
        composeRule.onAllNodesWithContentDescription("Sélectionnée").assertCountEquals(0)

        // Resélectionne, puis referme par la croix.
        composeRule.onNodeWithTag("selection-viewer-photo").performTouchInput { click() }
        composeRule.onNodeWithContentDescription("Fermer la visionneuse").performClick()

        // La grille sous-jacente reflète la sélection faite depuis la visionneuse, sans code de
        // synchronisation dédié : c'était le même état pendant tout le temps où elle était ouverte.
        composeRule.onAllNodesWithContentDescription("Sélectionnée").assertCountEquals(1)
    }

    @Test
    fun swipingBetweenPhotosNeverTogglesSelection() {
        setContent()

        composeRule.onAllNodesWithContentDescription("Agrandir la photo")[0].performClick()
        composeRule.onNodeWithText("1 / 3").assertIsDisplayed()

        composeRule.onNodeWithTag("selection-viewer-photo").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 / 3").assertIsDisplayed()

        // Le balayage a changé de page, il n'a basculé la sélection d'aucune des deux photos.
        composeRule.onAllNodesWithContentDescription("Sélectionnée").assertCountEquals(0)
    }

    @Test
    fun pinchingNeverTogglesSelection() {
        setContent()

        composeRule.onAllNodesWithContentDescription("Agrandir la photo")[0].performClick()
        composeRule.onNodeWithText("1 / 3").assertIsDisplayed()

        // RIC-182 : le détecteur de tap est un pointerInput distinct de celui du pincement, mais
        // partage le même flux de contacts. `detectTapGestures` doit ignorer de lui-même un
        // deuxième doigt : c'est cette cohabitation qui est vérifiée ici, pas le zoom en lui-même
        // (déjà hors périmètre de ce lot, et non observable depuis ce test).
        composeRule.onNodeWithTag("selection-viewer-photo").performTouchInput {
            pinch(
                start0 = center - Offset(60f, 0f),
                end0 = center - Offset(160f, 0f),
                start1 = center + Offset(60f, 0f),
                end1 = center + Offset(160f, 0f),
            )
        }

        composeRule.onAllNodesWithContentDescription("Sélectionnée").assertCountEquals(0)
    }
}

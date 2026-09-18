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
import androidx.test.platform.app.InstrumentationRegistry
import com.bivouac.app.R
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
 *
 * RIC-189 (lot 2 i18n) : les descriptions de contenu visées viennent des ressources, plus de
 * littéraux français. Le GMD tourne en en-US, donc l'app y est en anglais. Le compteur "n / N"
 * passe par [selectionCounterLabel], la fonction que l'écran lui-même appelle : le test vérifie
 * ainsi le libellé réellement affiché, pas une reconstitution.
 */
@RunWith(AndroidJUnit4::class)
class PhotoPickerSelectionViewerTest {

    @get:Rule
    val composeRule = createComposeRule()

    // targetContext et non le contexte du test : c'est l'application sous test qui porte les
    // ressources, et c'est sa locale qui décide de la langue affichée.
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(id: Int): String = appContext.getString(id)

    private fun counter(page: Int): String = selectionCounterLabel(appContext, page, candidates.size)

    private fun selectedChecks() =
        composeRule.onAllNodesWithContentDescription(string(R.string.photo_gallery_selected_description))

    private fun expandIcons() =
        composeRule.onAllNodesWithContentDescription(string(R.string.photo_gallery_expand_description))

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

        selectedChecks().assertCountEquals(0)

        // Ouvre la visionneuse depuis la DEUXIÈME vignette : le compteur doit refléter cette
        // candidate précise, pas juste "une visionneuse s'est ouverte".
        expandIcons()[1].performClick()
        composeRule.onNodeWithText(counter(page = 1)).assertIsDisplayed()

        // RIC-182 : le tap est sur la photo elle-même, plus de bandeau ni de case à cocher au bas
        // de l'écran.
        composeRule.onNodeWithTag("selection-viewer-photo").performTouchInput { click() }

        // Deux coches existent maintenant dans l'arbre de sémantique pour le même état partagé :
        // celle de la visionneuse, superposée à la photo, et celle de la grille sous-jacente, qui
        // reste composée (donc interrogeable) tant que le dialogue au-dessus est ouvert, même si
        // elle est visuellement couverte.
        selectedChecks().assertCountEquals(2)

        // Un second tap désélectionne : la coche disparaît des deux côtés à la fois, sans code de
        // synchronisation dédié entre la grille et la visionneuse.
        composeRule.onNodeWithTag("selection-viewer-photo").performTouchInput { click() }
        selectedChecks().assertCountEquals(0)

        // Resélectionne, puis referme par la croix.
        composeRule.onNodeWithTag("selection-viewer-photo").performTouchInput { click() }
        composeRule
            .onNodeWithContentDescription(string(R.string.photo_gallery_close_selection_viewer_description))
            .performClick()

        // La grille sous-jacente reflète la sélection faite depuis la visionneuse, sans code de
        // synchronisation dédié : c'était le même état pendant tout le temps où elle était ouverte.
        selectedChecks().assertCountEquals(1)
    }

    @Test
    fun swipingBetweenPhotosNeverTogglesSelection() {
        setContent()

        expandIcons()[0].performClick()
        composeRule.onNodeWithText(counter(page = 0)).assertIsDisplayed()

        composeRule.onNodeWithTag("selection-viewer-photo").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(counter(page = 1)).assertIsDisplayed()

        // Le balayage a changé de page, il n'a basculé la sélection d'aucune des deux photos.
        selectedChecks().assertCountEquals(0)
    }

    @Test
    fun pinchingNeverTogglesSelection() {
        setContent()

        expandIcons()[0].performClick()
        composeRule.onNodeWithText(counter(page = 0)).assertIsDisplayed()

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

        selectedChecks().assertCountEquals(0)
    }
}

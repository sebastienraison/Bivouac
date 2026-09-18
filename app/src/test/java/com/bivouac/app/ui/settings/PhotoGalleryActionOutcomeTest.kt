package com.bivouac.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bivouac.app.data.db.LoggedTrackRepository
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIC-157/151 : ce qu'un appui produit sur les deux actions des Réglages qui vont regarder dans la
 * galerie, et ce que leurs rapports de fin racontent.
 *
 * Deux règles se vérifient ici sans monter d'écran : aucune issue muette (le bouton fait toujours
 * quelque chose de visible), et aucune demande de permission galerie quand les photos sont
 * débrayées dans les Réglages (RIC-152).
 *
 * RIC-187 (lot 0 i18n) : les rapports attendent "34,5 Mo" (virgule française), d'où la locale
 * FRANCE posée et restaurée explicitement, pour que ce test passe sur une machine réglée dans
 * n'importe quelle locale.
 *
 * RIC-190 (lot 3 i18n) : les deux rapports lisent leurs phrases dans les ressources et prennent un
 * Context, d'où Robolectric et @Config(qualifiers = "fr-rFR") sur les tests qui assertent du texte.
 * Ce qu'ils vérifient reste le même : les issues sont dites séparément, et une passe qui n'a rien
 * pu faire le dit quand même. Les quatre premiers tests, eux, ne touchent aucune ressource.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-rFR")
class PhotoGalleryActionOutcomeTest {

    private lateinit var originalLocale: Locale
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun sauvegarderLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.FRANCE)
    }

    @After
    fun restaurerLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun withThePermissionGrantedItRunsStraightAway() {
        assertEquals(
            PhotoGalleryActionOutcome.RUN,
            photoGalleryActionOutcome(photosEnabled = true, permissionGranted = true, permanentlyDenied = false),
        )
    }

    @Test
    fun withoutThePermissionItAsksForIt() {
        assertEquals(
            PhotoGalleryActionOutcome.REQUEST_PERMISSION,
            photoGalleryActionOutcome(photosEnabled = true, permissionGranted = false, permanentlyDenied = false),
        )
    }

    /**
     * Refus devenu définitif : redemander rendrait la main sans afficher un pixel, donc on explique
     * nous-mêmes. Même correctif que celui du bandeau Photos du Journal (RIC-43), qui avait produit
     * un bouton perçu comme mort.
     */
    @Test
    fun afterAPermanentDenialItExplainsInsteadOfAskingAgain() {
        assertEquals(
            PhotoGalleryActionOutcome.EXPLAIN_BLOCKED,
            photoGalleryActionOutcome(photosEnabled = true, permissionGranted = false, permanentlyDenied = true),
        )
    }

    /**
     * Photos débrayées : rien, et surtout aucune demande de permission. Le réglage promet que
     * l'accès à la galerie ne sera jamais demandé, et cette promesse doit être tenue par le code
     * qui déclenche la demande, pas seulement par celui qui affiche le bouton.
     */
    @Test
    fun withPhotosDisabledNothingHappensAndNoPermissionIsEverRequested() {
        assertEquals(
            PhotoGalleryActionOutcome.IGNORED,
            photoGalleryActionOutcome(photosEnabled = false, permissionGranted = false, permanentlyDenied = false),
        )
        assertEquals(
            PhotoGalleryActionOutcome.IGNORED,
            photoGalleryActionOutcome(photosEnabled = false, permissionGranted = true, permanentlyDenied = false),
        )
    }

    @Test
    fun theReportSpellsOutTheThreeOutcomesSeparately() {
        val message = recompressionReportMessage(
            context,
            LoggedTrackRepository.PhotoRecompressionReport(
                recompressed = 12,
                freedBytes = 34_500_000L,
                kept = 3,
                alreadyReduced = 2,
            ),
        )

        assertTrue(message, message.contains("12 photos recompressées"))
        assertTrue(message, message.contains("34,5 Mo"))
        assertTrue(message, message.contains("3 photos conservées"))
        assertTrue(message, message.contains("2 photos étaient déjà"))
    }

    // Une passe qui n'a rien pu faire le dit quand même : pas de fin silencieuse, et surtout pas
    // un rapport vide qui laisserait croire à un bug.
    @Test
    fun aPassThatCouldDoNothingStillSaysSo() {
        val message = recompressionReportMessage(
            context,
            LoggedTrackRepository.PhotoRecompressionReport(recompressed = 0, freedBytes = 0L, kept = 4, alreadyReduced = 0),
        )

        assertTrue(message, message.contains("Aucune photo n'a pu être recompressée"))
        assertTrue(message, message.contains("4 photos conservées"))
    }

    /**
     * RIC-151 : les trois issues de la recherche, et surtout la deuxième, dite en toutes lettres :
     * la photo est bien là, mais ce n'est plus le même fichier, et rien n'a été repris à sa place.
     */
    @Test
    fun theRecoveryReportSaysWhatWasFoundAndWhatWasDeliberatelyNotAdopted() {
        val message = photoRecoveryReportMessage(
            context,
            LoggedTrackRepository.PhotoRecoveryReport(recovered = 5, modifiedNotAdopted = 2, notFound = 1),
        )

        assertTrue(message, message.contains("5 photos retrouvées"))
        // RIC-190 : au pluriel, c'est TOUTE la phrase qui s'accorde, pas seulement son début. Le
        // fragment recollé d'avant donnait « 2 photos retrouvées […] mais modifiée depuis
        // l'import : ce n'est plus le même fichier ».
        assertTrue(message, message.contains("modifiées depuis l'import"))
        assertTrue(message, message.contains("ce ne sont plus les mêmes fichiers"))
        assertTrue(message, message.contains("rien n'a été repris"))
        assertTrue(message, message.contains("1 photo reste introuvable"))
        // Le pluriel porte la phrase entière : au singulier, « Sa fiche est conservée ».
        assertTrue(message, message.contains("Sa fiche est conservée dans le Journal"))
    }

    @Test
    fun aRecoveryThatFoundNothingStillSaysSo() {
        val message = photoRecoveryReportMessage(
            context,
            LoggedTrackRepository.PhotoRecoveryReport(recovered = 0, modifiedNotAdopted = 0, notFound = 3),
        )

        assertTrue(message, message.contains("Aucune photo n'a pu être retrouvée"))
        // La fiche survit à l'échec : c'est ce qui permet de retenter le jour où l'original revient.
        // 3 introuvables : la forme « other » accorde toute la phrase, pas seulement son début.
        assertTrue(message, message.contains("Leurs fiches sont conservées dans le Journal"))
    }
}

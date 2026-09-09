package com.bivouac.app.ui.settings

import com.bivouac.app.data.db.LoggedTrackRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-157/151 : ce qu'un appui produit sur les deux actions des Réglages qui vont regarder dans la
 * galerie, et ce que leurs rapports de fin racontent.
 *
 * Deux règles se vérifient ici sans monter d'écran : aucune issue muette (le bouton fait toujours
 * quelque chose de visible), et aucune demande de permission galerie quand les photos sont
 * débrayées dans les Réglages (RIC-152).
 */
class PhotoGalleryActionOutcomeTest {

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
            LoggedTrackRepository.PhotoRecoveryReport(recovered = 5, modifiedNotAdopted = 2, notFound = 1),
        )

        assertTrue(message, message.contains("5 photos retrouvées"))
        assertTrue(message, message.contains("modifiée depuis l'import"))
        assertTrue(message, message.contains("rien n'a été repris"))
        assertTrue(message, message.contains("1 photo reste introuvable"))
    }

    @Test
    fun aRecoveryThatFoundNothingStillSaysSo() {
        val message = photoRecoveryReportMessage(
            LoggedTrackRepository.PhotoRecoveryReport(recovered = 0, modifiedNotAdopted = 0, notFound = 3),
        )

        assertTrue(message, message.contains("Aucune photo n'a pu être retrouvée"))
        // La fiche survit à l'échec : c'est ce qui permet de retenter le jour où l'original revient.
        assertTrue(message, message.contains("conservée dans le Journal"))
    }
}

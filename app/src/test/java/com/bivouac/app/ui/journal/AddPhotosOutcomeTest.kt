package com.bivouac.app.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * RIC-43 : la règle du bouton « Ajouter » du bandeau Photos, celle que la recette a prise en
 * défaut : un appui produit toujours quelque chose de visible.
 *
 * Ce qui manquait n'était pas une branche, c'était un événement : après un refus définitif, la
 * demande de permission était relancée à chaque appui, Android rendait la main sans rien afficher,
 * et le seul retour prévu (une ligne de texte dans le bandeau) était déjà à l'écran depuis le
 * premier refus. Trois appuis, aucun changement, un bouton qui semble mort.
 *
 * Test JVM pur, sans Robolectric : c'est une table de décision, elle n'a besoin ni de Context ni de
 * permission réelle. Ce que ce fichier ne couvre pas, faute de pouvoir simuler un refus définitif
 * hors appareil, c'est la classification elle-même (PhotoLibraryPermission.isPermanentlyDenied) ;
 * elle reste à vérifier à la main sur téléphone.
 */
class AddPhotosOutcomeTest {

    @Test
    fun aGrantedPermissionOpensThePicker() {
        assertEquals(
            AddPhotosOutcome.OPEN_PICKER,
            addPhotosOutcome(photosEnabled = true, permissionGranted = true, permanentlyDenied = false),
        )
    }

    @Test
    fun aFirstAttemptWithoutPermissionAsksTheSystem() {
        assertEquals(
            AddPhotosOutcome.REQUEST_PERMISSION,
            addPhotosOutcome(photosEnabled = true, permissionGranted = false, permanentlyDenied = false),
        )
    }

    /**
     * Le cœur du correctif : une fois le refus devenu définitif, on n'insiste plus auprès du
     * système (il rendrait la main sans rien afficher), on explique.
     */
    @Test
    fun aPermanentRefusalExplainsInsteadOfAskingAgain() {
        assertEquals(
            AddPhotosOutcome.EXPLAIN_BLOCKED,
            addPhotosOutcome(photosEnabled = true, permissionGranted = false, permanentlyDenied = true),
        )
    }

    /**
     * L'invariant, exprimé tel quel plutôt que déduit des trois cas ci-dessus : hors fonctionnalité
     * débrayée, aucune combinaison ne rend une issue silencieuse. C'est ce test-là qui échouerait
     * si quelqu'un rajoutait un jour une garde qui court-circuite l'appui.
     */
    @Test
    fun noTapIsEverSilentWhilePhotosAreEnabled() {
        for (granted in listOf(true, false)) {
            for (permanent in listOf(true, false)) {
                assertNotEquals(
                    "aucun appui ne doit rester sans réaction (accordée=$granted, définitif=$permanent)",
                    AddPhotosOutcome.IGNORED,
                    addPhotosOutcome(photosEnabled = true, permissionGranted = granted, permanentlyDenied = permanent),
                )
            }
        }
    }

    /**
     * RIC-152 : la seule issue silencieuse, et elle ne dépend de rien d'autre. Le bouton n'est de
     * toute façon pas affiché quand les photos sont débrayées.
     */
    @Test
    fun disabledPhotosNeverReachThePermission() {
        for (granted in listOf(true, false)) {
            for (permanent in listOf(true, false)) {
                assertEquals(
                    AddPhotosOutcome.IGNORED,
                    addPhotosOutcome(photosEnabled = false, permissionGranted = granted, permanentlyDenied = permanent),
                )
            }
        }
    }
}

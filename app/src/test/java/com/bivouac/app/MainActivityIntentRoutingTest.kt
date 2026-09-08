package com.bivouac.app

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RIC-168 : double instance de MainActivity via le gestionnaire de fichiers (S22/One UI).
 *
 * Cause vérifiée : le manifeste ne posait aucun launchMode sur MainActivity et
 * MainActivity.kt n'avait aucun onNewIntent, donc un GPX ouvert depuis Mes fichiers pendant que
 * l'app est déjà lancée en créait une SECONDE instance dans la tâche du gestionnaire de fichiers.
 * Correctif : launchMode="singleTask" (manifeste) + onNewIntent qui route le nouvel intent vers le
 * MÊME traitement que le lancement à froid.
 *
 * Ce que ce fichier verrouille, en JVM, sans Activity ni Compose (le comportement réel sur
 * appareil, dialogue de choix d'univers RIC-104 compris, reste à la charge du pilotage sur
 * appareil, voir le rapport) :
 *  - [Intent.extractGpxUris], le routage lui-même, utilisé identiquement par onCreate et
 *    onNewIntent : c'est ce partage qui garantit le « même traitement ».
 *  - [nextIncomingGpxBatch], la règle appliquée par onNewIntent à l'état recomposable
 *    (IncomingGpxBatch) qui remplace l'ancien argument figé de setContent.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityIntentRoutingTest {

    private fun uri(value: String): Uri = Uri.parse(value)

    // --- extractGpxUris : le routage partagé par onCreate et onNewIntent ---

    @Test
    fun viewActionExtractsTheSingleDataUri() {
        val gpxUri = uri("content://com.android.externalstorage.documents/document/1234")
        val intent = Intent(Intent.ACTION_VIEW).setData(gpxUri)

        assertEquals(listOf(gpxUri), intent.extractGpxUris())
    }

    @Test
    fun viewActionWithoutDataExtractsNothing() {
        val intent = Intent(Intent.ACTION_VIEW)

        assertTrue(intent.extractGpxUris().isEmpty())
    }

    @Test
    fun sendActionExtractsTheStreamExtraUri() {
        val gpxUri = uri("content://provider/gpx/one.gpx")
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, gpxUri)

        assertEquals(listOf(gpxUri), intent.extractGpxUris())
    }

    @Test
    fun sendActionWithoutStreamExtraExtractsNothing() {
        val intent = Intent(Intent.ACTION_SEND)

        assertTrue(intent.extractGpxUris().isEmpty())
    }

    @Test
    fun sendMultipleActionExtractsEveryStreamUri() {
        val first = uri("content://provider/gpx/day1.gpx")
        val second = uri("content://provider/gpx/day2.gpx")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))

        assertEquals(listOf(first, second), intent.extractGpxUris())
    }

    @Test
    fun mainLauncherActionExtractsNothing() {
        // Relance normale depuis l'icône ou les récentes, sans aucun fichier joint : c'est CE
        // routage-là (partagé avec onCreate) qui doit renvoyer une liste vide et laisser
        // onNewIntent (via nextIncomingGpxBatch) ne rien toucher au lot déjà en attente.
        val intent = Intent(Intent.ACTION_MAIN)

        assertTrue(intent.extractGpxUris().isEmpty())
    }

    // --- nextIncomingGpxBatch : ce qu'onNewIntent applique à l'état recomposable ---

    @Test
    fun aFreshGpxBatchBumpsTheGeneration() {
        val current = IncomingGpxBatch(uris = emptyList(), generation = 0)
        val incomingUris = listOf(uri("content://provider/gpx/one.gpx"))

        val next = nextIncomingGpxBatch(current, incomingUris)

        assertEquals(incomingUris, next.uris)
        assertEquals(1, next.generation)
    }

    @Test
    fun reopeningTheExactSameFileStillBumpsTheGeneration() {
        // C'est la raison d'être du compteur de génération plutôt que d'un simple contenu comparé
        // par égalité : rouvrir deux fois le MÊME fichier depuis Mes fichiers doit rouvrir deux
        // fois le dialogue de choix d'univers (RIC-104), pas être silencieusement ignoré comme
        // « déjà traité ».
        val sameUri = uri("content://provider/gpx/one.gpx")
        val afterFirstOpen = nextIncomingGpxBatch(IncomingGpxBatch(emptyList(), 0), listOf(sameUri))

        val afterSecondOpen = nextIncomingGpxBatch(afterFirstOpen, listOf(sameUri))

        assertEquals(listOf(sameUri), afterSecondOpen.uris)
        assertEquals(afterFirstOpen.generation + 1, afterSecondOpen.generation)
    }

    @Test
    fun anIntentWithoutGpxLeavesAPendingBatchUntouched() {
        // Un intent relivré sans fichier (relance depuis les récentes, par exemple) ne doit rien
        // effacer : un dialogue de choix d'univers déjà ouvert sur le lot précédent doit le rester.
        val pending = nextIncomingGpxBatch(
            IncomingGpxBatch(emptyList(), 0),
            listOf(uri("content://provider/gpx/one.gpx")),
        )

        val afterEmptyIntent = nextIncomingGpxBatch(pending, emptyList())

        assertEquals(pending, afterEmptyIntent)
    }
}

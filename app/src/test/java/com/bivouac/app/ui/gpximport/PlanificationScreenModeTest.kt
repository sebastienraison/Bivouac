package com.bivouac.app.ui.gpximport

import com.bivouac.app.data.gpx.TrackStats
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.gpximport.GpxImportUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * RIC-164 : l'invariant « aucune carte tant qu'il n'y a rien à montrer », vérifié sans appareil.
 *
 * C'est la seule moitié du sujet qu'un test peut tenir : ce qui déclenche le téléchargement de
 * tuile, c'est la construction du MapView osmdroid par HikeMapView, donc la composition de ce
 * composable. Les deux modes sans carte ci-dessous sont exactement les états où l'écran ne le
 * compose pas ; le reste est du ressort de la lecture du composable.
 */
class PlanificationScreenModeTest {

    private val loaded = GpxImportUiState.Loaded(
        track = HikeTrack(name = "Trace", points = emptyList()),
        stats = TrackStats(
            distanceMeters = 0.0,
            elevationGainMeters = 0.0,
            elevationLossMeters = 0.0,
            estimatedDurationMinutes = 0,
        ),
    )

    private fun carriesAMap(mode: PlanificationScreenMode): Boolean =
        mode == PlanificationScreenMode.BANK || mode == PlanificationScreenMode.DETAIL

    /**
     * Le défaut lui-même : au tout premier lancement, la banque n'a pas encore répondu et rien
     * n'est ouvert. Avant RIC-164, cet état tombait sur la branche « banque non vide », qui compose
     * une carte : une tuile OpenTopoMap partait alors que l'écran final n'en montre aucune.
     */
    @Test
    fun firstLaunchBeforeTheBankHasAnswered_carriesNoMap() {
        val mode = planificationScreenMode(
            uiState = GpxImportUiState.Idle,
            bankedTracesEmpty = true,
            bankedTracesLoaded = false,
        )

        assertEquals(PlanificationScreenMode.LOADING, mode)
        assertFalse("aucune carte ne doit exister avant la réponse de la banque", carriesAMap(mode))
    }

    /** Même fenêtre, mais une session précédente est en cours de restauration : toujours pas de carte. */
    @Test
    fun restoringASessionBeforeTheBankHasAnswered_carriesNoMap() {
        val mode = planificationScreenMode(
            uiState = GpxImportUiState.Loading,
            bankedTracesEmpty = true,
            bankedTracesLoaded = false,
        )

        assertEquals(PlanificationScreenMode.LOADING, mode)
        assertFalse(carriesAMap(mode))
    }

    /**
     * RIC-243 : la fenêtre que RIC-164 laissait ouverte. La banque a déjà répondu (vide) mais la
     * restauration de la session précédente est encore en vol : `Loading` tombait dans le `else`
     * et rendait BANK, donc un MapView et une tuile OpenTopoMap, puis la restauration répondait
     * « rien » et l'écran passait à EMPTY sans que personne ait vu la carte.
     */
    @Test
    fun loadingAfterTheBankHasAnsweredEmpty_carriesNoMap() {
        val mode = planificationScreenMode(
            uiState = GpxImportUiState.Loading,
            bankedTracesEmpty = true,
            bankedTracesLoaded = true,
        )

        assertEquals(PlanificationScreenMode.LOADING, mode)
        assertFalse("une restauration ou un import en vol n'est pas quelque chose à montrer", carriesAMap(mode))
    }

    /**
     * RIC-243 : même règle banque non vide. C'est aussi le chemin de l'ouverture d'une trace depuis
     * la liste (openFromBank passe par Loading) et de la restauration d'une session qui aboutit
     * à Loaded : aucune carte de fond tant que la lecture est en vol.
     */
    @Test
    fun loadingAfterTheBankHasAnsweredNonEmpty_carriesNoMap() {
        val mode = planificationScreenMode(
            uiState = GpxImportUiState.Loading,
            bankedTracesEmpty = false,
            bankedTracesLoaded = true,
        )

        assertEquals(PlanificationScreenMode.LOADING, mode)
        assertFalse(carriesAMap(mode))
    }

    /** L'écran d'accueil de RIC-105 : banque vraiment vide, et toujours aucune carte. */
    @Test
    fun emptyBank_carriesNoMap() {
        val mode = planificationScreenMode(
            uiState = GpxImportUiState.Idle,
            bankedTracesEmpty = true,
            bankedTracesLoaded = true,
        )

        assertEquals(PlanificationScreenMode.EMPTY, mode)
        assertFalse(carriesAMap(mode))
    }

    // Les deux états qui montrent réellement une carte : la corriger ne doit pas les avoir emportés.
    @Test
    fun aNonEmptyBankStillShowsItsMap() {
        assertEquals(
            PlanificationScreenMode.BANK,
            planificationScreenMode(GpxImportUiState.Idle, bankedTracesEmpty = false, bankedTracesLoaded = true),
        )
    }

    @Test
    fun anOpenTrackAlwaysShowsItsMapEvenBeforeTheBankHasAnswered() {
        assertEquals(
            PlanificationScreenMode.DETAIL,
            planificationScreenMode(loaded, bankedTracesEmpty = true, bankedTracesLoaded = false),
        )
        assertEquals(
            PlanificationScreenMode.DETAIL,
            planificationScreenMode(loaded, bankedTracesEmpty = false, bankedTracesLoaded = true),
        )
    }

    /**
     * Un échec d'import ne doit pas escamoter le tiroir qui le raconte : cet état garde la carte de
     * fond et son tiroir, comme avant.
     */
    @Test
    fun anImportErrorKeepsTheSheetAndItsMap() {
        assertEquals(
            PlanificationScreenMode.BANK,
            planificationScreenMode(
                GpxImportUiState.Error("boum"),
                bankedTracesEmpty = true,
                bankedTracesLoaded = true,
            ),
        )
    }
}

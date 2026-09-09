package com.bivouac.app.data.prefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIC-157 : la décision « faut-il poser cette proposition maintenant ». C'est la seule partie du
 * mécanisme qui puisse harceler l'utilisateur si elle est fausse, d'où une fonction pure et une
 * suite qui couvre chacune des trois issues sur plusieurs versions successives.
 */
class UpdatePromptDecisionTest {

    private val currentVersion = 11

    @Test
    fun neverPromptedAndConditionTrue_prompts() {
        assertTrue(
            shouldShowUpdatePrompt(currentVersion, UpdatePromptState(), conditionMet = true),
        )
    }

    // La condition métier est souveraine : sans objet, pas de question, quelle que soit la version.
    @Test
    fun conditionFalse_neverPromptsEvenOnABrandNewVersion() {
        assertFalse(shouldShowUpdatePrompt(currentVersion, UpdatePromptState(), conditionMet = false))
        assertFalse(
            shouldShowUpdatePrompt(
                currentVersion,
                UpdatePromptState(lastPromptedVersionCode = 9),
                conditionMet = false,
            ),
        )
    }

    /**
     * « Peut-être plus tard » : la version courante est enregistrée, donc la question ne revient pas
     * au lancement suivant. C'est la distinction qui fait tout l'intérêt de cette issue : sans
     * elle, « plus tard » voudrait dire « dans dix secondes ».
     */
    @Test
    fun alreadyPromptedInThisVersion_staysQuietHoweverManyLaunches() {
        val state = UpdatePromptState(lastPromptedVersionCode = currentVersion)
        assertFalse(shouldShowUpdatePrompt(currentVersion, state, conditionMet = true))
    }

    @Test
    fun promptedInAPreviousVersion_asksAgainOnTheNextOne() {
        val state = UpdatePromptState(lastPromptedVersionCode = 10)
        assertTrue(shouldShowUpdatePrompt(currentVersion, state, conditionMet = true))
    }

    // « Ne plus me le proposer » est définitif : aucune version future ne le relève, y compris une
    // version très éloignée.
    @Test
    fun dismissedForever_neverPromptsAgainOnAnyVersion() {
        val state = UpdatePromptState(lastPromptedVersionCode = 10, dismissedForever = true)
        assertFalse(shouldShowUpdatePrompt(currentVersion, state, conditionMet = true))
        assertFalse(shouldShowUpdatePrompt(999, state, conditionMet = true))
    }

    /**
     * Le scénario complet, sur trois versions : proposé en v11, reporté, silencieux tant qu'on
     * reste en v11, reposé en v12, définitivement refusé, silencieux en v13.
     */
    @Test
    fun theWholeLifeOfAPromptAcrossThreeVersions() {
        var state = UpdatePromptState()

        assertTrue("première rencontre", shouldShowUpdatePrompt(11, state, conditionMet = true))
        // « Peut-être plus tard » enregistre la version où la question a été posée.
        state = state.copy(lastPromptedVersionCode = 11)
        assertFalse("relancé le lendemain, même version", shouldShowUpdatePrompt(11, state, conditionMet = true))

        assertTrue("mise à jour vers la 12", shouldShowUpdatePrompt(12, state, conditionMet = true))
        state = state.copy(lastPromptedVersionCode = 12, dismissedForever = true)

        assertFalse("mise à jour vers la 13", shouldShowUpdatePrompt(13, state, conditionMet = true))
    }
}

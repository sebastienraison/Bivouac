package com.bivouac.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * RIC-157 : les propositions posées à l'utilisateur après une mise à jour de l'app.
 *
 * DataStore à part, et pas une poignée de clés de plus dans `bivouac_settings` : ce n'est pas un
 * réglage, c'est de la comptabilité de cycle de vie. La sauvegarde (BIV-66) embarque nommément le
 * fichier des Réglages ; y verser ceci ferait voyager « j'ai déjà répondu » dans une archive, ce
 * que personne n'a demandé. Une restauration sur un appareil neuf peut donc reposer la question,
 * ce qui est le bon comportement : les propositions dépendent d'une condition métier, et si elle
 * est encore vraie sur la base restaurée, la question l'est aussi.
 */
private const val UPDATE_PROMPTS_DATASTORE_NAME = "bivouac_update_prompts"
private val Context.updatePromptsDataStore by preferencesDataStore(name = UPDATE_PROMPTS_DATASTORE_NAME)

/**
 * RIC-157 : ce qui est retenu d'une proposition, entre deux lancements.
 *
 * [lastPromptedVersionCode] est `null` tant qu'elle n'a jamais été posée : une installation neuve
 * est donc traitée comme un changement de version, ce qui est exact (on n'a jamais rien proposé
 * sous cette version-ci). [dismissedForever] est définitif et ne se relève jamais tout seul.
 */
data class UpdatePromptState(
    val lastPromptedVersionCode: Int? = null,
    val dismissedForever: Boolean = false,
)

/**
 * RIC-157 : faut-il poser la proposition maintenant ?
 *
 * Trois conditions, dans l'ordre où elles coûtent :
 * 1. La condition métier est vraie. Elle est souveraine : une proposition sans objet ne se pose
 *    pas, même à un changement de version.
 * 2. L'utilisateur n'a pas dit « ne plus me le proposer ». Définitif, sans expiration.
 * 3. La version de l'app a changé depuis la dernière fois qu'on a posé la question. C'est là toute
 *    la différence entre « peut-être plus tard » et « au prochain lancement » : répondre « plus
 *    tard » enregistre la version courante, donc la question ne revient qu'à la version suivante,
 *    pas au redémarrage d'après.
 *
 * Fonction pure, volontairement séparée de la persistance et de l'UI : c'est la seule partie de ce
 * mécanisme qui puisse harceler l'utilisateur si elle est fausse, et c'est la seule qui se teste
 * sans appareil.
 */
fun shouldShowUpdatePrompt(
    currentVersionCode: Int,
    state: UpdatePromptState,
    conditionMet: Boolean,
): Boolean {
    if (!conditionMet) return false
    if (state.dismissedForever) return false
    return state.lastPromptedVersionCode != currentVersionCode
}

/**
 * RIC-157 : la persistance des propositions, une clé par proposition.
 *
 * Générique par construction : le mécanisme (poser une question à un changement de version, avec
 * les trois issues agir / plus tard / plus jamais) n'a rien de propre au stockage des photos, qui
 * n'en est que le premier usage. Une proposition suivante n'aura qu'à choisir sa clé et sa
 * condition métier.
 *
 * La clé est un identifiant stable, pas un libellé : la renommer ferait réapparaître une question
 * déjà répondue.
 */
class UpdatePromptPreferences(private val context: Context) {

    private fun versionKey(promptKey: String) = intPreferencesKey("prompt_${promptKey}_last_version")

    private fun neverKey(promptKey: String) = booleanPreferencesKey("prompt_${promptKey}_never")

    suspend fun read(promptKey: String): UpdatePromptState {
        val prefs = context.updatePromptsDataStore.data.first()
        return UpdatePromptState(
            lastPromptedVersionCode = prefs[versionKey(promptKey)],
            dismissedForever = prefs[neverKey(promptKey)] ?: false,
        )
    }

    /**
     * La question vient d'être posée sous [versionCode], quelle que soit la réponse (agir ou
     * « peut-être plus tard ») : dans les deux cas elle ne doit pas revenir avant la version
     * suivante.
     *
     * Écrit au moment où la question est POSÉE et non au moment où elle reçoit une réponse : un
     * utilisateur qui tue l'app sur le dialogue ne doit pas le retrouver à chaque lancement.
     */
    suspend fun markProposed(promptKey: String, versionCode: Int) {
        context.updatePromptsDataStore.edit { it[versionKey(promptKey)] = versionCode }
    }

    /** « Ne plus me le proposer » : définitif, aucune version future ne le relève. */
    suspend fun markDismissedForever(promptKey: String) {
        context.updatePromptsDataStore.edit { it[neverKey(promptKey)] = true }
    }
}

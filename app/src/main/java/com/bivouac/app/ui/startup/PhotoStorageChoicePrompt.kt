package com.bivouac.app.ui.startup

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bivouac.app.BuildConfig
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.photo.PhotoStorageMode
import com.bivouac.app.data.prefs.SettingsPreferences
import com.bivouac.app.data.prefs.UpdatePromptPreferences
import com.bivouac.app.data.prefs.shouldShowUpdatePrompt
import com.bivouac.app.ui.settings.PhotoStorageModeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RIC-157 : premier usage du mécanisme de proposition post-mise à jour (voir
 * [PostUpdatePromptDialog] et UpdatePromptPreferences).
 *
 * Condition métier : le Journal contient déjà des photos ET l'utilisateur n'a jamais tranché le
 * mode de stockage. C'est exactement le profil de quelqu'un qui a constitué un stock sous le
 * régime de la copie intégrale : lui, et lui seul, a une raison d'être interrogé. Une installation
 * neuve part en copie réduite sans que la question se pose (voir PhotoStoragePolicy.resolve, et le
 * verrouillage du défaut au premier import dans JournalViewModel).
 */
class PhotoStorageChoiceViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        /** Rien à l'écran : la lecture des préférences et le comptage sont en cours. */
        data object Checking : State

        /** Rien à proposer, ou déjà répondu : le dialogue n'existera pas de cette session. */
        data object Hidden : State

        data object Prompting : State
    }

    private val settingsPreferences = SettingsPreferences(application)
    private val updatePromptPreferences = UpdatePromptPreferences(application)
    private val repository = LoggedTrackRepository(application)

    private val _state = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val undecided = settingsPreferences.photoStorageModeDecision.first() == null
            // Le COUNT n'est fait que si la question peut encore se poser : sur une base déjà
            // tranchée (le cas de tout le monde après la première réponse), ce lancement ne touche
            // pas la base du tout.
            val conditionMet = undecided && withContext(Dispatchers.IO) { repository.countAllPhotos() > 0 }
            val promptState = updatePromptPreferences.read(PROMPT_KEY)
            if (shouldShowUpdatePrompt(BuildConfig.VERSION_CODE, promptState, conditionMet)) {
                // Enregistré dès l'affichage, pas à la réponse : quelqu'un qui tue l'app sur ce
                // dialogue ne doit pas le retrouver à chaque lancement.
                updatePromptPreferences.markProposed(PROMPT_KEY, BuildConfig.VERSION_CODE)
                _state.value = State.Prompting
            } else {
                _state.value = State.Hidden
            }
        }
    }

    fun choose(mode: PhotoStorageMode) {
        _state.value = State.Hidden
        viewModelScope.launch { settingsPreferences.setPhotoStorageMode(mode) }
    }

    fun later() {
        _state.value = State.Hidden
    }

    fun never() {
        _state.value = State.Hidden
        viewModelScope.launch { updatePromptPreferences.markDismissedForever(PROMPT_KEY) }
    }

    companion object {
        /**
         * Identifiant stable de la proposition, jamais un libellé : le renommer ferait réapparaître
         * une question déjà répondue chez tous ceux qui y ont répondu.
         */
        const val PROMPT_KEY = "photo_storage_mode"
    }
}

/**
 * RIC-157 : le dialogue lui-même, posé par-dessus l'app une fois le rattrapage d'altitude terminé
 * (voir MainActivity). Ne bloque rien : c'est une proposition, pas une condition d'accès.
 */
@Composable
fun PhotoStorageChoicePrompt(viewModel: PhotoStorageChoiceViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state !is PhotoStorageChoiceViewModel.State.Prompting) return

    // Le nom et non la valeur : rememberSaveable passe par un Bundle, et une String y entre sans
    // Saver sur mesure. Présélection sur « Qualité d'archive » : c'est le régime SOUS LEQUEL cette
    // personne est aujourd'hui (elle a des photos et n'a rien tranché, voir
    // PhotoStoragePolicy.resolve). Présélectionner la copie réduite parce qu'on la recommande
    // changerait son régime au moindre appui distrait sur « Enregistrer ».
    var selectedName by rememberSaveable { mutableStateOf(PhotoStorageMode.FULL.name) }
    val selected = PhotoStorageMode.valueOf(selectedName)

    PostUpdatePromptDialog(
        title = "Le poids des photos du Journal",
        message = "Chaque photo ajoutée à une sortie est aujourd'hui copiée intégralement, soit " +
            "environ 4 Mo pièce : quelques dizaines de sorties suffisent à occuper plusieurs " +
            "centaines de Mo. Bivouac sait désormais n'en garder qu'une copie réduite, une dizaine " +
            "de fois plus légère et largement assez nette pour les revoir.",
        actionLabel = "Choisir maintenant",
        onLater = viewModel::later,
        onNever = viewModel::never,
    ) { onDone ->
        Column {
            PhotoStorageModeChoice(mode = selected, onModeSelected = { selectedName = it.name })
            TextButton(
                onClick = {
                    viewModel.choose(selected)
                    onDone()
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Enregistrer")
            }
        }
    }
}

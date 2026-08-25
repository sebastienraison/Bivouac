package com.bivouac.app.ui.startup

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bivouac.app.data.db.LoggedTrackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RIC-19 §5 : rattrapage des colonnes d'altitude (maxElevationMeters/lastPointElevationMeters),
 * lancé au tout premier composable de l'appli plutôt que depuis JournalViewModel — préférence
 * utilisateur documentée, délibérément à l'opposé du patron fire-and-forget de
 * [LoggedTrackRepository.backfillDenormalizedFields] (annulable en quittant le Journal, RIC-132) :
 * ici, rien n'est navigable tant que ce n'est pas terminé (voir [ElevationBackfillGate]).
 *
 * AndroidViewModel plutôt qu'un simple état local à BivouacApp : obtenu via `viewModel()`, il
 * survit à une rotation d'écran pendant que le rattrapage tourne (le ViewModelStore appartient à
 * l'Activity, pas au composable), sans quoi une rotation malheureuse le relancerait depuis zéro.
 */
class ElevationBackfillViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        data object Checking : State
        data class Running(val done: Int, val total: Int) : State
        data object Ready : State
    }

    private val repository = LoggedTrackRepository(application)
    private val _state = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val remaining = withContext(Dispatchers.IO) { repository.countDaysNeedingElevationBackfill() }
            if (remaining == 0) {
                // Cas de très loin le plus fréquent (banque déjà à jour, ou toute nouvelle
                // installation) : aucun popup ne doit même apparaître, pas même une frame.
                _state.value = State.Ready
                return@launch
            }
            withContext(Dispatchers.IO) {
                repository.backfillElevationFields { done, total -> _state.value = State.Running(done, total) }
            }
            _state.value = State.Ready
        }
    }
}

/**
 * [content] (le reste de l'appli, NavHost compris) n'est composé qu'une fois le rattrapage terminé
 * ou constaté inutile. Tant que ce n'est pas le cas, seuls un fond neutre et un popup non
 * dismissable (retour et tap extérieur désarmés, comme [com.bivouac.app.ui.journal.JournalScreen]
 * le fait déjà pour son propre popup d'import en cours) sont affichés — la navigation est bloquée
 * par construction, puisque le NavHost lui-même n'est pas encore monté.
 */
@Composable
fun ElevationBackfillGate(
    modifier: Modifier = Modifier,
    viewModel: ElevationBackfillViewModel = viewModel(),
    content: @Composable () -> Unit,
) {
    when (val current = viewModel.state.collectAsStateWithLifecycle().value) {
        ElevationBackfillViewModel.State.Ready -> content()
        else -> {
            Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text("Mise à jour du carnet") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        Text(
                            when (current) {
                                ElevationBackfillViewModel.State.Checking -> "Préparation…"
                                is ElevationBackfillViewModel.State.Running ->
                                    "Lecture des altitudes : ${current.done}/${current.total}…"
                                ElevationBackfillViewModel.State.Ready -> ""
                            },
                        )
                    }
                },
                confirmButton = {},
            )
        }
    }
}

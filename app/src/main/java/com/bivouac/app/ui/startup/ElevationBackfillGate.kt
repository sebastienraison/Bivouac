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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bivouac.app.R
import com.bivouac.app.data.db.BankedTrackRepository
import com.bivouac.app.data.db.CalibrationRefresh
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.prefs.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RIC-19 §5 : rattrapage des colonnes d'altitude (maxElevationMeters/lastPointElevationMeters),
 * lancé au tout premier composable de l'appli plutôt que depuis JournalViewModel : préférence
 * utilisateur documentée, délibérément à l'opposé du patron fire-and-forget de
 * [LoggedTrackRepository.backfillDenormalizedFields] (annulable en quittant le Journal, RIC-132) :
 * ici, rien n'est navigable tant que ce n'est pas terminé (voir [ElevationBackfillGate]).
 *
 * RIC-114 lot 2 (conception §5.3) : cette même porte enchaîne désormais trois phases de plus,
 * dans cet ordre, sous **un seul compteur de progression** (les done/total de chaque phase se
 * concatènent plutôt que de faire clignoter le popup à zéro entre deux phases) :
 * 1. l'altitude ci-dessus (RIC-19, inchangée) ;
 * 2. les statistiques du Journal ([LoggedTrackRepository.backfillStatsFields]) ;
 * 3. les statistiques de la Banque ([BankedTrackRepository.backfillStatsFields]) ;
 * puis, silencieusement et sans jamais compter dans ce total (elle ne lit que des sommes déjà en
 * base, conception §5.3) : la recalibration Auto/Sélection ([CalibrationRefresh.refreshIfNeeded]).
 * Une installation qui n'a rien à rattraper sur 1-3 ne montre donc jamais le popup, même si la
 * phase 4 (calibration) a du travail (cas d'une installation neuve).
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
    private val bankedRepository = BankedTrackRepository(application)
    private val settingsPreferences = SettingsPreferences(application)
    private val _state = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val elevationRemaining = withContext(Dispatchers.IO) { repository.countDaysNeedingElevationBackfill() }
            val journalStatsRemaining = withContext(Dispatchers.IO) { repository.countTracksNeedingStatsBackfill() }
            val bankedStatsRemaining = withContext(Dispatchers.IO) { bankedRepository.countTracksNeedingStatsBackfill() }
            val total = elevationRemaining + journalStatsRemaining + bankedStatsRemaining
            if (total == 0) {
                // Cas de très loin le plus fréquent (banque déjà à jour, ou toute nouvelle
                // installation) : aucun popup ne doit même apparaître, pas même une frame.
                _state.value = State.Ready
            } else {
                var base = 0
                if (elevationRemaining > 0) {
                    withContext(Dispatchers.IO) {
                        repository.backfillElevationFields { done, _ -> _state.value = State.Running(base + done, total) }
                    }
                    base += elevationRemaining
                }
                if (journalStatsRemaining > 0) {
                    withContext(Dispatchers.IO) {
                        repository.backfillStatsFields { done, _ -> _state.value = State.Running(base + done, total) }
                    }
                    base += journalStatsRemaining
                }
                if (bankedStatsRemaining > 0) {
                    withContext(Dispatchers.IO) {
                        bankedRepository.backfillStatsFields { done, _ -> _state.value = State.Running(base + done, total) }
                    }
                    base += bankedStatsRemaining
                }
            }
            withContext(Dispatchers.IO) {
                CalibrationRefresh.refreshIfNeeded(
                    repository = repository,
                    settingsPreferences = settingsPreferences,
                    staleJournalStatsBackfilled = journalStatsRemaining > 0,
                )
            }
            _state.value = State.Ready
        }
    }
}

/**
 * [content] (le reste de l'appli, NavHost compris) n'est composé qu'une fois le rattrapage terminé
 * ou constaté inutile. Tant que ce n'est pas le cas, la navigation est bloquée par construction
 * (le NavHost lui-même n'est pas encore monté), mais le dialogue ne s'affiche, lui, que pendant
 * [ElevationBackfillViewModel.State.Running], jamais pendant [ElevationBackfillViewModel.State.
 * Checking] : ce dernier n'est qu'une requête COUNT (quasi instantanée, y compris sur une grosse
 * banque), et l'immense majorité des lancements n'ont rien à rattraper : un dialogue qui
 * apparaîtrait puis disparaîtrait aussitôt à CHAQUE lancement de l'app serait plus gênant qu'utile.
 * Checking se contente donc du même fond neutre que Running, sans le popup par-dessus.
 */
@Composable
fun ElevationBackfillGate(
    modifier: Modifier = Modifier,
    viewModel: ElevationBackfillViewModel = viewModel(),
    content: @Composable () -> Unit,
) {
    when (val current = viewModel.state.collectAsStateWithLifecycle().value) {
        ElevationBackfillViewModel.State.Ready -> content()
        ElevationBackfillViewModel.State.Checking -> {
            Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }
        is ElevationBackfillViewModel.State.Running -> {
            Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text(stringResource(R.string.msg_elevation_backfill_title)) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        Text(
                            stringResource(
                                R.string.msg_elevation_backfill_progress,
                                current.done,
                                current.total,
                            ),
                        )
                    }
                },
                confirmButton = {},
            )
        }
    }
}

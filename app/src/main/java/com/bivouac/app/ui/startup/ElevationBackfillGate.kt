package com.bivouac.app.ui.startup

import android.app.Application
import android.util.Log
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
import com.bivouac.app.data.db.refusedStops
import com.bivouac.app.data.gpx.BIVOUAC_REVIEW_VERSION
import com.bivouac.app.data.prefs.SettingsPreferences
import com.bivouac.app.journal.BivouacQuestion
import com.bivouac.app.journal.BivouacReview
import com.bivouac.app.journal.BivouacReviewBatch
import com.bivouac.app.ui.journal.BivouacDetectedDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
 * RIC-227 (lot 3 de la 2.7.0, conception RIC-222 §3.2) : enfin, après les rattrapages et la
 * calibration, le crible des bivouacs détectés ([reviewBivouacs]) pour chaque rando dont
 * `bivouacReviewVersion` est inférieure à [BIVOUAC_REVIEW_VERSION] : détection rando par rando sur
 * Dispatchers.IO sous l'état [State.Running] existant, puis une question par bivouac trouvé
 * ([State.Reviewing]), la réponse de chaque rando étant appliquée dès sa dernière question.
 * Bloquant comme le rattrapage : le reste de l'app n'est composé qu'à [State.Ready].
 *
 * AndroidViewModel plutôt qu'un simple état local à BivouacApp : obtenu via `viewModel()`, il
 * survit à une rotation d'écran pendant que le rattrapage tourne (le ViewModelStore appartient à
 * l'Activity, pas au composable), sans quoi une rotation malheureuse le relancerait depuis zéro.
 */
class ElevationBackfillViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        data object Checking : State
        data class Running(val done: Int, val total: Int) : State

        /** RIC-227 : une question « Arrêt prolongé détecté » attend sa réponse ([answerBivouacQuestion]). */
        data class Reviewing(val question: BivouacQuestion) : State
        data object Ready : State
    }

    /** RIC-227 : clé d'une rando du crible, son identifiant et ceux de ses jours à la détection. */
    private data class ReviewKey(val trackId: String, val dayIds: List<Long>)

    private val repository = LoggedTrackRepository(application)
    private val bankedRepository = BankedTrackRepository(application)
    private val settingsPreferences = SettingsPreferences(application)
    private val _state = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = _state.asStateFlow()

    // RIC-227 : la réponse attendue par la question affichée ; aucun délai, aucune valeur par défaut.
    private var pendingAnswer: CompletableDeferred<BivouacReview.Answer>? = null

    init {
        viewModelScope.launch {
            val elevationRemaining = withContext(Dispatchers.IO) { repository.countDaysNeedingElevationBackfill() }
            val journalStatsRemaining = withContext(Dispatchers.IO) { repository.countTracksNeedingStatsBackfill() }
            val bankedStatsRemaining = withContext(Dispatchers.IO) { bankedRepository.countTracksNeedingStatsBackfill() }
            val total = elevationRemaining + journalStatsRemaining + bankedStatsRemaining
            // RIC-227 : compté dès maintenant, pour ne composer le reste de l'app qu'après le
            // crible quand il y a des randos à examiner.
            val reviewRemaining = withContext(Dispatchers.IO) {
                repository.countTracksNeedingBivouacReview(BIVOUAC_REVIEW_VERSION)
            }
            if (total == 0) {
                // Cas de très loin le plus fréquent (banque déjà à jour, ou toute nouvelle
                // installation) : aucun popup ne doit même apparaître, pas même une frame.
                if (reviewRemaining == 0) _state.value = State.Ready
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
            if (reviewRemaining > 0) reviewBivouacs()
            _state.value = State.Ready
        }
    }

    /** RIC-227 : réponse à la question affichée ; [BivouacReview.Answer.PAUSE] aussi en quittant le dialogue (RIC-229). */
    fun answerBivouacQuestion(answer: BivouacReview.Answer) {
        pendingAnswer?.complete(answer)
    }

    /**
     * RIC-227 : le crible du premier lancement.
     *
     * 1. Détection, rando par rando, sur Dispatchers.IO, sous [State.Running] (randos traitées sur
     *    randos à examiner). Une rando sans bivouac, y compris une rando dont aucun jour n'est
     *    horodaté, est marquée examinée aussitôt, sans question. Une rando dont un fichier est
     *    illisible n'est pas marquée : elle sera retentée au lancement suivant (journalisé).
     * 2. Questions, dans l'ordre des randos (de la plus ancienne à la plus récente) puis de
     *    [BivouacReview.questions], le compteur portant sur le total des bivouacs à examiner.
     * 3. Dès la dernière question d'une rando : découpages et version du crible
     *    ([LoggedTrackRepository.applyBivouacReview]). Si l'app est tuée avant, cette rando reste
     *    non examinée et sera reproposée en entier au lancement suivant ; celles déjà traitées ne le
     *    sont plus. Un découpage en échec est journalisé et laisse la rando non examinée.
     *
     * RIC-229 : la détection porte sur les bivouacs effectifs de chaque jour (une nuit déjà
     * refusée n'est pas reposée), et les réponses « Pause » sont écrites avec les découpages. Si
     * une rando au moins a changé, la calibration Auto est recalculée une fois, à la fin, comme
     * après un import.
     */
    private suspend fun reviewBivouacs() {
        val tracks = withContext(Dispatchers.IO) { repository.tracksNeedingBivouacReview(BIVOUAC_REVIEW_VERSION) }
        val pending = mutableListOf<BivouacReviewBatch.Track<ReviewKey>>()
        tracks.forEachIndexed { done, track ->
            _state.value = State.Running(done, tracks.size)
            val found = withContext(Dispatchers.IO) {
                try {
                    val (days, points) = repository.daysWithPoints(track.id)
                    val questions = BivouacReview.startupQuestions(track.bivouacReviewVersion) {
                        BivouacReview.effectiveDays(points, days.map { it.refusedStops() })
                    }.orEmpty()
                    if (questions.isEmpty()) {
                        repository.markBivouacReviewed(track.id, BIVOUAC_REVIEW_VERSION)
                        null
                    } else {
                        BivouacReviewBatch.Track(ReviewKey(track.id, days.map { it.id }), track.name, questions)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Crible des bivouacs : rando ${track.id} illisible, reportée au lancement suivant", e)
                    null
                }
            }
            if (found != null) pending += found
        }
        // Ordre, compteur global et réponses par rando : BivouacReviewBatch, partagé avec les
        // questions d'après le rapport d'un import en sorties séparées (JournalViewModel).
        val batch = BivouacReviewBatch(pending)
        val answers = mutableListOf<BivouacReview.Answer>()
        var changed = false
        for (index in 0 until batch.total) {
            val answer = CompletableDeferred<BivouacReview.Answer>()
            pendingAnswer = answer
            _state.value = State.Reviewing(batch.questionAt(index))
            answers += answer.await()
            pendingAnswer = null
            val completed = batch.completedBy(answers) ?: continue
            val key = completed.track.key
            withContext(Dispatchers.IO) {
                try {
                    changed = repository.applyBivouacReview(
                        trackId = key.trackId,
                        splitsByDayId = completed.outcome.splits.mapKeys { (position, _) -> key.dayIds[position] },
                        reviewVersion = completed.outcome.reviewVersion,
                        pausesByDayId = completed.outcome.pauses.mapKeys { (position, _) -> key.dayIds[position] },
                    ) || changed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Crible des bivouacs : découpage de la rando ${key.trackId} en échec", e)
                }
            }
        }
        if (changed) {
            withContext(Dispatchers.IO) {
                try {
                    CalibrationRefresh.refreshAuto(repository, settingsPreferences)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Crible des bivouacs : recalcul de la calibration Auto en échec", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "ElevationBackfillGate"
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
        // RIC-227 : le crible des bivouacs, même fond neutre, la question par-dessus.
        is ElevationBackfillViewModel.State.Reviewing -> {
            Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            BivouacDetectedDialog(
                question = current.question,
                onSplit = { viewModel.answerBivouacQuestion(BivouacReview.Answer.SPLIT) },
                onPause = { viewModel.answerBivouacQuestion(BivouacReview.Answer.PAUSE) },
            )
        }
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

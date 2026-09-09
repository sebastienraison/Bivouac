package com.bivouac.app.ui.startup

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bivouac.app.BuildConfig
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.operations.ExclusiveOperation
import com.bivouac.app.data.operations.ExclusiveOperations
import com.bivouac.app.data.photo.PhotoLibraryPermission
import com.bivouac.app.data.photo.PhotoRecompression
import com.bivouac.app.data.photo.PhotoStorageMode
import com.bivouac.app.data.photo.PhotoStoragePolicy
import com.bivouac.app.data.prefs.SettingsPreferences
import com.bivouac.app.data.prefs.UpdatePromptPreferences
import com.bivouac.app.data.prefs.shouldShowUpdatePrompt
import com.bivouac.app.data.storage.AppStorageUsageCalculator
import com.bivouac.app.settings.DataOperationPhase
import com.bivouac.app.settings.DataOperationProgress
import com.bivouac.app.ui.components.BlockingProgress
import com.bivouac.app.ui.components.BlockingProgressDialog
import com.bivouac.app.ui.settings.PhotoGalleryActionOutcome
import com.bivouac.app.ui.settings.PhotoStorageModeChoice
import com.bivouac.app.ui.settings.countLabel
import com.bivouac.app.ui.settings.formatBytes
import com.bivouac.app.ui.settings.openApplicationSettings
import com.bivouac.app.ui.settings.photoGalleryActionOutcome
import com.bivouac.app.ui.settings.recompressionReportMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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

    // RIC-152 : même lecture que SettingsViewModel/StorageUsageViewModel, pour que le clic sur
    // « Recompresser » de la proposition ci-dessous applique la même garde qu'ailleurs (jamais de
    // demande de permission galerie quand les photos sont débrayées) sans devoir la relire à la
    // main à chaque appel.
    val photosEnabled: StateFlow<Boolean> = settingsPreferences.photosEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * RIC-157 : la proposition d'enchaîner sur la recompression du stock, une fois le mode
     * enregistré. Un état séparé de [state] : ce dialogue-ci doit rester à l'écran alors que
     * [state] est déjà retombé à [State.Hidden] (voir [choose]).
     */
    private val _recompressionOffer = MutableStateFlow<PhotoRecompression.Estimate?>(null)
    val recompressionOffer: StateFlow<PhotoRecompression.Estimate?> = _recompressionOffer.asStateFlow()

    private val _recompressionProgress = MutableStateFlow<DataOperationProgress?>(null)
    val recompressionProgress: StateFlow<DataOperationProgress?> = _recompressionProgress.asStateFlow()

    private val _recompressionReport = MutableStateFlow<LoggedTrackRepository.PhotoRecompressionReport?>(null)
    val recompressionReport: StateFlow<LoggedTrackRepository.PhotoRecompressionReport?> = _recompressionReport.asStateFlow()

    private val _recompressionError = MutableStateFlow<String?>(null)
    val recompressionError: StateFlow<String?> = _recompressionError.asStateFlow()

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
        viewModelScope.launch {
            settingsPreferences.setPhotoStorageMode(mode)
            offerRecompressionIfNeeded(mode)
        }
    }

    fun later() {
        _state.value = State.Hidden
    }

    fun never() {
        _state.value = State.Hidden
        viewModelScope.launch { updatePromptPreferences.markDismissedForever(PROMPT_KEY) }
    }

    /**
     * RIC-157 : la même proposition que dans les Réglages (voir
     * SettingsViewModel.choosePhotoStorageMode), pour ce second point d'entrée du même choix
     * partagé (PhotoStorageModeChoice).
     *
     * Le mode PRÉCÉDENT n'a pas besoin d'être lu : la condition même qui ouvre ce dialogue (voir
     * la kdoc de la classe) garantit qu'il n'y avait aucune décision enregistrée et qu'il existait
     * déjà des photos, donc que [PhotoStoragePolicy.resolve] rendait FULL. [previousMode] n'est
     * donc jamais que ce constat, pas une hypothèse.
     *
     * RIC-152 : rien n'est calculé si les photos sont débrayées, comme ailleurs dans l'app : ce
     * cas ne montre jamais la recompression, et n'a donc pas à payer le relevé qui la chiffre.
     */
    private suspend fun offerRecompressionIfNeeded(newMode: PhotoStorageMode) {
        if (!settingsPreferences.photosEnabled.first()) return
        val previousMode = PhotoStoragePolicy.resolve(decision = null, hasExistingPhotos = true)
        val usage = withContext(Dispatchers.IO) {
            AppStorageUsageCalculator.compute(getApplication(), repository.allPhotos())
        }
        if (
            PhotoRecompression.shouldOfferRecompressionAfterModeChange(
                previousMode = previousMode,
                newMode = newMode,
                fullPhotoCount = usage.photos.fullCount,
                estimate = usage.recompression,
            )
        ) {
            _recompressionOffer.value = usage.recompression
        }
    }

    fun dismissRecompressionOffer() {
        _recompressionOffer.value = null
    }

    /**
     * RIC-157 : même opération que StorageUsageViewModel.recompressPhotos et
     * SettingsViewModel.recompressPhotosFromOffer, un troisième point de départ pour le même
     * verrou et le même appel : ce dialogue de démarrage a sa propre fenêtre modale et son propre
     * ViewModel, sans accès à celui des Réglages.
     *
     * L'appelant a déjà vérifié la permission galerie, même discipline qu'ailleurs.
     */
    fun recompressPhotosFromOffer() {
        _recompressionOffer.value = null
        if (!ExclusiveOperations.tryStart(ExclusiveOperation.PHOTO_RECOMPRESS)) {
            _recompressionError.value = refusalMessage()
            return
        }
        _recompressionProgress.value = DataOperationProgress(DataOperationPhase.PHOTO_RECOMPRESS, done = 0, total = null)
        viewModelScope.launch {
            val report = try {
                withContext(Dispatchers.IO) {
                    repository.recompressFullPhotos { done, total ->
                        _recompressionProgress.value =
                            DataOperationProgress(DataOperationPhase.PHOTO_RECOMPRESS, done, total)
                    }
                }
            } finally {
                _recompressionProgress.value = null
                ExclusiveOperations.finish(ExclusiveOperation.PHOTO_RECOMPRESS)
            }
            _recompressionReport.value = report
        }
    }

    fun dismissRecompressionReport() {
        _recompressionReport.value = null
    }

    fun dismissRecompressionError() {
        _recompressionError.value = null
    }

    // Censé inatteignable à cet instant du process (rien d'autre ne tourne au démarrage), même
    // politique défensive que les autres verrous de l'app pour un chemin oublié.
    private fun refusalMessage(): String {
        val ongoing = ExclusiveOperations.current.value?.label ?: "une autre opération"
        return "Impossible pour l'instant : $ongoing est en cours. Attends qu'elle se termine, puis recommence."
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
    val photosEnabled by viewModel.photosEnabled.collectAsStateWithLifecycle()
    // RIC-157 : collectés en dehors du early-return ci-dessous : ce dialogue-ci doit rester monté
    // alors que `state` est déjà retombé à Hidden (choose() le fait avant même de calculer
    // l'estimation, voir la kdoc du ViewModel).
    val recompressionOffer by viewModel.recompressionOffer.collectAsStateWithLifecycle()
    val recompressionProgress by viewModel.recompressionProgress.collectAsStateWithLifecycle()
    val recompressionReport by viewModel.recompressionReport.collectAsStateWithLifecycle()
    val recompressionError by viewModel.recompressionError.collectAsStateWithLifecycle()

    val context = LocalContext.current
    // RIC-43/151/157 : même mécanique qu'aux Réglages et sur l'écran « Espace utilisé ».
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var blockedDialog by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (PhotoLibraryPermission.isGranted(context)) {
            permanentlyDenied = false
            viewModel.recompressPhotosFromOffer()
        } else {
            permanentlyDenied = PhotoLibraryPermission.isPermanentlyDenied(context)
            if (permanentlyDenied) blockedDialog = true
        }
    }
    val onRecompressClick: () -> Unit = {
        when (
            photoGalleryActionOutcome(
                photosEnabled = photosEnabled,
                permissionGranted = PhotoLibraryPermission.isGranted(context),
                permanentlyDenied = permanentlyDenied,
            )
        ) {
            PhotoGalleryActionOutcome.IGNORED -> Unit
            PhotoGalleryActionOutcome.RUN -> viewModel.recompressPhotosFromOffer()
            PhotoGalleryActionOutcome.EXPLAIN_BLOCKED -> {
                viewModel.dismissRecompressionOffer()
                blockedDialog = true
            }
            PhotoGalleryActionOutcome.REQUEST_PERMISSION ->
                permissionLauncher.launch(PhotoLibraryPermission.requestedPermissions)
        }
    }

    if (state is PhotoStorageChoiceViewModel.State.Prompting) {
        // Le nom et non la valeur : rememberSaveable passe par un Bundle, et une String y entre
        // sans Saver sur mesure. Présélection sur « Qualité d'origine » : c'est le régime SOUS
        // LEQUEL cette personne est aujourd'hui (elle a des photos et n'a rien tranché, voir
        // PhotoStoragePolicy.resolve). Présélectionner le poids allégé parce qu'on le recommande
        // changerait son régime au moindre appui distrait sur « Enregistrer ».
        var selectedName by rememberSaveable { mutableStateOf(PhotoStorageMode.FULL.name) }
        val selected = PhotoStorageMode.valueOf(selectedName)

        PostUpdatePromptDialog(
            title = "Le poids des photos du Journal",
            message = "Chaque photo ajoutée à une sortie est aujourd'hui copiée en qualité d'origine, " +
                "soit environ 4 Mo pièce : quelques dizaines de sorties suffisent à occuper plusieurs " +
                "centaines de Mo. Bivouac sait désormais n'en garder qu'une version allégée, une " +
                "dizaine de fois plus légère et largement assez nette pour les revoir.",
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

    // RIC-157 : enchaînée juste après avoir choisi « Poids allégé » ci-dessus, quand il restait
    // des photos en qualité d'origine à reprendre. N et le poids viennent de la même estimation
    // que la carte de l'écran « Espace utilisé » (PhotoRecompression.estimate), pas d'un second
    // calcul.
    recompressionOffer?.let { estimate ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRecompressionOffer,
            title = { Text("Recompresser tes photos ?") },
            text = {
                Text(
                    "${countLabel(estimate.photoCount, "photo déjà importée reste", "photos déjà importées restent")} " +
                        "en qualité d'origine (~${formatBytes(estimate.freedBytes)}). Les recompresser " +
                        "maintenant ?",
                )
            },
            confirmButton = { TextButton(onClick = onRecompressClick) { Text("Recompresser") } },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRecompressionOffer) { Text("Plus tard") }
            },
        )
    }

    // Même dialogue bloquant, même rapport de fin que les deux autres points de départ de cette
    // opération (StorageUsageScreen, Réglages) : voir recompressionReportMessage.
    BlockingProgressDialog(
        progress = recompressionProgress?.let {
            BlockingProgress(title = it.phase.title, done = it.done, total = it.total)
        },
    )

    recompressionReport?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRecompressionReport,
            title = { Text("Recompression terminée") },
            text = { Text(recompressionReportMessage(report)) },
            confirmButton = { TextButton(onClick = viewModel::dismissRecompressionReport) { Text("OK") } },
        )
    }

    recompressionError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRecompressionError,
            title = { Text("Recompression impossible") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissRecompressionError) { Text("OK") } },
        )
    }

    if (blockedDialog) {
        AlertDialog(
            onDismissRequest = { blockedDialog = false },
            title = { Text("Accès aux photos refusé") },
            text = {
                Text(
                    "Recompresser demande de retrouver tes photos d'origine dans la galerie, et " +
                        "Android ne redemandera plus l'autorisation depuis l'application. " +
                        "Autorise l'accès à la galerie dans les réglages de l'application.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    blockedDialog = false
                    context.openApplicationSettings()
                }) { Text("Ouvrir les réglages") }
            },
            dismissButton = { TextButton(onClick = { blockedDialog = false }) { Text("Annuler") } },
        )
    }
}

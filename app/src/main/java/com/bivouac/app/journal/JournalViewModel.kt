package com.bivouac.app.journal

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bivouac.app.bilan.JournalOpenRequest
import com.bivouac.app.data.db.DuplicateMatch
import com.bivouac.app.data.db.LoggedTrackEntity
import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import com.bivouac.app.data.db.LoggedTrackRepository
import com.bivouac.app.data.db.PendingPhotoAdd
import com.bivouac.app.data.db.PhotoAddReport
import com.bivouac.app.data.db.PhotoDisplayOrder
import com.bivouac.app.data.db.PreparedImport
import com.bivouac.app.data.db.SystemTag
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.SpeedCalibrationCalculator
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.DayJunctions
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.Segment
import com.bivouac.app.data.photo.MediaStorePhotoQuery
import com.bivouac.app.data.photo.PhotoPickerScope
import com.bivouac.app.data.prefs.MapLayerPreferences
import com.bivouac.app.data.prefs.SettingsPreferences
import com.bivouac.app.ui.map.MapLayer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface JournalUiState {
    data object Overview : JournalUiState
    data object Loading : JournalUiState
    // daySegments (RIC-41) : la trace redécoupée par jour importé, pour la ventilation
    // « Total » + « Jour N » — voir LoggedTrackRepository.openDetail.
    //
    // initialCursorIndex (RIC-19) : non nul quand l'ouverture vient d'un record du Bilan portant
    // sur un jour précis d'un trek multi-jours (trek le plus long, bivouac le plus haut) — voir
    // openTrackById. Réutilise le curseur déjà affiché sur l'ElevationProfile (BIV-52) plutôt que
    // de construire une nouvelle navigation day-level, comme demandé par le ticket.
    data class Detail(
        val entry: LoggedTrackEntity,
        val track: HikeTrack,
        val daySegments: List<Segment>,
        val initialCursorIndex: Int? = null,
    ) : JournalUiState
    // BIV-48: a contemplative overview of several traces at once — entries in the order they
    // should get their (rotating) legend color, each paired with its parsed track.
    data class MultiTrack(val entries: List<Pair<LoggedTrackEntity, HikeTrack>>) : JournalUiState
    data class Error(val message: String) : JournalUiState
}

// RIC-65 écran 3 : le sélecteur a renvoyé plusieurs fichiers, et rien ne permet de deviner s'il
// s'agit d'un trek en plusieurs jours ou de plusieurs sorties indépendantes — l'utilisateur
// tranche explicitement à chaque fois, sans heuristique de date ni de proximité.
data class MultiFileImportChoice(val fileCount: Int)

// Bilan d'un import « sorties séparées » : chaque fichier est traité indépendamment, donc l'échec
// ou le doublon de l'un n'empêche pas les autres d'entrer — d'où ce compte rendu de fin de lot,
// là où un import d'une seule sortie se contente d'ouvrir la trace importée.
// probableDuplicateNames : les traces importées malgré une ressemblance avec une sortie déjà
// présente (cf. processNextSeparateImport). Nommées, et pas seulement comptées : « 3 doublons
// possibles » n'est pas actionnable, l'utilisateur ne saurait pas lesquelles aller vérifier.
data class SeparateImportReport(
    val imported: Int,
    val duplicatesSkipped: Int,
    val failed: Int,
    val probableDuplicateNames: List<String> = emptyList(),
)

// Non nul du premier fichier lu jusqu'à la toute fin de l'opération, calibration comprise. Sert à
// bloquer l'écran : pouvoir ouvrir une autre trace pendant qu'un import écrit en base est un
// risque d'état incohérent, pas seulement un inconfort. La calibration en fait partie parce
// qu'elle est, aujourd'hui, l'étape la plus lente des deux (voir refreshAutoCalibration).
sealed interface ImportProgress {
    // done vaut 0 pour un trek en plusieurs jours : prepareImport lit ses fichiers d'un bloc, il
    // n'y a pas d'étape intermédiaire à montrer. Un lot de sorties séparées, lui, avance fichier
    // par fichier et peut donc compter.
    data class Reading(val done: Int, val total: Int) : ImportProgress

    data object Calibrating : ImportProgress
}

/**
 * Ce que la liste du Journal sait des jours d'une trace. [dates] peut être vide ou incomplète, un
 * GPX pouvant n'avoir aucun horodatage, alors que [dayCount] est toujours juste.
 *
 * D'où [bivouacCount] tiré de [dayCount] et non du nombre de dates : sur une trace importée, une
 * nuit dehors est exactement une coupure entre deux fichiers, connue même sans horodatage.
 */
data class JournalDayInfo(val dayCount: Int, val dates: List<LocalDate>) {
    val bivouacCount: Int get() = (dayCount - 1).coerceAtLeast(0)
}

// RIC-40 : tout ce dont la Planification a besoin pour ouvrir cette trace du Journal comme un
// nouveau plan éditable — construit ici, et pas dans GpxImportViewModel, parce que seul le côté
// Journal connaît les frontières entre jours. La trace du Journal, elle, n'est jamais touchée :
// elle est immuable une fois importée. bivouacPoints porte déjà un point par jonction de fichiers
// (vide pour une trace d'un seul jour) ; l'écran d'arrivée le charge comme n'importe quelle autre
// sélection de bivouacs.
data class DuplicatePlanRequest(
    val track: HikeTrack,
    val bivouacPoints: List<BivouacPoint>,
    val suggestedName: String,
)

/**
 * RIC-149 : où en est l'enregistrement des photos d'une édition.
 *
 * Non nul du clic sur la disquette (ou sur « Enregistrer » du dialogue de sortie) jusqu'à la
 * dernière photo écrite. L'écran s'en sert pour bloquer, voir JournalScreen : tant que ce compte
 * avance, plus rien n'est manipulable et la sortie n'a pas lieu.
 *
 * [total] additionne les suppressions et les ajouts, dans cet ordre : ce sont les deux moitiés du
 * même geste, et les compter séparément donnerait deux barres qui se succèdent pour une seule
 * attente.
 */
data class PhotoCommitProgress(val done: Int, val total: Int)

class JournalViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LoggedTrackRepository(application)
    // Resolver de l'application, et pas celui passé par l'écran : un import peut s'étaler sur
    // plusieurs allers-retours avec l'utilisateur (avertissement de doublon), il ne doit pas
    // garder une référence vers un Context d'Activity pendant ce temps. Les permissions de
    // lecture accordées par le sélecteur valent pour tout le process.
    private val contentResolver = application.contentResolver
    private val mapLayerPreferences = MapLayerPreferences(application)
    private val settingsPreferences = SettingsPreferences(application)

    private val _tracks = MutableStateFlow<List<LoggedTrackEntity>>(emptyList())
    // RIC-65 : liste non filtrée, pour distinguer « aucune trace jamais importée » (écran 1, CTA
    // plein écran) d'un « filtre à zéro résultat » (écran 3, la banque n'est pas vide) — filteredTracks
    // seul ne permet pas cette distinction une fois un filtre actif.
    val tracks: StateFlow<List<LoggedTrackEntity>> = _tracks.asStateFlow()

    // La toute première lecture de la base n'est pas instantanée : tant qu'elle n'a pas abouti,
    // _tracks vaut encore emptyList() par construction, indiscernable d'un journal vraiment vide.
    // Sans ce drapeau, un démarrage à froid sur le Journal (RIC-106) avec une base déjà bien
    // remplie affichait une bouffée de l'écran « aucune rando pour l'instant » avant de basculer
    // sur la liste réelle. Passe à true une fois pour de bon dès la première lecture aboutie ;
    // les rafraîchissements suivants (refresh() est rappelé après le rattrapage ci-dessous, et à
    // chaque import) n'ont plus besoin d'y retoucher.
    private val _tracksLoaded = MutableStateFlow(false)
    val tracksLoaded: StateFlow<Boolean> = _tracksLoaded.asStateFlow()

    // trackId -> its tags, for every track that has at least one — drives both the filter chips
    // (distinct values across all tracks) and which entries a filter selection keeps.
    private val _tagsByTrackId = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val tagsByTrackId: StateFlow<Map<String, List<String>>> = _tagsByTrackId.asStateFlow()

    // Le brut, jamais exposé : une sélection peut porter sur un tag libre qui disparaît ensuite
    // (dernière trace qui le portait démarquée) sans que rien ici ne le sache spontanément — voir
    // selectedFilterTags plus bas, qui recale contre les tags encore réellement présents.
    private val _selectedFilterTags = MutableStateFlow<Set<String>>(emptySet())

    // Recalée contre les tags qui existent encore quelque part (système, toujours offerts, ou
    // portés par au moins une trace) — sans ça, démarquer la dernière trace d'un tag libre filtré
    // faisait disparaître ce tag de la rangée de chips tout en laissant la liste filtrée dessus,
    // donc vide sans aucun chip actif ne l'expliquant. Seul un aller-retour sur l'écran (qui
    // recrée le ViewModel) remettait les pendules à l'heure.
    val selectedFilterTags: StateFlow<Set<String>> = combine(_selectedFilterTags, _tagsByTrackId) { selected, tagsByTrackId ->
        val stillValid = SystemTag.entries.map { it.value }.toSet() + tagsByTrackId.values.flatten()
        selected intersect stillValid
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // trackId -> ce que la liste doit savoir de ses jours, pour distinguer un trek d'une sortie
    // d'un jour : leurs dates, et leur nombre. Les dates manquent tant que le rattrapage n'a pas
    // relevé celles d'une trace, auquel cas la liste se contente de la date de départ, comme
    // avant ; le nombre de jours, lui, est toujours connu.
    private val _dayInfoByTrackId = MutableStateFlow<Map<String, JournalDayInfo>>(emptyMap())
    val dayInfoByTrackId: StateFlow<Map<String, JournalDayInfo>> = _dayInfoByTrackId.asStateFlow()

    // OR semantics: a track matching any one selected tag is kept — narrows what's browsable,
    // doesn't require an exact combination match. Sur selectedFilterTags (déjà recalée) et non sur
    // le brut, pour que ce que la liste montre et ce que les chips montrent racontent la même chose.
    val filteredTracks: StateFlow<List<LoggedTrackEntity>> =
        combine(_tracks, _tagsByTrackId, selectedFilterTags) { tracks, tagsByTrackId, selected ->
            if (selected.isEmpty()) {
                tracks
            } else {
                tracks.filter { entry -> tagsByTrackId[entry.id]?.any { it in selected } == true }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow<JournalUiState>(JournalUiState.Overview)
    val uiState: StateFlow<JournalUiState> = _uiState.asStateFlow()

    // Tags of whichever track is currently open — kept separate from
    // tagsByTrackId's bulk map so editing one track doesn't need re-querying every track's tags.
    private val _currentTags = MutableStateFlow<List<String>>(emptyList())
    val currentTags: StateFlow<List<String>> = _currentTags.asStateFlow()

    // RIC-43 : photos de la trace actuellement ouverte, telles qu'elles sont en base — même raison
    // d'être séparée que currentTags ci-dessus.
    //
    // RIC-149 : ce flux ne bouge plus qu'aux sauvegardes. Ce que l'écran affiche pendant une
    // édition, c'est currentPhotos plus bas, qui y superpose les ajouts en transit et retranche les
    // suppressions en attente.
    private val _currentPhotos = MutableStateFlow<List<LoggedTrackPhotoEntity>>(emptyList())

    /**
     * RIC-149 : les photos choisies pendant l'édition en cours, copiées en zone de transit et pas
     * encore enregistrées. Voir [PendingPhotoAdd].
     */
    private val _pendingPhotoAdds = MutableStateFlow<List<PendingPhotoAdd>>(emptyList())

    /**
     * RIC-149 : les photos que l'utilisateur a demandé à supprimer pendant l'édition en cours.
     * Elles disparaissent immédiatement de l'affichage, mais leur ligne et leur fichier ne sont
     * touchés qu'à la sauvegarde — abandonner l'édition les rend telles qu'elles étaient.
     */
    private val _pendingPhotoDeletions = MutableStateFlow<Set<Long>>(emptySet())

    // RIC-152 : le débrayage est appliqué ici, à la source, et pas seulement en cachant le bandeau
    // côté écran. Tout ce qui montre des photos part de ce flux — bandeau, galerie, marqueurs de
    // la carte, bulle du curseur, visionneuse : le rendre vide quand la fonctionnalité est
    // désactivée est ce qui garantit qu'il n'en reste nulle part, sans avoir à se souvenir de
    // poser une condition à chaque point d'affichage.
    //
    // _currentPhotos, lui, continue de porter la vérité de la base : rien n'est supprimé, et
    // réactiver fait tout revenir sans relire quoi que ce soit.
    //
    // RIC-149 : les ajouts en attente sont rendus comme des photos ordinaires (voir
    // toDisplayEntity).
    //
    // Ils étaient concaténés en fin de liste, dans l'ordre où ils avaient été choisis, en pariant
    // que personne n'attend d'une photo qu'on vient d'ajouter qu'elle se glisse ailleurs sous ses
    // yeux. La recette a tranché l'inverse : une photo du matin ajoutée en fin d'édition se posait
    // en bout de bandeau, puis sautait à sa place chronologique à la sauvegarde — le bandeau
    // racontait deux histoires différentes à quelques secondes d'écart. La liste combinée est donc
    // triée exactement comme la requête DAO l'aurait rendue, voir PhotoDisplayOrder.
    val currentPhotos: StateFlow<List<LoggedTrackPhotoEntity>> =
        combine(
            _currentPhotos,
            _pendingPhotoAdds,
            _pendingPhotoDeletions,
            settingsPreferences.photosEnabled,
        ) { photos, pendingAdds, pendingDeletions, enabled ->
            if (!enabled) {
                emptyList()
            } else {
                val kept = photos.filterNot { it.id in pendingDeletions }
                // Un ajout dont l'empreinte est déjà en base vient d'être enregistré : la liste
                // relue le porte désormais, la version en transit ferait double emploi. C'est ce
                // qui permet à la sauvegarde de publier la nouvelle liste AVANT de vider les
                // attentes, donc sans que les vignettes clignotent — l'ordre inverse les ferait
                // disparaître le temps d'un aller-retour disque.
                val savedHashes = kept.mapTo(mutableSetOf()) { it.contentHash }
                (kept + pendingAdds.filterNot { it.contentHash in savedHashes }.map { it.toDisplayEntity() })
                    .sortedWith(PhotoDisplayOrder)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * RIC-149 : non nul pendant qu'un enregistrement de photos est en cours. Voir
     * [PhotoCommitProgress], et [saveDetails] pour ce qui le pose et le retire.
     */
    private val _photoCommitProgress = MutableStateFlow<PhotoCommitProgress?>(null)
    val photoCommitProgress: StateFlow<PhotoCommitProgress?> = _photoCommitProgress.asStateFlow()

    /**
     * RIC-149 : y a-t-il, côté photos, quelque chose que la disquette enregistrerait ? L'écran
     * l'agrège avec ses propres brouillons (tags, note) pour décider de l'icône de sauvegarde et de
     * l'avertissement de sortie — voir ThreeStopJournalDetail.
     */
    val photosDirty: StateFlow<Boolean> =
        combine(_pendingPhotoAdds, _pendingPhotoDeletions) { adds, deletions ->
            adds.isNotEmpty() || deletions.isNotEmpty()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Lu par l'écran pour masquer le bandeau Photos lui-même, ce qu'une liste vide ne suffirait
    // pas à faire (elle donnerait « Aucune photo pour l'instant » et un bouton d'ajout).
    val photosEnabled: StateFlow<Boolean> = settingsPreferences.photosEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // RIC-43 : les photos de la trace ouverte dont la copie locale a disparu. Dérivé de
    // currentPhotos plutôt que recalculé dans chacune des cinq fonctions qui l'écrivent, et
    // recalculé sur Dispatchers.IO parce que c'est un stat par photo. La carte s'en sert pour ne
    // pas planter de marqueur sur une photo qu'elle ne peut pas montrer, les vignettes pour
    // afficher « photo absente » — la ligne, elle, n'est jamais supprimée (voir
    // LoggedTrackRepository.missingPhotoFileIds).
    val missingPhotoIds: StateFlow<Set<Long>> = _currentPhotos
        .map { photos -> withContext(Dispatchers.IO) { repository.missingPhotoFileIds(photos) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // Retour visuel pendant addPhotos (copie + EXIF + corrélation, potentiellement plusieurs
    // secondes sur un gros lot) — signalé par l'utilisateur en testant sur device, rien ne
    // montrait qu'un ajout était en cours.
    private val _photosLoading = MutableStateFlow(false)
    val photosLoading: StateFlow<Boolean> = _photosLoading.asStateFlow()

    // RIC-43 : erreur d'une action photo, exposée à l'UI sur le modèle de _importError plus bas.
    // Toutes les actions photo touchent des Uri de sélecteur et des fichiers : une Uri révoquée
    // entre la sélection et l'ajout, une permission retirée pendant qu'on lit MediaStore, un
    // fichier disparu — autant de cas réels qui remontaient jusqu'ici hors de viewModelScope,
    // donc en crash de l'app.
    private val _photoError = MutableStateFlow<String?>(null)
    val photoError: StateFlow<String?> = _photoError.asStateFlow()

    // Non nul quand un lot d'ajout s'est terminé avec quelque chose à signaler — voir addPhotos
    // pour ce qui compte comme tel.
    private val _photoAddReport = MutableStateFlow<PhotoAddReport?>(null)
    val photoAddReport: StateFlow<PhotoAddReport?> = _photoAddReport.asStateFlow()

    // Confirmation par dialogue avant suppression d'une photo (décidé en séance de conception) —
    // même mécanique que _deleteTarget pour une trace entière, plus bas.
    private val _photoDeleteTarget = MutableStateFlow<LoggedTrackPhotoEntity?>(null)
    val photoDeleteTarget: StateFlow<LoggedTrackPhotoEntity?> = _photoDeleteTarget.asStateFlow()

    // RIC-43 : non nul pendant que le sélecteur interne est ouvert, porte les candidats trouvés
    // par MediaStorePhotoQuery (éventuellement une liste vide, un vrai résultat « rien trouvé »
    // différent de « pas encore cherché »). Voir openPhotoPicker.
    private val _photoPickerCandidates = MutableStateFlow<List<Uri>?>(null)
    val photoPickerCandidates: StateFlow<List<Uri>?> = _photoPickerCandidates.asStateFlow()
    private val _photoPickerLoading = MutableStateFlow(false)
    val photoPickerLoading: StateFlow<Boolean> = _photoPickerLoading.asStateFlow()
    private var photoPickerJob: Job? = null

    // Périmètre courant du sélecteur. Remis à TRACK_DATES à chaque ouverture plutôt que conservé
    // d'une fois sur l'autre : c'est le mode qui a du sens dans le cas général, et le retrouver
    // ouvert sur toute la galerie parce qu'on y était allé une fois serait une régression
    // silencieuse du confort qu'il apporte.
    private val _photoPickerScope = MutableStateFlow(PhotoPickerScope.TRACK_DATES)
    val photoPickerScope: StateFlow<PhotoPickerScope> = _photoPickerScope.asStateFlow()

    // Shared with Planification — one "which map style" preference for the whole app, not a
    // per-screen setting. Satellite falls back to the free default while non-free features are
    // disabled (BIV-16), same as Planification's own selectedLayer.
    val selectedLayer: StateFlow<MapLayer> = combine(
        mapLayerPreferences.selectedLayer,
        settingsPreferences.nonFreeFeaturesDisabled,
    ) { layer, nonFreeDisabled ->
        if (nonFreeDisabled && layer == MapLayer.SATELLITE) MapLayer.HIKING else layer
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapLayer.HIKING)

    val nonFreeFeaturesDisabled: StateFlow<Boolean> = settingsPreferences.nonFreeFeaturesDisabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // BIV-16 Vitesse personnalisée: whichever calibration is currently active, applied when
    // importing a new hike (existing entries keep the duration they were imported with).
    val activeCalibration: StateFlow<SpeedCalibration> = settingsPreferences.effectiveCalibration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeedCalibration.DEFAULT)

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    // Le match entier et pas seulement la trace ressemblante : l'avertissement ne dit pas la même
    // chose selon qu'il s'agit d'une sortie qui ressemble à une autre ou d'un jour déjà présent.
    private val _duplicateWarning = MutableStateFlow<DuplicateMatch?>(null)
    val duplicateWarning: StateFlow<DuplicateMatch?> = _duplicateWarning.asStateFlow()
    private var pendingImport: PreparedImport? = null

    // RIC-65 écran 3 : non nul tant que l'utilisateur n'a pas tranché entre trek multi-jours et
    // sorties séparées. Aucun fichier n'est lu avant ce choix — « Abandonner » ne peut donc pas
    // laisser d'import partiel derrière lui.
    private val _multiFileImportChoice = MutableStateFlow<MultiFileImportChoice?>(null)
    val multiFileImportChoice: StateFlow<MultiFileImportChoice?> = _multiFileImportChoice.asStateFlow()
    private var pendingChoiceUris: List<Uri> = emptyList()

    private val _separateImportReport = MutableStateFlow<SeparateImportReport?>(null)
    val separateImportReport: StateFlow<SeparateImportReport?> = _separateImportReport.asStateFlow()

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()
    private var separateTotal = 0

    // File d'attente de l'import « sorties séparées » : non nulle du premier fichier au bilan de
    // fin. Elle survit à l'avertissement de doublon, qui la suspend puis la relance.
    private var separateQueue: ArrayDeque<Uri>? = null
    private var separateImported = 0
    private var separateDuplicatesSkipped = 0
    private var separateFailed = 0
    private val separateProbableNames = mutableListOf<String>()

    private val _deleteTarget = MutableStateFlow<LoggedTrackEntity?>(null)
    val deleteTarget: StateFlow<LoggedTrackEntity?> = _deleteTarget.asStateFlow()

    // BIV-47: entered via long-press on a list row; while active, tapping a row toggles it
    // instead of opening it. Cleared automatically once the multi-trace map view is shown.
    private val _selectionModeActive = MutableStateFlow(false)
    val selectionModeActive: StateFlow<Boolean> = _selectionModeActive.asStateFlow()

    private val _selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedTrackIds: StateFlow<Set<String>> = _selectedTrackIds.asStateFlow()

    // BIV-16: same selection mechanics as BIV-47 above, reused rather than duplicated, but
    // entered from Réglages ("Choisir les traces") to pick the Sélection calibration's tracks
    // instead of the map. While true, JournalScreen swaps "Afficher la sélection" for "Confirmer
    // la sélection" and confirming writes into SettingsPreferences instead of opening the map.
    private val _calibrationSelectionActive = MutableStateFlow(false)
    val calibrationSelectionActive: StateFlow<Boolean> = _calibrationSelectionActive.asStateFlow()

    init {
        refresh()
        // RIC-149 : les fichiers de transit qu'un process tué en pleine édition a laissés derrière
        // lui. Ici, à l'ouverture du Journal : c'est le seul endroit d'où un transit peut naître,
        // et à cet instant précis aucune édition n'est en cours dans ce process, donc tout ce qui
        // s'y trouve est par construction périmé.
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // keptPaths et non un balayage aveugle : quitter le Journal puis y revenir aussitôt
                // détruit le ViewModel précédent alors que son enregistrement, lui, se poursuit
                // (NonCancellable, voir saveDetails). Ses fichiers de transit ne sont périmés qu'aux
                // yeux d'un ViewModel qui ne les connaît pas, d'où la liste partagée.
                runCatching { repository.purgePhotoTransit(keptPaths = transitPathsBeingCommitted.toSet()) }
                    .onFailure { Log.w("JournalViewModel", "Nettoyage des photos en transit interrompu", it) }
            }
        }
        // Rattrapage des colonnes dénormalisées, sans effet une fois la banque à jour. Lancé ici
        // plutôt qu'à l'ouverture de la base : c'est le seul endroit où le travail a un scope qui
        // s'annule (quitter le Journal l'interrompt) et où il ne retarde l'affichage de rien.
        viewModelScope.launch {
            val backfilled = withContext(Dispatchers.IO) {
                runCatching { repository.backfillDenormalizedFields() }
                    .onFailure { Log.w("JournalViewModel", "Rattrapage interrompu", it) }
                    .isSuccess
            }
            // Les plages de dates de la liste viennent des colonnes que le rattrapage remplit :
            // sans ce second passage, elles n'apparaîtraient qu'au prochain lancement.
            if (backfilled) refresh()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _tracks.value = repository.list()
                _tagsByTrackId.value = repository.tagsByTrackId()
                val zone = ZoneId.systemDefault()
                _dayInfoByTrackId.value = repository.daySummariesByTrackId()
                    .mapValues { (_, summary) ->
                        JournalDayInfo(
                            dayCount = summary.dayCount,
                            dates = summary.startMillis.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() },
                        )
                    }
            }
            _tracksLoaded.value = true
        }
    }

    fun setSelectedLayer(layer: MapLayer) {
        viewModelScope.launch { mapLayerPreferences.setSelectedLayer(layer) }
    }

    fun toggleFilterTag(tag: String) {
        _selectedFilterTags.value = _selectedFilterTags.value.let { if (tag in it) it - tag else it + tag }
    }

    fun openTrack(entry: LoggedTrackEntity) = openTrackInternal(entry, dayIndex = null)

    /**
     * RIC-19 §6 : point d'entrée depuis un record du Bilan (autre écran, autre ViewModel — même
     * boîte aux lettres que [onDuplicateToPlanification][com.bivouac.app.journal.DuplicatePlanRequest]
     * portée par MainActivity, voir BilanScreen/MainActivity). [dayIndex] non nul positionne le
     * curseur au premier point du jour correspondant une fois la trace chargée ; laissé nul pour
     * les records mono-jour, qui ouvrent la trace sans marquage particulier (RIC-19 §6).
     */
    fun openTrackById(id: String, dayIndex: Int? = null) {
        val entry = _tracks.value.find { it.id == id } ?: run {
            Log.w("JournalViewModel", "openTrackById: trace $id introuvable (supprimée depuis ?)")
            _uiState.value = JournalUiState.Error("Trace introuvable.")
            return
        }
        openTrackInternal(entry, dayIndex)
    }

    private fun openTrackInternal(entry: LoggedTrackEntity, dayIndex: Int?) {
        // RIC-149 : ouvrir une autre trace abandonne ce qui n'a pas été enregistré sur la
        // précédente — l'écran ne laisse pas partir une édition sale sans le demander (voir
        // ThreeStopJournalDetail.requestExit), mais l'état des photos ne doit dépendre d'aucune
        // promesse tenue ailleurs.
        discardPhotoEdits()
        _uiState.value = JournalUiState.Loading
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.openDetail(entry.id) to repository.listPhotos(entry.id)
                }
            }.onSuccess { (detail, photos) ->
                _uiState.value = if (detail != null) {
                    _currentTags.value = _tagsByTrackId.value[entry.id].orEmpty()
                    _currentPhotos.value = photos
                    // Point du premier point du jour demandé : somme des tailles des jours qui le
                    // précèdent, la trace concaténée listant les jours dans cet ordre (voir
                    // LoggedTrackRepository.openDetail). coerceIn par prudence si le jour demandé
                    // n'existe plus (trace modifiée entre le calcul du record et le clic).
                    val cursor = dayIndex?.let { idx ->
                        detail.daySegments.take(idx).sumOf { it.points.size }
                            .coerceIn(0, (detail.track.points.size - 1).coerceAtLeast(0))
                    }
                    JournalUiState.Detail(entry, detail.track, detail.daySegments, cursor)
                } else {
                    JournalUiState.Error("Trace introuvable.")
                }
            }.onFailure {
                Log.e("JournalViewModel", "Échec de l'ouverture d'une trace du journal", it)
                _uiState.value = JournalUiState.Error("Trace incorrecte ou fichier illisible.")
            }
        }
    }

    /**
     * RIC-40 : null quand il n'y a rien à dupliquer (on n'est pas sur la vue détail). Les points
     * de bivouac tombent aux jonctions entre jours, voir [DayJunctions] — liste vide pour une
     * trace d'un seul jour, que la Planification ouvre alors comme n'importe quelle trace sans
     * bivouac. Rien n'est écrit ici : la trace du Journal reste telle qu'elle a été importée.
     */
    fun buildDuplicateForPlanification(): DuplicatePlanRequest? {
        val state = _uiState.value as? JournalUiState.Detail ?: return null
        val junctions = DayJunctions.bivouacTrackPointIndices(state.daySegments.map { it.points.size })
        return DuplicatePlanRequest(
            track = state.track,
            bivouacPoints = junctions.map { BivouacPoint(id = UUID.randomUUID().toString(), trackPointIndex = it) },
            suggestedName = "Copie de ${state.entry.name}",
        )
    }

    fun closeTrack() {
        // RIC-149 : même filet qu'à l'ouverture d'une autre trace — quitter la vue détail ne peut
        // pas laisser un transit vivant derrière lui.
        discardPhotoEdits()
        _uiState.value = JournalUiState.Overview
    }

    fun enterSelectionMode(initialId: String) {
        _selectionModeActive.value = true
        _selectedTrackIds.value = setOf(initialId)
    }

    fun exitSelectionMode() {
        _selectionModeActive.value = false
        _calibrationSelectionActive.value = false
        _selectedTrackIds.value = emptySet()
    }

    // Pre-checks whatever is already saved as the Sélection calibration's tracks, so reopening
    // this flow shows the current choice rather than starting from empty.
    fun enterCalibrationSelectionMode() {
        _calibrationSelectionActive.value = true
        _selectionModeActive.value = true
        viewModelScope.launch { _selectedTrackIds.value = settingsPreferences.selectedTrackIds.first() }
    }

    // The caller (JournalScreen) navigates back to Réglages immediately after calling this —
    // that pops this screen's NavBackStackEntry, which clears this ViewModel and cancels
    // viewModelScope. Without NonCancellable, that race routinely won the race against the write
    // below (calibrationSamples() parses GPX, never instant), so the confirmed selection just
    // never made it to disk — this is why "Confirmer la sélection (N)" wasn't reliably updating
    // Réglages' count. NonCancellable keeps this specific write alive past that cancellation.
    // JournalListContent already disables the confirm button below this — guarded again here in
    // case that ever gets bypassed, since a 1-trace Sélection can't be told apart from a genuine
    // 2+ fit (see SpeedCalibrationCalculator's MIN_TRACKS_FOR_CALIBRATION).
    fun confirmCalibrationSelection() {
        val ids = _selectedTrackIds.value
        if (ids.size < SpeedCalibrationCalculator.MIN_TRACKS_FOR_CALIBRATION) return
        exitSelectionMode()
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                val input = repository.calibrationSamples(ids)
                val result = SpeedCalibrationCalculator.compute(input.aggregate, input.fallbackSamples)
                settingsPreferences.setSelectionCalibration(result?.calibration ?: SpeedCalibration.DEFAULT, ids)
            }
        }
    }

    fun toggleTrackSelection(id: String) {
        _selectedTrackIds.value = _selectedTrackIds.value.let { if (id in it) it - id else it + id }
    }

    // Standard "select all if not all selected yet, else clear" checkbox behavior — no partial
    // (indeterminate) visual state, just a plain toggle.
    fun toggleYearSelection(ids: List<String>) {
        _selectedTrackIds.value = if (_selectedTrackIds.value.containsAll(ids)) {
            _selectedTrackIds.value - ids.toSet()
        } else {
            _selectedTrackIds.value + ids
        }
    }

    // Nothing selected → everything currently listed (already tag-filtered if a filter is
    // active); a selection → just that. Either way, selection mode resets once the map opens.
    fun showOnMap() {
        val idsToShow = _selectedTrackIds.value.ifEmpty { filteredTracks.value.map { it.id }.toSet() }
        val entriesToShow = _tracks.value.filter { it.id in idsToShow }
        exitSelectionMode()
        _uiState.value = JournalUiState.Loading
        viewModelScope.launch {
            // RIC-127 : même filet que openTrack — un GPX illisible parmi la sélection ne doit
            // pas crasher l'app. Tout-ou-rien pour l'instant (comme openTrack), pas un skip
            // silencieux des traces en échec : à revoir si ça s'avère trop strict à l'usage.
            runCatching {
                withContext(Dispatchers.IO) {
                    entriesToShow.mapNotNull { entry -> repository.open(entry.id)?.let { entry to it } }
                }
            }.onSuccess { loaded ->
                _uiState.value = JournalUiState.MultiTrack(loaded)
            }.onFailure {
                Log.e("JournalViewModel", "Échec de l'affichage multi-traces sur la carte", it)
                _uiState.value = JournalUiState.Error("Trace incorrecte ou fichier illisible.")
            }
        }
    }

    fun closeMultiTrack() {
        _uiState.value = JournalUiState.Overview
    }

    fun renameCurrentTrack(name: String) {
        val entry = currentEntry() ?: return
        val trimmed = name.trim().ifBlank { return }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.rename(entry.id, trimmed) }
            _tracks.value = _tracks.value.map { if (it.id == entry.id) it.copy(name = trimmed) else it }
            val state = _uiState.value as? JournalUiState.Detail
            if (state != null && state.entry.id == entry.id) {
                _uiState.value = JournalUiState.Detail(state.entry.copy(name = trimmed), state.track, state.daySegments)
            }
        }
    }

    /**
     * Commits a whole edit-mode draft at once (tags + note together) — nothing is written while
     * the user is merely toggling chips or typing; editing this "détails" data isn't a frequent
     * operation, so it gets an explicit save/discard step rather than writing through on every tap
     * like the rest of the app does for more routine actions.
     *
     * RIC-149 : les photos ont rejoint ce même geste. Suppressions d'abord, ajouts ensuite : une
     * photo supprimée puis réajoutée dans la même édition (ce que la déduplication autorise
     * justement, voir addPhotos) doit finir présente, pas écartée en doublon par la ligne qu'on
     * s'apprête à effacer.
     *
     * [onFinished] est appelé quand tout est écrit, jamais avant, et c'est ce qui rend son sens au
     * bouton « Enregistrer » du dialogue de sortie : c'est lui qui porte la fermeture de l'écran.
     * Signalé en recette, ce chemin-là perdait une partie des photos ajoutées alors que la
     * disquette les enregistrait toutes. La cause tenait à l'enchaînement, sur le même clic, de
     * cette sauvegarde puis de closeTrack : celui-ci appelle discardPhotoEdits, dont la boucle de
     * suppression (un unlink par photo) rattrapait la boucle d'enregistrement, plus lente (un
     * déplacement plus un insert par photo), et effaçait les fichiers de transit qu'il restait à
     * enregistrer. La disquette ne quittant pas l'écran ne déclenchait rien de tout ça : toute
     * l'asymétrie était là. Voir JournalPhotoCommitRaceTest.
     *
     * Trois verrous plutôt qu'un, parce que perdre des photos est irréversible :
     * - la sortie attend [onFinished], donc la course n'a plus lieu d'être ;
     * - les chemins en cours d'écriture sont déclarés dans [transitPathsBeingCommitted], que
     *   [discardPhotoEdits], [onCleared] et le balayage de démarrage épargnent ;
     * - l'écriture est NonCancellable, donc la mort du ViewModel pendant l'enregistrement (retour
     *   vers un autre onglet) ne la coupe pas en deux.
     */
    fun saveDetails(tags: Set<String>, note: String, onFinished: () -> Unit = {}) {
        val entry = currentEntry() ?: return onFinished()
        val previousTags = _currentTags.value.toSet()
        val deletions = _pendingPhotoDeletions.value
        val additions = _pendingPhotoAdds.value
        val photoWork = deletions.size + additions.size
        val inFlightPaths = additions.map { it.transitPath }
        // Posés avant le launch, donc avant que quoi que ce soit d'autre ne puisse s'exécuter : la
        // protection ne doit pas dépendre du moment où la coroutine sera ordonnancée, c'est
        // exactement ce qui manquait.
        transitPathsBeingCommitted += inFlightPaths
        if (photoWork > 0) _photoCommitProgress.value = PhotoCommitProgress(done = 0, total = photoWork)
        viewModelScope.launch {
            val photoFailures = withContext(NonCancellable + Dispatchers.IO) {
                (tags - previousTags).forEach { repository.addTag(entry.id, it) }
                (previousTags - tags).forEach { repository.removeTag(entry.id, it) }
                repository.updateNote(entry.id, note)
                var failures = 0
                if (photoWork > 0) {
                    var done = 0
                    val advance = { done++; _photoCommitProgress.value = PhotoCommitProgress(done, photoWork) }
                    runCatching {
                        repository.deletePhotos(deletions, onProgress = advance)
                        failures = repository.commitPendingPhotos(entry.id, additions, onProgress = advance)
                        _currentPhotos.value = repository.listPhotos(entry.id)
                    }.onFailure {
                        Log.e("JournalViewModel", "Échec de l'enregistrement des photos", it)
                        failures = additions.size
                    }
                }
                failures
            }
            transitPathsBeingCommitted -= inFlightPaths.toSet()
            _photoCommitProgress.value = null
            // Vidées après l'écriture, jamais avant : les vignettes en transit tiennent l'affichage
            // jusqu'à ce que la liste relue prenne le relais, sinon elles disparaîtraient le temps
            // d'un aller-retour disque. Le doublon que ce recouvrement pourrait produire est écarté
            // par empreinte dans currentPhotos.
            _pendingPhotoDeletions.value = emptySet()
            _pendingPhotoAdds.value = emptyList()
            if (photoFailures > 0) {
                _photoError.value = if (photoFailures == 1) {
                    "Une photo n'a pas pu être enregistrée."
                } else {
                    "$photoFailures photos n'ont pas pu être enregistrées."
                }
            }
            _currentTags.value = tags.toList()
            _tagsByTrackId.value = _tagsByTrackId.value + (entry.id to tags.toList())
            refreshCurrentEntry(entry.id, note = note)
            onFinished()
        }
    }

    private fun currentEntry(): LoggedTrackEntity? = when (val state = _uiState.value) {
        is JournalUiState.Detail -> state.entry
        else -> null
    }

    /**
     * RIC-149 : un ajout en attente, vu comme une photo ordinaire.
     *
     * C'est ce qui permet au bandeau, à la galerie, à la visionneuse, aux marqueurs de la carte et à
     * la bulle du curseur de le montrer sans rien savoir du transit : le chemin porte son propre
     * marqueur et LoggedTrackPhotoStore.resolve sait où aller le chercher.
     *
     * addedAtMillis vaut l'instant de la copie en transit ([PendingPhotoAdd.stagedAtMillis]) : cette
     * valeur ne sert qu'au tri, et un instant figé une fois pour toutes est ce qui donne au bandeau
     * un ordre qui ne bouge pas d'une recomposition à l'autre. Le vrai addedAtMillis sera posé à
     * l'insert, plus tard mais dans le même ordre.
     */
    private fun PendingPhotoAdd.toDisplayEntity(): LoggedTrackPhotoEntity = LoggedTrackPhotoEntity(
        id = displayId,
        trackId = currentEntry()?.id.orEmpty(),
        filePath = transitPath,
        addedAtMillis = stagedAtMillis,
        takenAtMillis = takenAtMillis,
        latitude = latitude,
        longitude = longitude,
        positionPointIndex = positionPointIndex,
        positionApproximate = positionApproximate,
        takenAtZoneCertain = takenAtZoneCertain,
        contentHash = contentHash,
        sourceDisplayName = source.displayName,
        sourceRelativePath = source.relativePath,
        sourceDateTakenMillis = source.dateTakenMillis,
    )

    private fun refreshCurrentEntry(id: String, note: String) {
        _tracks.value = _tracks.value.map { if (it.id == id) it.copy(note = note) else it }
        val state = _uiState.value as? JournalUiState.Detail
        if (state != null && state.entry.id == id) {
            _uiState.value = JournalUiState.Detail(state.entry.copy(note = note), state.track, state.daySegments)
        }
    }

    fun requestDelete() {
        _deleteTarget.value = currentEntry()
    }

    fun dismissDeleteConfirmation() {
        _deleteTarget.value = null
    }

    fun confirmDelete() {
        val target = _deleteTarget.value ?: return
        // La trace part avec ses photos (CASCADE + fichiers, voir LoggedTrackRepository.delete) :
        // ce qui était en attente d'enregistrement sur elle n'a plus de destination.
        discardPhotoEdits()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.delete(target.id) }
            _deleteTarget.value = null
            _uiState.value = JournalUiState.Overview
            refresh()
        }
    }

    /**
     * RIC-43/149 : sélection faite dans le sélecteur interne, une ou plusieurs photos à la fois.
     * Rien n'entre en base ici : les photos rejoignent la zone de transit et s'affichent dans le
     * bandeau comme les autres, en attente de la disquette.
     *
     * Le bilan de fin de lot n'est affiché que s'il a quelque chose à dire (un doublon écarté, un
     * échec) : quand tout est entré, les vignettes qui apparaissent le disent déjà, et un dialogue
     * à acquitter après chaque ajout serait une friction pour rien. C'est la différence avec le
     * bilan d'import de sorties séparées, qui est toujours montré parce que son résultat n'est
     * visible nulle part ailleurs.
     *
     * Son moment ne bouge pas — il reste celui de la sélection, seul instant où l'on sait qu'une
     * photo était un doublon ou illisible — mais son libellé, si : « ajoutées » sous-entendrait
     * enregistrées. Voir formatPhotoAddReport côté écran.
     */
    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val entry = currentEntry() ?: return
        viewModelScope.launch {
            _photosLoading.value = true
            try {
                val batch = runCatching {
                    withContext(Dispatchers.IO) {
                        repository.stagePhotosFromPicker(
                            trackId = entry.id,
                            resolver = contentResolver,
                            uris = uris,
                            // Le contrôle d'empreinte couvre le persisté ET le transit : deux
                            // passages successifs dans le sélecteur sur la même photo, sans
                            // sauvegarde entre les deux, doivent se comporter comme deux passages
                            // séparés par une sauvegarde.
                            alreadyStagedHashes = _pendingPhotoAdds.value.mapTo(mutableSetOf()) { it.contentHash },
                            // À l'inverse, une photo marquée pour suppression ne sera plus là après
                            // la sauvegarde : la refuser en doublon enfermerait l'utilisateur dans
                            // un état où il ne peut ni la garder ni la reprendre.
                            ignoredHashes = hashesOfPendingDeletions(),
                        )
                    }
                }.onSuccess { batch ->
                    _pendingPhotoAdds.value = _pendingPhotoAdds.value + batch.staged
                }.onFailure {
                    Log.e("JournalViewModel", "Échec de l'ajout de photos", it)
                    _photoError.value = "Impossible d'ajouter ces photos. Réessaie depuis la galerie."
                }.getOrNull()
                val report = batch?.report
                if (report != null && (report.duplicatesSkipped > 0 || report.failed > 0)) {
                    _photoAddReport.value = report
                }
            } finally {
                _photosLoading.value = false
            }
        }
    }

    private fun hashesOfPendingDeletions(): Set<String> {
        val deleted = _pendingPhotoDeletions.value
        return _currentPhotos.value.filter { it.id in deleted }.mapTo(mutableSetOf()) { it.contentHash }
    }

    /**
     * RIC-149 : l'abandon d'une édition, côté photos — les ajouts en transit sont effacés du disque,
     * les suppressions en attente sont simplement oubliées, donc les photos concernées reviennent.
     *
     * Appelé aussi bien quand l'utilisateur choisit « Ne pas enregistrer » qu'à la fermeture de
     * l'écran ou à l'ouverture d'une autre trace : il n'y a pas d'état d'édition photo qui survive à
     * la sortie du mode édition.
     */
    fun discardPhotoEdits() {
        // Les fichiers qu'un enregistrement est en train de déplacer sont épargnés : ils ne
        // sont plus à l'abandon, ils sont en route. C'est le filet qui manquait quand la sortie
        // d'écran suivait immédiatement la sauvegarde, voir saveDetails.
        val discarded = _pendingPhotoAdds.value.filterNot { it.transitPath in transitPathsBeingCommitted }
        _pendingPhotoAdds.value = emptyList()
        _pendingPhotoDeletions.value = emptySet()
        if (discarded.isEmpty()) return
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) { repository.discardPendingPhotos(discarded) }
        }
    }

    /**
     * RIC-149 : le ViewModel est détruit (retour vers un autre onglet, mort du process) alors qu'une
     * édition tenait des transits. viewModelScope est déjà annulé à ce moment-là, donc la
     * suppression se fait ici même, en synchrone : c'est une poignée d'appels unlink, et le seul
     * moyen de ne pas dépendre d'une coroutine qui ne partira jamais. Ce que ce filet manque (mort
     * brutale du process) est rattrapé au prochain démarrage, voir purgePhotoTransit.
     */
    override fun onCleared() {
        // Même exception que discardPhotoEdits : un enregistrement lancé juste avant la destruction
        // survit à celle-ci (il est NonCancellable), ses fichiers ne sont donc pas des orphelins.
        val pending = _pendingPhotoAdds.value.filterNot { it.transitPath in transitPathsBeingCommitted }
        if (pending.isNotEmpty()) {
            runCatching { repository.discardPendingPhotos(pending) }
                .onFailure { Log.w("JournalViewModel", "Photos en transit non nettoyées", it) }
        }
        super.onCleared()
    }

    fun dismissPhotoError() {
        _photoError.value = null
    }

    fun dismissPhotoAddReport() {
        _photoAddReport.value = null
    }

    /**
     * RIC-43 : ouvre le sélecteur interne — appelé seulement après vérification de la permission
     * galerie côté écran (voir PhotoLibraryPermission), jamais avant, et jamais du tout quand la
     * fonctionnalité photos est désactivée.
     */
    fun openPhotoPicker() {
        _photoPickerScope.value = PhotoPickerScope.TRACK_DATES
        queryPhotoCandidates()
    }

    /** Changement de périmètre depuis le sélecteur ouvert : même requête, autre portée. */
    fun setPhotoPickerScope(scope: PhotoPickerScope) {
        if (_photoPickerScope.value == scope) return
        _photoPickerScope.value = scope
        queryPhotoCandidates()
    }

    /**
     * Relance la requête sans changer de périmètre : ce dont le sélecteur a besoin au retour du
     * dialogue système de re-sélection (accès partiel Android 14+), où la liste de photos visibles
     * par l'app vient de changer sous ses pieds.
     */
    fun reloadPhotoPicker() {
        queryPhotoCandidates()
    }

    /**
     * La plage TRACK_DATES vient des horodatages réels de la trace ouverte (premier/dernier point),
     * pas de startedAt seul : plus précis sur une sortie multi-jours, et MediaStorePhotoQuery
     * ajoute déjà sa propre marge. Une trace sans horodatage rend une liste vide, résultat honnête
     * que le sélecteur sait présenter en proposant d'élargir à toute la galerie.
     */
    private fun queryPhotoCandidates() {
        val scope = _photoPickerScope.value
        val track = (_uiState.value as? JournalUiState.Detail)?.track ?: return
        val start = track.points.firstOrNull()?.time
        val end = track.points.lastOrNull()?.time
        if (scope == PhotoPickerScope.TRACK_DATES && (start == null || end == null)) {
            _photoPickerCandidates.value = emptyList()
            _photoPickerLoading.value = false
            return
        }
        // Avant le launch, pas dedans : c'est ce drapeau qui ouvre le dialogue (voir JournalScreen),
        // et l'ouvrir seulement une fois la coroutine ordonnancée, c'est ne rien montrer pendant
        // la requête MediaStore — soit exactement la seconde ou deux qu'il s'agit de couvrir. Le
        // CircularProgressIndicator du dialogue n'était jamais atteint pour cette raison.
        _photoPickerLoading.value = true
        photoPickerJob?.cancel()
        photoPickerJob = viewModelScope.launch {
            try {
                val candidates = withContext(Dispatchers.IO) {
                    when (scope) {
                        PhotoPickerScope.TRACK_DATES ->
                            MediaStorePhotoQuery.findInRange(contentResolver, start!!.toEpochMilli(), end!!.toEpochMilli())
                        PhotoPickerScope.WHOLE_GALLERY -> MediaStorePhotoQuery.findAll(contentResolver)
                    }
                }
                _photoPickerCandidates.value = candidates
            } catch (e: CancellationException) {
                // L'utilisateur a fermé le dialogue pendant la requête : rien à signaler, et
                // surtout pas de résultat à publier. Relancée telle quelle pour ne pas transformer
                // une annulation en succès aux yeux de la coroutine parente.
                throw e
            } catch (e: Exception) {
                // SecurityException réelle : la permission galerie peut avoir été retirée entre la
                // vérification faite par l'écran et cette requête (retrait manuel, ou révocation
                // automatique d'une app inutilisée).
                Log.e("JournalViewModel", "Échec de la recherche de photos", e)
                _photoPickerCandidates.value = null
                _photoError.value = "Impossible de parcourir la galerie. " +
                    "Vérifie l'autorisation d'accès aux photos dans les réglages d'Android."
            } finally {
                _photoPickerLoading.value = false
            }
        }
    }

    fun closePhotoPicker() {
        // La requête est annulée avec le dialogue : sans ça, une recherche encore en cours
        // republiait ses candidats à son terme et rouvrait le dialogue tout seul.
        photoPickerJob?.cancel()
        photoPickerJob = null
        _photoPickerCandidates.value = null
        _photoPickerLoading.value = false
    }

    fun confirmPhotoSelection(uris: List<Uri>) {
        // Fermeture complète et non simple mise à null des candidats : depuis que le périmètre est
        // changeable sans quitter le sélecteur, une requête peut être encore en cours au moment où
        // l'utilisateur valide (il valide ce qu'il avait déjà coché, la nouvelle liste n'étant pas
        // arrivée). Sans annulation, cette requête publiait ses résultats à son terme et rouvrait
        // le sélecteur par-dessus l'ajout qui venait d'être lancé.
        closePhotoPicker()
        addPhotos(uris)
    }

    fun requestDeletePhoto(photo: LoggedTrackPhotoEntity) {
        _photoDeleteTarget.value = photo
    }

    fun dismissPhotoDeleteConfirmation() {
        _photoDeleteTarget.value = null
    }

    /**
     * RIC-149 : la suppression est enregistrée comme une intention, pas exécutée. La photo quitte
     * l'affichage tout de suite (c'est ce que l'utilisateur demande), mais sa ligne et son fichier
     * ne bougent qu'à la sauvegarde — et reviennent intacts si l'édition est abandonnée.
     *
     * Une photo encore en transit (ajoutée dans la même édition, jamais enregistrée) n'a rien à
     * marquer : elle est simplement retirée du lot en attente, et ses octets partent avec elle.
     */
    fun confirmDeletePhoto() {
        val target = _photoDeleteTarget.value ?: return
        _photoDeleteTarget.value = null
        val pendingAdd = _pendingPhotoAdds.value.find { it.displayId == target.id }
        if (pendingAdd != null) {
            _pendingPhotoAdds.value = _pendingPhotoAdds.value - pendingAdd
            viewModelScope.launch {
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.discardPendingPhotos(listOf(pendingAdd))
                }
            }
            return
        }
        _pendingPhotoDeletions.value = _pendingPhotoDeletions.value + target.id
    }

    /**
     * Point d'entrée unique de l'import depuis le sélecteur de fichiers. Un seul fichier passe
     * directement (il n'y a rien à trancher) ; plusieurs déclenchent le choix de RIC-65 écran 3,
     * avant toute lecture de fichier.
     */
    fun importTracks(uris: List<Uri>) {
        when {
            uris.isEmpty() -> return
            uris.size == 1 -> importAsSingleTrack(uris)
            else -> {
                pendingChoiceUris = uris
                _multiFileImportChoice.value = MultiFileImportChoice(uris.size)
            }
        }
    }

    /** « Un seul trek en plusieurs jours » : un fichier = un jour d'une même entrée du Journal. */
    fun chooseMultiDayImport() {
        val uris = consumeImportChoice() ?: return
        importAsSingleTrack(uris)
    }

    /** « Sorties séparées » : N entrées indépendantes du Journal, traitées une par une. */
    fun chooseSeparateImports() {
        val uris = consumeImportChoice() ?: return
        separateQueue = ArrayDeque(uris)
        separateTotal = uris.size
        separateImported = 0
        separateDuplicatesSkipped = 0
        separateFailed = 0
        separateProbableNames.clear()
        processNextSeparateImport()
    }

    /** « Abandonner » : rien n'a encore été lu ni écrit, il n'y a donc rien à défaire. */
    fun cancelMultiFileImport() {
        consumeImportChoice()
    }

    private fun consumeImportChoice(): List<Uri>? {
        val uris = pendingChoiceUris.takeIf { it.isNotEmpty() }
        pendingChoiceUris = emptyList()
        _multiFileImportChoice.value = null
        return uris
    }

    /**
     * RIC-41 : plusieurs fichiers ici forment une seule sortie de plusieurs jours (un fichier =
     * un jour) — c'est [LoggedTrackRepository.prepareImport] qui ordonne les jours et agrège les
     * statistiques.
     *
     * Import tout-ou-rien : un fichier illisible fait échouer le lot entier plutôt que de laisser
     * une sortie multi-jours amputée d'un jour, plus trompeuse qu'une absence d'import. C'est
     * exactement l'inverse du mode « sorties séparées » ci-dessous, où l'indépendance des
     * fichiers est justement ce qui a été demandé.
     */
    private fun importAsSingleTrack(uris: List<Uri>) {
        _importProgress.value = ImportProgress.Reading(done = 0, total = uris.size)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val prepared = repository.prepareImport(contentResolver, uris, activeCalibration.value)
                    prepared to repository.findDuplicate(prepared)
                }
            }.onSuccess { (prepared, duplicate) ->
                when (duplicate) {
                    is DuplicateMatch.Exact ->
                        _importError.value = "« ${duplicate.existing.name} » est déjà dans le journal."
                    is DuplicateMatch.Probable, is DuplicateMatch.SharedDay -> {
                        pendingImport = prepared
                        _duplicateWarning.value = duplicate
                    }
                    null -> commit(prepared, openAfterCommit = true)
                }
            }.onFailure {
                Log.e("JournalViewModel", "Échec de l'import GPX (Journal)", it)
                _importError.value = "Trace incorrecte ou fichier illisible."
            }
            // Après le commit et sa calibration, donc après l'opération entière : ce qui suit
            // (avertissement de doublon, erreur, vue détail) est de nouveau manipulable.
            _importProgress.value = null
        }
    }

    // Un fichier à la fois, mais sans jamais rendre la main : rien dans ce mode n'interrompt le lot
    // pour poser une question. Le suivant n'est lu qu'une fois le précédent écrit, pour ne pas
    // paralléliser des écritures en base sur un lot de plusieurs dizaines de fichiers.
    private fun processNextSeparateImport() {
        val queue = separateQueue ?: return
        val uri = queue.removeFirstOrNull() ?: return finishSeparateImports()
        _importProgress.value = ImportProgress.Reading(done = separateTotal - queue.size - 1, total = separateTotal)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val prepared = repository.prepareImport(contentResolver, listOf(uri), activeCalibration.value)
                    prepared to repository.findDuplicate(prepared)
                }
            }.onSuccess { (prepared, duplicate) ->
                when (duplicate) {
                    // Écarté sans dialogue, contrairement à l'import d'une sortie seule : c'est le
                    // bilan de fin de lot qui le rapporte, sans interrompre les fichiers suivants.
                    is DuplicateMatch.Exact -> {
                        separateDuplicatesSkipped++
                        processNextSeparateImport()
                    }
                    // Importé quand même, et signalé dans le bilan de fin plutôt que par une
                    // question bloquante : sur un import de masse, une erreur visible et
                    // réversible (un doublon apparaît dans la liste, se supprime en deux taps)
                    // vaut mieux qu'un oubli silencieux, et se faire arrêter plusieurs fois au
                    // milieu de 66 fichiers est exactement la friction que ce mode doit éviter.
                    // La politique devient au passage cohérente entre les deux niveaux de
                    // détection : doublon certain écarté en silence, doublon probable importé
                    // puis signalé.
                    is DuplicateMatch.Probable, is DuplicateMatch.SharedDay -> {
                        commit(prepared, openAfterCommit = false, refreshCalibration = false)
                        separateImported++
                        separateProbableNames += prepared.entity.name
                        processNextSeparateImport()
                    }
                    null -> {
                        commit(prepared, openAfterCommit = false, refreshCalibration = false)
                        separateImported++
                        processNextSeparateImport()
                    }
                }
            }.onFailure {
                Log.e("JournalViewModel", "Échec de l'import GPX (Journal, sorties séparées)", it)
                separateFailed++
                processNextSeparateImport()
            }
        }
    }

    private fun finishSeparateImports() {
        separateQueue = null
        val imported = separateImported
        val report = SeparateImportReport(
            imported = imported,
            duplicatesSkipped = separateDuplicatesSkipped,
            failed = separateFailed,
            probableDuplicateNames = separateProbableNames.toList(),
        )
        // Une seule fois pour tout le lot, et pas après chaque fichier : recalculer la calibration
        // Auto reparse le GPX de tout le Journal, donc la faire N fois d'affilée coûte N passes
        // complètes pour un résultat que seule la dernière détermine.
        //
        // Le bilan n'est posé qu'après : sur une banque un peu fournie cette passe se compte en
        // secondes, et afficher « Import terminé » par-dessus un traitement encore en cours
        // reviendrait à rendre l'écran manipulable au pire moment.
        viewModelScope.launch {
            if (imported > 0) {
                _importProgress.value = ImportProgress.Calibrating
                withContext(Dispatchers.IO) { refreshAutoCalibration() }
            }
            _importProgress.value = null
            _separateImportReport.value = report
        }
    }

    fun dismissSeparateImportReport() {
        _separateImportReport.value = null
    }

    // L'avertissement de doublon probable ne concerne plus que l'import d'une sortie seule : une
    // question pour un fichier ne coûte rien, et l'utilisateur a le contexte pour y répondre. Un
    // lot de sorties séparées, lui, ne pose jamais la question (cf. processNextSeparateImport).
    fun confirmImportAnyway() {
        val prepared = pendingImport ?: return
        pendingImport = null
        _duplicateWarning.value = null
        viewModelScope.launch {
            commit(prepared, openAfterCommit = true)
            _importProgress.value = null
        }
    }

    fun dismissDuplicateWarning() {
        pendingImport = null
        _duplicateWarning.value = null
    }

    fun dismissImportError() {
        _importError.value = null
    }

    private suspend fun commit(
        prepared: PreparedImport,
        openAfterCommit: Boolean,
        refreshCalibration: Boolean = true,
    ) {
        withContext(Dispatchers.IO) { repository.commitImport(prepared) }
        if (refreshCalibration) {
            _importProgress.value = ImportProgress.Calibrating
            withContext(Dispatchers.IO) { refreshAutoCalibration() }
        }
        refresh()
        // Mirrors Planification's "open a track" behavior: a just-imported trace should land
        // straight on its detail view, not merely appear in the list waiting to be tapped. Un lot
        // de sorties séparées y échappe : en ouvrir une seule, arbitrairement, ne dirait rien du
        // reste du lot — c'est le bilan de fin qui tient ce rôle.
        if (openAfterCommit) openTrack(prepared.entity)
    }

    // BIV-16 Auto mode: recomputed on every import regardless of which mode is currently active,
    // so switching to Auto later never shows a stale value from before the last import.
    private suspend fun refreshAutoCalibration() {
        val input = repository.calibrationSamples()
        val result = SpeedCalibrationCalculator.compute(input.aggregate, input.fallbackSamples) ?: return
        settingsPreferences.setAutoCalibration(result.calibration)
    }

    private companion object {
        /**
         * RIC-149 : les chemins de transit qu'un enregistrement est en train de déplacer.
         *
         * Partagé entre instances, et pas porté par le ViewModel : un enregistrement survit à la
         * destruction du ViewModel qui l'a lancé (il est NonCancellable), donc le suivant doit
         * pouvoir savoir que ces fichiers-là ne sont pas des orphelins avant de balayer le transit.
         *
         * Ensemble concurrent : il est écrit depuis le thread principal et lu depuis les threads
         * d'entrées/sorties du balayage.
         */
        val transitPathsBeingCommitted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}

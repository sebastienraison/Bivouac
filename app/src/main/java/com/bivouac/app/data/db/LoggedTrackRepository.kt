package com.bivouac.app.data.db

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import com.bivouac.app.data.gpx.DaySegmentAggregate
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.gpx.SpeedCalibration
import com.bivouac.app.data.gpx.SpeedCalibrationCalculator
import com.bivouac.app.data.gpx.TrackSegmenter
import com.bivouac.app.data.gpx.TrackStatsCalculator
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.Segment
import com.bivouac.app.data.photo.MediaStorePhotoQuery
import com.bivouac.app.data.photo.PhotoExifReader
import com.bivouac.app.data.photo.PhotoLibraryPermission
import com.bivouac.app.data.photo.PhotoPositionCorrelator
import com.bivouac.app.data.photo.PhotoSourceMetadata
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

// A parsed-and-ready-to-store import that hasn't been written to the DB yet — lets the caller
// check for duplicates (findDuplicate) before committing (commitImport), without re-reading or
// re-parsing the file. Carries the raw content itself (one entry per day, in day order): the
// backing file only gets written at commit time, so a discarded duplicate leaves no orphan.
data class PreparedImport(val entity: LoggedTrackEntity, val days: List<PreparedDay>)

// Un jour d'un import en attente, avec ce qui a été relevé pendant le parsing — le fichier est
// déjà lu et parsé à ce moment-là, donc dénormaliser ne coûte rien de plus ici, alors que le
// relire ensuite coûte une passe complète. Voir LoggedTrackDayEntity pour le sens des champs.
data class PreparedDay(
    val rawGpx: String,
    val contentHash: String,
    val startedAtMillis: Long?,
    val elapsedSeconds: Long?,
    // RIC-109 : les sept sommes de segments de ce jour, calculées ici pendant que track.points est
    // déjà parsé — voir DaySegmentAggregate. DaySegmentAggregate.EMPTY (pas de segment exploitable,
    // ex. GPX sans altitude ou trop court) plutôt qu'un type nullable : un jour fraîchement importé
    // n'a jamais besoin de rattrapage, contrairement à une ligne héritée d'avant cette migration.
    val segmentAggregate: DaySegmentAggregate,
    // RIC-19 : calculées ici pendant que track.points est déjà parsé, même raisonnement que
    // segmentAggregate ci-dessus — un jour fraîchement importé n'a jamais besoin du rattrapage
    // d'altitude, voir LoggedTrackDayEntity.elevationBackfilled. Défaut à null (et pas de valeur
    // obligatoire comme segmentAggregate) : un appelant qui les omet obtient un jour "sans altitude
    // connue", un état déjà légitime par ailleurs, plutôt qu'une erreur de compilation pour des
    // fixtures de test qui n'ont rien à voir avec RIC-19 (voir RepositoryBackupCycleTest).
    val maxElevationMeters: Double? = null,
    val lastPointElevationMeters: Double? = null,
)

// Une trace du Journal ouverte pour affichage : [track] est l'ensemble des jours concaténés (la
// carte et le profil altimétrique n'ont pas à connaître le découpage), [daySegments] ces mêmes
// points redécoupés par jour, un par LoggedTrackDayEntity et dans l'ordre des jours, pour la
// ventilation « Total » + « Jour N » de la vue détail (RIC-41). Une trace d'un seul jour donne une
// liste à un élément ; l'affichage de la ventilation ne se déclenche qu'au-delà, même convention
// que les segments de Planification.
data class LoggedTrackDetail(val track: HikeTrack, val daySegments: List<Segment>)

// Ce que la liste du Journal doit savoir des jours d'une trace, sans ouvrir de fichier.
data class DaySummary(val dayCount: Int, val startMillis: List<Long>)

/**
 * RIC-43 : issue d'un lot d'ajout de photos, photo par photo — voir [LoggedTrackRepository.addPhotosFromPicker].
 *
 * Même raison d'être que [SeparateImportReport][com.bivouac.app.journal.SeparateImportReport] côté
 * import GPX : chaque photo est traitée indépendamment, donc l'échec de l'une ne doit pas empêcher
 * les autres d'entrer, et le seul moment où l'utilisateur peut apprendre ce qui s'est réellement
 * passé est la fin du lot. Sans ça, une sélection de dix photos dont trois sont des doublons
 * rendait sept vignettes sans jamais dire ce qu'étaient devenues les trois autres.
 */
data class PhotoAddReport(val added: Int, val duplicatesSkipped: Int, val failed: Int)

/**
 * RIC-149 : une photo choisie dans le sélecteur, copiée en zone de transit, pas encore en base.
 *
 * Tout le travail coûteux (empreinte, lecture EXIF, corrélation avec la trace, copie des octets) est
 * fait au moment de la sélection, pas à la sauvegarde : la disquette ne fait plus qu'un déplacement
 * de fichier et un insert, donc elle répond tout de suite, et le bandeau montre les vignettes dès la
 * validation du sélecteur — c'est-à-dire au moment où l'utilisateur s'attend à les voir.
 *
 * [displayId] est négatif, et n'existe que dans la mémoire du process : il sert de clé de liste et
 * de cible de suppression tant que la photo n'a pas de vrai id. Négatif justement pour qu'il ne
 * puisse jamais être confondu avec un id Room autogénéré, qui commence à 1.
 */
data class PendingPhotoAdd(
    val displayId: Long,
    // Chemin préfixé « transit: », résolu par LoggedTrackPhotoStore.resolve comme n'importe quel
    // autre chemin de photo — c'est ce qui permet à l'UI d'afficher un ajout en attente sans rien
    // savoir de son statut.
    val transitPath: String,
    val contentHash: String,
    val takenAtMillis: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val positionPointIndex: Int?,
    val positionApproximate: Boolean,
    val takenAtZoneCertain: Boolean?,
    val source: PhotoSourceMetadata,
)

/** Ce que rend un passage du sélecteur : ce qui est entré en transit, et le bilan du lot. */
data class StagedPhotoBatch(val staged: List<PendingPhotoAdd>, val report: PhotoAddReport)

/** RIC-152 : ce que « Purger les photos » annonce avant d'agir — voir [LoggedTrackRepository.photoStorageSummary]. */
data class PhotoStorageSummary(val count: Int, val totalBytes: Long)

// Identifiants d'affichage des ajouts en attente : uniques pour la durée du process, et toujours
// négatifs pour ne jamais pouvoir croiser un id Room autogénéré (qui part de 1). Au niveau du
// fichier et non de l'instance de repository : plusieurs repositories coexistent (un par
// ViewModel), et deux photos en attente ne doivent pas partager un id sous prétexte qu'elles
// viennent de deux écrans différents.
private val pendingPhotoDisplayIds = AtomicLong(0L)

private fun nextPendingPhotoDisplayId(): Long = -pendingPhotoDisplayIds.incrementAndGet()

sealed interface DuplicateMatch {
    val existing: LoggedTrackEntity
    data class Exact(override val existing: LoggedTrackEntity) : DuplicateMatch
    data class Probable(override val existing: LoggedTrackEntity) : DuplicateMatch

    /**
     * Au moins un jour du lot entrant est déjà, à l'octet près, un jour d'une sortie du Journal,
     * sans que les deux sorties soient identiques : importer une seule journée d'un trek déjà en
     * banque, ou le trek entier alors qu'une de ses journées y est déjà seule.
     *
     * Traité comme un avertissement et jamais comme un blocage, dans les deux sens : la
     * ressemblance est certaine mais l'intention ne l'est pas, et refuser un trek de six jours
     * parce que son jour 3 existe déjà à part serait pire que le doublon.
     */
    data class SharedDay(
        override val existing: LoggedTrackEntity,
        val sharedDays: Int,
        val incomingDays: Int,
    ) : DuplicateMatch
}

class LoggedTrackRepository(context: Context) {

    private val appContext = context.applicationContext

    // RIC-103 : propriété calculée, jamais figée. BackupManager.backup() ferme puis rouvre la
    // base (closeAndReset) pendant que ce repository vit dans un ViewModel : un DAO capturé à la
    // construction resterait accroché à la connexion fermée, et chaque lecture mourrait ensuite
    // en JobCancellationException, silencieusement avalée par viewModelScope, jusqu'au
    // redémarrage du process. Résoudre ici est bon marché : getInstance() comme le xxxDao()
    // généré par Room se réduisent à une lecture volatile une fois initialisés.
    private val dao get() = BivouacDatabase.getInstance(appContext).loggedTrackDao()

    suspend fun list(): List<LoggedTrackEntity> = dao.list()

    /**
     * Rattrape les colonnes dénormalisées des traces importées avant la migration 8 vers 9. Sans
     * effet une fois la banque à jour, et interruptible : voir [LoggedTrackBackfill].
     */
    suspend fun backfillDenormalizedFields() = LoggedTrackBackfill.run(appContext, dao)

    /**
     * RIC-19 : rattrapage de maxElevationMeters/lastPointElevationMeters, bloquant côté appelant
     * (voir ElevationBackfillGate) — contrairement à [backfillDenormalizedFields] ci-dessus,
     * délibérément pas fire-and-forget. Sans effet une fois la banque à jour.
     */
    suspend fun backfillElevationFields(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) =
        LoggedTrackBackfill.runElevation(appContext, dao, onProgress)

    /** Ce que la porte d'accueil (RIC-19) doit savoir avant de décider d'afficher le popup bloquant. */
    suspend fun countDaysNeedingElevationBackfill(): Int = dao.countDaysNeedingElevationBackfill()

    /**
     * Ce que la liste doit savoir des jours d'une trace sans ouvrir le moindre fichier : combien
     * elle en compte, et quand chacun a commencé. Une requête pour toute la banque.
     */
    suspend fun daySummariesByTrackId(): Map<String, DaySummary> =
        allDaysByTrackId()
            .mapValues { (_, days) ->
                DaySummary(
                    dayCount = days.size,
                    // Les jours sans horodatage exploitable, et ceux que le rattrapage n'a pas
                    // encore traités, sont absents : afficher les dates connues vaut mieux
                    // qu'inventer les autres. dayCount, lui, est toujours juste.
                    startMillis = days.mapNotNull { it.startedAtMillis },
                )
            }

    /**
     * RIC-19 : ce dont [com.bivouac.app.bilan.BilanStatsCalculator] a besoin pour les records de
     * granularité "jour" (VAM, altitude, bivouac le plus haut, distance/D+ max journée) — les jours
     * de chaque trace, triés, sans ouvrir le moindre fichier (colonnes dénormalisées uniquement).
     */
    suspend fun allDaysByTrackId(): Map<String, List<LoggedTrackDayEntity>> =
        dao.getAllDays().groupBy { it.trackId }.mapValues { (_, days) -> days.sortedBy { it.dayIndex } }

    /**
     * Reads, parses and hashes one or more raw GPX files into a single logged track (RIC-41 : un
     * fichier = un jour d'une même sortie), without writing anything to the DB yet — the raw
     * content itself is only used transiently here (via [GpxParser]) to compute the denormalized
     * stats and the hike's start date; it's stored untouched separately (see LoggedTrackDayEntity).
     *
     * L'ordre des jours ne suit pas l'ordre de sélection mais l'horodatage de chaque fichier, voir
     * [ImportDayOrdering]. Les statistiques agrégées sont la somme de celles de chaque jour, pas
     * un calcul sur la concaténation : ça évite de compter le trajet fictif entre l'arrivée d'un
     * jour et le départ du lendemain.
     */
    suspend fun prepareImport(
        resolver: ContentResolver,
        uris: List<Uri>,
        calibration: SpeedCalibration = SpeedCalibration.DEFAULT,
    ): PreparedImport {
        require(uris.isNotEmpty()) { "Aucun fichier sélectionné" }
        // readBounded plutôt que readBytes() : même plafond de taille que le parseur, appliqué
        // avant la première allocation pleine du fichier (entrée externe non maîtrisée, RIC-95).
        val parsed = uris.map { uri ->
            val rawGpx = resolver.openInputStream(uri)?.use { GpxParser.readBounded(it) }
                ?: throw IOException("Impossible d'ouvrir le fichier sélectionné")
            rawGpx to rawGpx.byteInputStream(StandardCharsets.UTF_8).use { GpxParser.parse(it) }
        }
        val ordered = ImportDayOrdering.orderIndices(parsed.map { (_, track) -> track.points.firstOrNull()?.time })
            .map { parsed[it] }
        val dayStats = ordered.map { (_, track) -> TrackStatsCalculator.compute(track.points, calibration) }
        val startedAt = ordered.first().second.points.firstOrNull()?.time?.toEpochMilli()
            ?: System.currentTimeMillis()

        val id = UUID.randomUUID().toString()
        val entity = LoggedTrackEntity(
            id = id,
            name = ordered.first().second.name ?: "Trace sans nom",
            startedAt = startedAt,
            // Concaténation des contenus dans l'ordre des jours, pas une combinaison de hachages
            // par fichier : réimporter exactement le même lot doit retomber sur le même hash pour
            // que findDuplicate le bloque. Sur un fichier unique, ça reste le hash de ce fichier,
            // donc les traces déjà importées gardent le leur.
            contentHash = sha256(ordered.joinToString("\n") { (rawGpx, _) -> rawGpx }),
            distanceMeters = dayStats.sumOf { it.distanceMeters },
            elevationGainMeters = dayStats.sumOf { it.elevationGainMeters },
            elevationLossMeters = dayStats.sumOf { it.elevationLossMeters },
            pointCount = ordered.sumOf { (_, track) -> track.points.size },
            estimatedDurationMinutes = dayStats.sumOf { it.estimatedDurationMinutes },
        )
        val days = ordered.map { (rawGpx, track) ->
            PreparedDay(
                rawGpx = rawGpx,
                contentHash = sha256(rawGpx),
                startedAtMillis = track.points.firstOrNull()?.time?.toEpochMilli(),
                elapsedSeconds = elapsedSeconds(track),
                segmentAggregate = DaySegmentAggregate.of(TrackSegmenter.segment(track.points)),
                maxElevationMeters = track.points.mapNotNull { it.elevationMeters }.maxOrNull(),
                lastPointElevationMeters = track.points.lastOrNull()?.elevationMeters,
            )
        }
        return PreparedImport(entity, days)
    }

    // null quand le jour n'a pas d'horodatage exploitable, ce qui est un cas réel : la calibration
    // écarte alors la trace au lieu de lui prêter une durée inventée.
    private fun elapsedSeconds(track: HikeTrack): Long? {
        val first = track.points.firstOrNull()?.time ?: return null
        val last = track.points.lastOrNull()?.time ?: return null
        return Duration.between(first, last).seconds.takeIf { it > 0 }
    }

    /**
     * Two-tier check: an identical content hash means the exact same file was already imported
     * (re-exporting the same hike from a different tool won't hash the same, so this alone isn't
     * enough) — [DuplicateMatch.Exact], meant to hard-block. Failing that, a start time within an
     * hour and a distance within 5% of an existing entry is treated as a probable duplicate — two
     * different hikes of similar length can happen on the same day, but not within the same hour
     * — [DuplicateMatch.Probable], meant as a "import anyway?" warning rather than a block.
     */
    suspend fun findDuplicate(prepared: PreparedImport): DuplicateMatch? {
        val all = dao.list()
        all.find { it.contentHash == prepared.entity.contentHash }?.let { return DuplicateMatch.Exact(it) }
        findSharedDay(prepared, all)?.let { return it }
        all.find { isProbablySameHike(it, prepared.entity) }?.let { return DuplicateMatch.Probable(it) }
        return null
    }

    /**
     * Constat A de la recette : le hash de `logged_track` porte sur la concaténation des jours
     * dans l'ordre, donc un fichier seul ne peut jamais correspondre à un trek de trois jours, et
     * le repli « doublon probable » ne rattrape pas non plus, le jour 1 partageant la date de
     * départ du trek entier mais pas sa distance. D'où cette comparaison jour à jour, qui n'est
     * possible que depuis que le hash de chaque fichier est stocké.
     *
     * Ne voit que les jours déjà rattrapés (voir [LoggedTrackBackfill]) : sur une banque en cours
     * de rattrapage la détection est partielle, jamais fausse.
     */
    private suspend fun findSharedDay(prepared: PreparedImport, all: List<LoggedTrackEntity>): DuplicateMatch? {
        val incomingHashes = prepared.days.map { it.contentHash }.toSet()
        val matchesByTrack = dao.getAllDays()
            .filter { it.contentHash != null && it.contentHash in incomingHashes }
            .groupBy { it.trackId }
        // La sortie qui partage le plus de jours avec le lot entrant : sur un trek réimporté
        // journée par journée, c'est celle qui rend l'avertissement compréhensible.
        val (trackId, days) = matchesByTrack.maxByOrNull { it.value.size } ?: return null
        val existing = all.find { it.id == trackId } ?: return null
        return DuplicateMatch.SharedDay(
            existing = existing,
            sharedDays = days.size,
            incomingDays = prepared.days.size,
        )
    }

    // Fichiers d'abord, lignes ensuite : si l'insert échoue, les fichiers tout juste écrits sont
    // retirés ; si une écriture de fichier échoue, rien n'a touché la base — dans les deux cas
    // aucune ligne ne peut pointer vers un fichier absent.
    suspend fun commitImport(prepared: PreparedImport): String {
        LoggedTrackGpxStore.dir(appContext).mkdirs()
        val days = prepared.days.mapIndexed { dayIndex, day ->
            val relativePath = LoggedTrackGpxStore.relativePath(prepared.entity.id, dayIndex)
            LoggedTrackGpxStore.resolve(appContext, relativePath).writeText(day.rawGpx, StandardCharsets.UTF_8)
            LoggedTrackDayEntity(
                trackId = prepared.entity.id,
                dayIndex = dayIndex,
                rawGpxFilePath = relativePath,
                contentHash = day.contentHash,
                startedAtMillis = day.startedAtMillis,
                elapsedSeconds = day.elapsedSeconds,
                flatCount = day.segmentAggregate.flatCount,
                flatDistanceMeters = day.segmentAggregate.flatDistanceMeters,
                flatHours = day.segmentAggregate.flatHours,
                steepCount = day.segmentAggregate.steepCount,
                steepDistanceMeters = day.segmentAggregate.steepDistanceMeters,
                steepGainMeters = day.segmentAggregate.steepGainMeters,
                steepHours = day.segmentAggregate.steepHours,
                stoppedHours = day.segmentAggregate.stoppedHours,
                maxElevationMeters = day.maxElevationMeters,
                lastPointElevationMeters = day.lastPointElevationMeters,
                elevationBackfilled = true,
            )
        }
        try {
            dao.insert(prepared.entity, days)
        } catch (e: Exception) {
            days.forEach { LoggedTrackGpxStore.resolve(appContext, it.rawGpxFilePath).delete() }
            throw e
        }
        return prepared.entity.id
    }

    /** Concatenates every day's raw GPX, in order, into a single flat track for display. */
    suspend fun open(id: String): HikeTrack? {
        val entity = dao.get(id) ?: return null
        val days = dao.getDays(id)
        if (days.isEmpty()) return null
        val points = days.sortedBy { it.dayIndex }.flatMap { day ->
            LoggedTrackGpxStore.resolve(appContext, day.rawGpxFilePath).inputStream()
                .use { GpxParser.parse(it) }.points
        }
        return HikeTrack(name = entity.name, points = points)
    }

    /**
     * Même trace concaténée que [open], plus la ventilation par jour (RIC-41) dont la vue détail a
     * besoin pour afficher « Total » et « Jour N ». La durée de chaque jour est calculée avec la
     * calibration par défaut : c'est l'appelant qui la re-dérive sous la calibration active, comme
     * [LoggedTrackEntity.toTrackStats] le fait déjà pour la ligne agrégée.
     */
    suspend fun openDetail(id: String): LoggedTrackDetail? {
        val entity = dao.get(id) ?: return null
        val days = dao.getDays(id).sortedBy { it.dayIndex }
        if (days.isEmpty()) return null
        val dayTracks = days.map { day ->
            LoggedTrackGpxStore.resolve(appContext, day.rawGpxFilePath).inputStream().use { GpxParser.parse(it) }
        }
        return LoggedTrackDetail(
            track = HikeTrack(name = entity.name, points = dayTracks.flatMap { it.points }),
            daySegments = dayTracks.map { Segment(it.points, TrackStatsCalculator.compute(it.points)) },
        )
    }

    suspend fun delete(id: String) {
        // Chemins relevés avant le DELETE (le CASCADE emporte les lignes de jours et de photos,
        // RIC-43) ; la ligne disparaît avant son fichier, jamais l'inverse.
        val days = dao.getDays(id)
        val photos = dao.getPhotos(id)
        dao.delete(id)
        days.forEach { LoggedTrackGpxStore.resolve(appContext, it.rawGpxFilePath).delete() }
        photos.forEach { LoggedTrackPhotoStore.resolve(appContext, it.filePath).delete() }
    }

    suspend fun updateNote(id: String, note: String) {
        dao.updateNote(id, note)
    }

    suspend fun rename(id: String, name: String) {
        dao.updateName(id, name)
    }

    /** trackId -> its tags, for every track that has at least one. */
    suspend fun tagsByTrackId(): Map<String, List<String>> =
        dao.getAllTags().groupBy({ it.trackId }, { it.tag })

    suspend fun addTag(trackId: String, tag: String) {
        dao.insertTag(LoggedTrackTagEntity(trackId = trackId, tag = tag))
    }

    suspend fun removeTag(trackId: String, tag: String) {
        dao.deleteTag(trackId, tag)
    }

    // L'extension du fichier local, déduite du type MIME annoncé par le fournisseur — « jpg » par
    // défaut, faute de mieux : rien ici ne décode l'image, l'extension n'est qu'une commodité.
    private fun extensionFor(resolver: ContentResolver, uri: Uri): String =
        resolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "jpg"

    /**
     * RIC-43/149 : ce que l'écran appelle après une sélection dans le sélecteur interne — lit l'EXIF
     * de chaque [uris], corrèle avec la trace (voir PhotoPositionCorrelator) puis copie les octets
     * en **zone de transit**, sans rien écrire en base. La trace n'est ouverte qu'une fois pour tout
     * le lot, pas par photo.
     *
     * Rien n'entre dans le Journal ici : c'est [commitPendingPhotos] qui écrit, à la sauvegarde du
     * mode édition, et [discardPendingPhotos] qui efface, à l'abandon. Voir PendingPhotoAdd pour
     * pourquoi tout le travail lourd est fait maintenant plutôt qu'à la sauvegarde.
     *
     * Écarte les doublons par contenu (SHA-256), pas par Uri : deux entrées MediaStore distinctes
     * peuvent porter exactement la même photo (copie, image reçue puis ré-enregistrée), et seul le
     * contenu permet de détecter « déjà présente sur cette trace » ou « déjà choisie plus tôt dans
     * ce même lot » (sélection multiple avec la même photo touchée deux fois). Le contrôle part des
     * photos déjà en base, auxquelles l'appelant ajoute [alreadyStagedHashes] (les ajouts déjà en
     * transit, invisibles de la base) et retire [ignoredHashes] (les photos marquées pour
     * suppression : elles ne seront plus là après la sauvegarde, refuser leur réajout dans le même
     * geste d'édition n'aurait aucun sens).
     *
     * Chaque photo est traitée indépendamment : celle dont l'Uri a été révoquée entre la sélection
     * et l'ajout est comptée en échec et le lot continue, plutôt que d'abandonner les suivantes.
     * Le [PhotoAddReport] rendu est ce que l'écran affiche à la fin. Seules les erreurs qui
     * concernent le lot entier (trace illisible, base inaccessible) remontent en exception.
     */
    suspend fun stagePhotosFromPicker(
        trackId: String,
        resolver: ContentResolver,
        uris: List<Uri>,
        alreadyStagedHashes: Set<String> = emptySet(),
        ignoredHashes: Set<String> = emptySet(),
    ): StagedPhotoBatch {
        val points = open(trackId)?.points.orEmpty()
        val seenHashes = dao.getPhotos(trackId)
            .mapTo(mutableSetOf()) { it.contentHash }
            .apply {
                removeAll(ignoredHashes)
                addAll(alreadyStagedHashes)
            }
        // Relevé une fois pour tout le lot, pas par photo : la permission ne peut pas changer au
        // milieu sans que l'app repasse au premier plan, et c'est un checkSelfPermission par appel.
        val mediaLocationGranted = PhotoLibraryPermission.isMediaLocationGranted(appContext)
        val staged = mutableListOf<PendingPhotoAdd>()
        var duplicatesSkipped = 0
        var failed = 0
        LoggedTrackPhotoStore.transitDir(appContext).mkdirs()
        for (uri in uris) {
            runCatching {
                val contentHash = resolver.openInputStream(uri)?.use { sha256(it) }
                    ?: throw IOException("Impossible d'ouvrir la photo sélectionnée")
                if (!seenHashes.add(contentHash)) return@runCatching false
                val exif = PhotoExifReader.read(resolver, uri, requireOriginal = mediaLocationGranted)
                val position =
                    PhotoPositionCorrelator.correlate(points, exif.latitude, exif.longitude, exif.takenAtMillis)
                val transitPath = LoggedTrackPhotoStore.transitPath(trackId, extensionFor(resolver, uri))
                val target = LoggedTrackPhotoStore.resolve(appContext, transitPath)
                resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: throw IOException("Impossible d'ouvrir la photo sélectionnée")
                staged += PendingPhotoAdd(
                    displayId = nextPendingPhotoDisplayId(),
                    transitPath = transitPath,
                    contentHash = contentHash,
                    takenAtMillis = exif.takenAtMillis,
                    latitude = exif.latitude,
                    longitude = exif.longitude,
                    positionPointIndex = position.pointIndex,
                    positionApproximate = position.approximate,
                    takenAtZoneCertain = exif.takenAtZoneCertain,
                    source = MediaStorePhotoQuery.readSource(resolver, uri),
                )
                true
            }.onSuccess { wasStaged ->
                if (!wasStaged) duplicatesSkipped++
            }.onFailure {
                Log.w("LoggedTrackRepository", "Photo ignorée, ajout impossible", it)
                failed++
            }
        }
        return StagedPhotoBatch(
            staged = staged,
            report = PhotoAddReport(added = staged.size, duplicatesSkipped = duplicatesSkipped, failed = failed),
        )
    }

    /**
     * RIC-149 : la sauvegarde du mode édition, côté ajouts — chaque fichier de transit rejoint
     * filesDir/photos/ et sa ligne entre en base, dans cet ordre (« fichier d'abord, ligne
     * ensuite », comme [addPhoto] et [commitImport]).
     *
     * Déplacement et non recopie : les octets sont déjà écrits, les relire pour les réécrire
     * coûterait une passe complète par photo pour rien. `renameTo` échoue si cacheDir et filesDir ne
     * sont pas sur le même volume (ce n'est pas garanti par la plateforme, seulement habituel), d'où
     * le repli par copie.
     *
     * Best effort photo par photo, comme le lot d'ajout : une photo qui ne peut pas être écrite ne
     * doit pas emporter les autres ni le reste de la sauvegarde (tags, note). Rend le nombre
     * d'échecs, que l'appelant raconte.
     */
    suspend fun commitPendingPhotos(trackId: String, pending: List<PendingPhotoAdd>): Int {
        if (pending.isEmpty()) return 0
        LoggedTrackPhotoStore.dir(appContext).mkdirs()
        var failed = 0
        for (add in pending) {
            runCatching {
                val transitFile = LoggedTrackPhotoStore.resolve(appContext, add.transitPath)
                val extension = transitFile.extension.ifEmpty { "jpg" }
                val relativePath = LoggedTrackPhotoStore.relativePath(trackId, extension)
                val target = LoggedTrackPhotoStore.resolve(appContext, relativePath)
                if (!transitFile.renameTo(target)) {
                    transitFile.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                    transitFile.delete()
                }
                val entity = LoggedTrackPhotoEntity(
                    trackId = trackId,
                    filePath = relativePath,
                    addedAtMillis = System.currentTimeMillis(),
                    takenAtMillis = add.takenAtMillis,
                    latitude = add.latitude,
                    longitude = add.longitude,
                    positionPointIndex = add.positionPointIndex,
                    positionApproximate = add.positionApproximate,
                    takenAtZoneCertain = add.takenAtZoneCertain,
                    contentHash = add.contentHash,
                    sourceDisplayName = add.source.displayName,
                    sourceRelativePath = add.source.relativePath,
                    sourceDateTakenMillis = add.source.dateTakenMillis,
                )
                try {
                    dao.insertPhoto(entity)
                } catch (e: Exception) {
                    target.delete()
                    throw e
                }
            }.onFailure {
                Log.w("LoggedTrackRepository", "Photo en transit non enregistrée", it)
                failed++
            }
        }
        return failed
    }

    /**
     * RIC-149 : l'abandon du mode édition, côté ajouts — les octets copiés en transit n'ont jamais
     * eu de ligne, il n'y a donc que des fichiers à retirer.
     */
    fun discardPendingPhotos(pending: List<PendingPhotoAdd>) {
        pending.forEach { LoggedTrackPhotoStore.resolve(appContext, it.transitPath).delete() }
    }

    /**
     * RIC-149 : les transits qu'aucune édition en cours ne revendique.
     *
     * Un process tué pendant une édition (Android récupère de la mémoire, l'utilisateur balaie
     * l'app) laisse ses fichiers de transit derrière lui, sans personne pour les abandonner. Le
     * cache est purgeable par le système, donc ce n'est pas une fuite définitive, mais il n'y a
     * aucune raison de laisser traîner des octets que plus rien ne peut valider : ce balayage est
     * fait à l'ouverture du Journal, seul endroit d'où un transit peut naître.
     *
     * [keptPaths] existe pour que ce balayage reste sûr même appelé pendant qu'une édition tient
     * des transits vivants.
     */
    fun purgePhotoTransit(keptPaths: Set<String> = emptySet()) {
        val kept = keptPaths.map { LoggedTrackPhotoStore.resolve(appContext, it) }.toSet()
        LoggedTrackPhotoStore.transitDir(appContext).listFiles().orEmpty()
            .filter { it.isFile && it !in kept }
            .forEach { it.delete() }
    }

    suspend fun listPhotos(trackId: String): List<LoggedTrackPhotoEntity> = dao.getPhotos(trackId)

    /**
     * RIC-43 : parmi [photos], celles dont la copie locale a disparu — restauration d'une
     * sauvegarde antérieure à leur ajout, nettoyage manuel du stockage de l'app, ou tout simplement
     * une écriture qui n'a jamais abouti.
     *
     * Aucune de ces lignes n'est supprimée pour autant, ni ici ni ailleurs : leurs métadonnées
     * d'origine (colonnes source* de [LoggedTrackPhotoEntity]) sont ce qui permettra de re-acquérir
     * la photo depuis la galerie (RIC-151). Elles doivent survivre.
     *
     * Un stat par photo, fait une fois par rafraîchissement de la liste plutôt qu'à chaque rendu
     * de carte ou de vignette.
     */
    fun missingPhotoFileIds(photos: List<LoggedTrackPhotoEntity>): Set<Long> =
        photos.filterNot { LoggedTrackPhotoStore.resolve(appContext, it.filePath).exists() }
            .mapTo(mutableSetOf()) { it.id }

    // Suppression d'une seule photo (RIC-43) : jamais une cascade ici, contrairement à delete(id)
    // ci-dessus — c'est la ligne elle-même qui disparaît, donc son chemin doit être lu avant.
    //
    // RIC-149 : appelée à la sauvegarde du mode édition, jamais au moment où l'utilisateur tape
    // « Supprimer » — d'ici là la suppression n'est qu'une intention, portée par le ViewModel.
    suspend fun deletePhoto(id: Long) {
        val photo = dao.getPhoto(id) ?: return
        dao.deletePhoto(id)
        LoggedTrackPhotoStore.resolve(appContext, photo.filePath).delete()
    }

    /** RIC-149 : les suppressions accumulées pendant une édition, appliquées d'un bloc. */
    suspend fun deletePhotos(ids: Collection<Long>) {
        ids.forEach { deletePhoto(it) }
    }

    // "Repositionner" (RIC-43) : toujours positionApproximate = false, qu'il s'agisse de corriger
    // une position déduite par horodatage ou de déplacer une position déjà certaine — un
    // repositionnement manuel vaut confirmation explicite dans les deux cas.
    //
    // Plus atteignable depuis l'UI : le menu d'appui long d'une vignette ne garde que
    // « Supprimer », la mécanique de placement étant différée à un lot ultérieur. Conservée telle
    // quelle, avec sa requête DAO, pour que ce lot-là la reprenne plutôt que de la réécrire.
    suspend fun repositionPhoto(id: Long, positionPointIndex: Int?) {
        dao.updatePhotoPosition(id, positionPointIndex, positionApproximate = false)
    }

    /**
     * RIC-152 : ce que le bouton « Purger les photos » affiche avant d'agir — le nombre de photos
     * en base et la place que prennent leurs fichiers.
     *
     * L'espace est mesuré sur les fichiers réellement pointés par les lignes, pas sur le dossier
     * entier : c'est ce que la purge va libérer à coup sûr, et une ligne dont le fichier a disparu
     * (RIC-151) compte alors pour zéro octet, ce qui est exact.
     */
    suspend fun photoStorageSummary(): PhotoStorageSummary {
        val paths = dao.getAllPhotoFilePaths()
        val totalBytes = paths.sumOf { LoggedTrackPhotoStore.resolve(appContext, it).length() }
        return PhotoStorageSummary(count = paths.size, totalBytes = totalBytes)
    }

    /**
     * RIC-152 : vide la table des photos et le dossier qui porte leurs fichiers.
     *
     * Jamais appelée automatiquement, et surtout pas par la bascule des Réglages : désactiver la
     * fonctionnalité continue de tout conserver. Il n'y a qu'un seul chemin vers ici, un bouton
     * explicite doublé d'une confirmation.
     *
     * Le dossier est retiré en entier plutôt que fichier par fichier : à ce stade plus aucune ligne
     * ne le référence, donc ce qui y resterait serait par définition orphelin.
     */
    suspend fun purgeAllPhotos() {
        dao.deleteAllPhotos()
        LoggedTrackPhotoStore.dir(appContext).deleteRecursively()
    }

    /**
     * RIC-109 : ce que [SpeedCalibrationCalculator.compute] a besoin de voir de la banque (ou du
     * sous-ensemble [ids]) pour calibrer par segments — voir la kdoc de `compute` pour ce que porte
     * chaque champ et pourquoi ils viennent de deux chemins différents.
     */
    data class SegmentCalibrationInput(
        val aggregate: DaySegmentAggregate,
        val fallbackSamples: List<SpeedCalibrationCalculator.Sample>,
    )

    /**
     * Calibration input (BIV-16 Auto/Sélection) for every track in [ids], or the whole Journal
     * when null.
     *
     * [SegmentCalibrationInput.aggregate] ne somme que les traces dont **tous** les jours portent
     * déjà les colonnes de segments (stoppedHours non nul, RIC-115 : implique déjà flatCount non
     * nul, voir LoggedTrackDao) : une trace dont un seul jour n'est pas encore rattrapé (RIC-109,
     * migration 10->11 ; RIC-115, migration 11->12) est purement et simplement absente de cette
     * somme plutôt que d'y contribuer une somme partielle — mélanger une somme partielle avec des
     * jours ignorés donnerait une calibration silencieusement fausse (même risque, même traitement
     * que le garde-fou RIC-98/99 qui protégeait déjà startedAtMillis/elapsedSeconds). Contrairement
     * à ce garde-fou historique, il n'y a délibérément *pas* de repli qui reparserait les GPX pour
     * calculer les segments manquants à la volée : ça réintroduirait exactement le coût que
     * RIC-62/98/99 a supprimé. La banque encore partiellement rattrapée contribue simplement moins
     * de segments jusqu'à ce que [LoggedTrackBackfill] la rattrape — en pratique quelques secondes.
     *
     * [SegmentCalibrationInput.fallbackSamples] reste construit exactement comme avant RIC-109 (une
     * ligne par rando, distance/D+/durée), pour le repli à une seule inconnue de
     * [SpeedCalibrationCalculator] quand l'agrégat de segments n'a pas assez de plat.
     */
    suspend fun calibrationSamples(ids: Set<String>? = null): SegmentCalibrationInput {
        val entries = dao.list().filter { ids == null || it.id in ids }
        // Deux requêtes pour toute la banque, contre une par trace plus un parsing complet de
        // chacun de ses fichiers auparavant. C'était la cause de la lenteur d'import : le coût ne
        // dépendait pas de ce qu'on importait mais du nombre de traces déjà en banque, donc il
        // grossissait tout seul (constat B de la recette).
        val daysByTrack = dao.getAllDays().groupBy { it.trackId }

        val aggregate = entries.fold(DaySegmentAggregate.EMPTY) { total, entry ->
            val days = daysByTrack[entry.id].orEmpty()
            if (days.isNotEmpty() && days.all { it.stoppedHours != null }) {
                total + days.fold(DaySegmentAggregate.EMPTY) { dayTotal, day -> dayTotal + day.toSegmentAggregate() }
            } else {
                total
            }
        }

        val fallbackSamples = entries.mapNotNull { entry ->
            val days = daysByTrack[entry.id].orEmpty()
            // Une trace dont un seul jour n'est pas encore rattrapé repasse entièrement par
            // l'ancien chemin : mélanger une somme partielle avec des jours ignorés donnerait une
            // durée écoulée trop courte, donc une vitesse trop rapide, silencieusement.
            if (days.isNotEmpty() && days.all { it.contentHash != null }) {
                val elapsedHours = days.sumOf { it.elapsedSeconds ?: 0L } / 3_600.0
                if (elapsedHours <= 0.0) {
                    null
                } else {
                    SpeedCalibrationCalculator.Sample(
                        distanceMeters = entry.distanceMeters,
                        elevationGainMeters = entry.elevationGainMeters,
                        elapsedHours = elapsedHours,
                    )
                }
            } else {
                calibrationSample(entry)
            }
        }

        return SegmentCalibrationInput(aggregate, fallbackSamples)
    }

    private fun LoggedTrackDayEntity.toSegmentAggregate(): DaySegmentAggregate = DaySegmentAggregate(
        flatCount = flatCount ?: 0,
        flatDistanceMeters = flatDistanceMeters ?: 0.0,
        flatHours = flatHours ?: 0.0,
        steepCount = steepCount ?: 0,
        steepDistanceMeters = steepDistanceMeters ?: 0.0,
        steepGainMeters = steepGainMeters ?: 0.0,
        steepHours = steepHours ?: 0.0,
        stoppedHours = stoppedHours ?: 0.0,
    )

    // Elapsed time is summed per day (not first-to-last across the whole track) so an overnight
    // gap on a multi-day hike never gets counted as "elapsed walking time" — see
    // SpeedCalibrationCalculator's kdoc for why a real elapsed duration matters here.
    //
    // Le plafond CursorWindow qui faisait planter ici (BIV-16 recette) est levé depuis RIC-62 —
    // le contenu vient d'un fichier, plus d'un Cursor. Le runCatching reste : un fichier manquant
    // ou un GPX corrompu ne doit pas couler la calibration des autres traces — même traitement
    // "silently skipped" qu'une trace sans horodatages exploitables.
    private suspend fun calibrationSample(entry: LoggedTrackEntity): SpeedCalibrationCalculator.Sample? {
        val elapsedHours = runCatching {
            dao.getDays(entry.id).sumOf { day ->
                val points = LoggedTrackGpxStore.resolve(appContext, day.rawGpxFilePath).inputStream()
                    .use { GpxParser.parse(it) }.points
                val first = points.firstOrNull()?.time
                val last = points.lastOrNull()?.time
                if (first != null && last != null) Duration.between(first, last).toMillis() / 3_600_000.0 else 0.0
            }
        }.getOrElse {
            Log.w("LoggedTrackRepository", "Trace « ${entry.name} » illisible pour la calibration, ignorée", it)
            return null
        }
        if (elapsedHours <= 0.0) return null
        return SpeedCalibrationCalculator.Sample(
            distanceMeters = entry.distanceMeters,
            elevationGainMeters = entry.elevationGainMeters,
            elapsedHours = elapsedHours,
        )
    }

    private fun isProbablySameHike(a: LoggedTrackEntity, b: LoggedTrackEntity): Boolean {
        val startCloseEnough = abs(a.startedAt - b.startedAt) <= ONE_HOUR_MILLIS
        val avgDistance = (a.distanceMeters + b.distanceMeters) / 2
        val distanceCloseEnough = avgDistance > 0 && abs(a.distanceMeters - b.distanceMeters) / avgDistance <= 0.05
        return startCloseEnough && distanceCloseEnough
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // RIC-43 : par flux plutôt que text.toByteArray() — une photo (quelques Mo) n'a pas à
    // transiter par une String intermédiaire comme le fait la variante GPX ci-dessus.
    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ONE_HOUR_MILLIS = 3_600_000L
    }
}

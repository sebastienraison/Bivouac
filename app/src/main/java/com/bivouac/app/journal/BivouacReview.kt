package com.bivouac.app.journal

import com.bivouac.app.data.gpx.BIVOUAC_REVIEW_VERSION
import com.bivouac.app.data.gpx.BivouacDetector
import com.bivouac.app.data.gpx.DayBivouacs
import com.bivouac.app.data.gpx.DetectedBivouac
import com.bivouac.app.data.gpx.LocalZone
import com.bivouac.app.data.model.TrackPoint
import java.time.ZoneId

/**
 * RIC-227 (lot 3 de la 2.7.0, conception RIC-222 §3.1 et §3.2) : la logique de décision du crible
 * des bivouacs, isolée de Compose, du ViewModel et de la base. Trois usages, une seule logique :
 *
 *   - import d'un fichier (ou d'un trek en plusieurs fichiers) : une question par bivouac détecté
 *     à l'intérieur d'un fichier, fichier par fichier puis dans l'ordre chronologique ;
 *   - crible du premier lancement : même chose pour chaque rando pas encore passée au crible
 *     courant ([BIVOUAC_REVIEW_VERSION]), rien pour une rando déjà examinée ;
 *   - action de menu « Détecter les bivouacs » : les mêmes questions, ou l'un des deux messages
 *     quand il n'y a rien à demander ([MenuResult]).
 *
 * Les réponses sont ramenées par [outcome] à ce qu'il faut écrire : les bivouacs à découper et les
 * nuits refusées, par rang de jour, et la version du crible à poser sur la rando. Écrire est
 * l'affaire de l'appelant (LoggedTrackRepository.applyBivouacReview), qui traite chaque jour en une
 * fois : un jour à deux nuits découpées aux deux reçoit ses deux bivouacs dans le même appel, ce
 * que splitDay exige (ses index sont ceux des points du jour d'avant tout découpage).
 *
 * RIC-229 (lot 7, conception RIC-222 §10) : la réponse « Pause » ([Answer.PAUSE], ex « Ne pas
 * découper ») dit que la nuit n'est pas un bivouac. Elle est désormais mémorisée par jour
 * (`logged_track_day.refusedBivouacStops`) et la nuit devient une pause forcée dans les calculs
 * (DayBivouacs.nights). Une question laissée sans réponse ([outcome] reçoit `null`, l'utilisateur a
 * quitté le dialogue autrement que par un bouton) vaut « Pause ». Le crible ne pose que les nuits
 * des bivouacs effectifs ([effectiveDays]) ; l'action de menu repropose toutes les nuits, refusées
 * comprises ([menuResult], [Question.wasRefused]) : c'est le moyen de revenir sur une réponse.
 */
object BivouacReview {

    enum class Answer { SPLIT, PAUSE }

    /**
     * Une question : le [bivouac] détecté dans le jour de rang [dayPosition] (rang dans la liste
     * des jours passée à [questions], 0 pour le premier), en index des points de ce jour.
     * RIC-229 : [wasRefused] vrai quand cette nuit avait déjà reçu « Pause » (action de menu
     * seulement) : répondre « Bivouac » retire le refus et découpe, « Pause » le confirme.
     */
    data class Question(val dayPosition: Int, val bivouac: DetectedBivouac, val wasRefused: Boolean = false)

    /**
     * Ce qu'il faut écrire une fois toutes les questions répondues : [splits], les bivouacs à
     * découper par rang de jour (absent : jour gardé tel quel), [pauses] (RIC-229), les nuits
     * refusées par rang de jour (réponse « Pause », ou dialogue quitté), et [reviewVersion], la
     * version du crible à poser sur la rando, dans tous les cas.
     */
    data class Outcome(
        val splits: Map<Int, List<DetectedBivouac>>,
        val reviewVersion: Int,
        val pauses: Map<Int, List<DetectedBivouac>> = emptyMap(),
    )

    /** Ce que l'action de menu doit faire d'une rando. */
    sealed interface MenuResult {
        /** Aucun bivouac, ni à l'intérieur d'un jour ni entre deux jours : `bivouac_detect_none`. */
        data object NoneDetected : MenuResult

        /** Des bivouacs, mais tous entre deux jours (déjà des fins de journée) : `bivouac_detect_all_split`. */
        data object AllAlreadySplit : MenuResult

        /** Au moins une nuit à l'intérieur d'un jour : les questions, comme à l'import. */
        data class Ask(val questions: List<Question>) : MenuResult
    }

    /**
     * Toutes les nuits détectées à l'intérieur de chacun des jours de [dayPoints], dans l'ordre des
     * jours, refusées comprises (base de [menuResult]).
     */
    fun detectDays(
        dayPoints: List<List<TrackPoint>>,
        zoneFor: (TrackPoint) -> ZoneId = LocalZone::forPoint,
    ): List<List<DetectedBivouac>> = dayPoints.map { DayBivouacs.detect(it, zoneFor) }

    /**
     * RIC-229 : bivouacs effectifs de chacun des jours de [dayPoints] ([DayBivouacs.effective]),
     * [refusedStopsByDay] portant les arrêts refusés de chaque jour (vide ou plus court : aucun).
     * Base du crible du premier lancement : une nuit déjà refusée n'y est pas reposée.
     */
    fun effectiveDays(
        dayPoints: List<List<TrackPoint>>,
        refusedStopsByDay: List<Set<Int>>,
        zoneFor: (TrackPoint) -> ZoneId = LocalZone::forPoint,
    ): List<List<DetectedBivouac>> =
        dayPoints.mapIndexed { index, points -> DayBivouacs.effective(points, refusedStopsByDay.getOrNull(index).orEmpty(), zoneFor) }

    /**
     * Les questions à poser pour des jours dont les bivouacs internes sont [dayBivouacs] (un
     * élément par jour, dans l'ordre des jours) : jour par jour, puis dans l'ordre chronologique à
     * l'intérieur d'un jour. Vide quand aucun jour ne contient de nuit, ou qu'aucun n'est horodaté.
     */
    fun questions(dayBivouacs: List<List<DetectedBivouac>>): List<Question> =
        dayBivouacs.flatMapIndexed { position, bivouacs ->
            bivouacs.sortedBy { it.stopIndex }.map { Question(position, it) }
        }

    /**
     * Crible du premier lancement : `null` quand la rando, à la version [reviewVersion], est déjà
     * passée au crible courant (rien à faire, pas même une détection), sinon ses questions,
     * éventuellement aucune (la rando est alors seulement marquée examinée, voir [outcome]).
     */
    fun startupQuestions(reviewVersion: Int, dayBivouacs: () -> List<List<DetectedBivouac>>): List<Question>? =
        if (reviewVersion >= BIVOUAC_REVIEW_VERSION) null else questions(dayBivouacs())

    /** Vrai si une rando à la version [reviewVersion] doit passer au crible du premier lancement. */
    fun needsStartupReview(reviewVersion: Int): Boolean = reviewVersion < BIVOUAC_REVIEW_VERSION

    /**
     * Action de menu sur une rando de jours [dayPoints] : les questions des nuits internes s'il y
     * en a ; sinon, si au moins une jonction entre deux jours est elle-même un bivouac au sens de
     * [BivouacDetector] (dernier point d'un jour, premier point du suivant), tous les bivouacs sont
     * déjà des fins de journée ; sinon, aucun bivouac.
     *
     * RIC-229 : TOUTES les nuits internes, refusées comprises ([detectDays], pas les bivouacs
     * effectifs), chaque question marquée [Question.wasRefused] d'après [refusedStopsByDay] (les
     * arrêts refusés de chaque jour, dans l'ordre de [dayPoints]).
     */
    fun menuResult(
        dayPoints: List<List<TrackPoint>>,
        zoneFor: (TrackPoint) -> ZoneId = LocalZone::forPoint,
        refusedStopsByDay: List<Set<Int>> = emptyList(),
    ): MenuResult {
        val internal = questions(detectDays(dayPoints, zoneFor)).map { question ->
            question.copy(wasRefused = question.bivouac.stopIndex in refusedStopsByDay.getOrNull(question.dayPosition).orEmpty())
        }
        if (internal.isNotEmpty()) return MenuResult.Ask(internal)
        val junctionIsNight = dayPoints.filter { it.isNotEmpty() }.zipWithNext().any { (day, next) ->
            BivouacDetector.detect(listOf(day.last(), next.first()), zoneFor).isNotEmpty()
        }
        return if (junctionIsNight) MenuResult.AllAlreadySplit else MenuResult.NoneDetected
    }

    /**
     * Les réponses [answers] (une par question de [questions], dans le même ordre ; `null` vaut
     * [Answer.PAUSE]) ramenées à ce qu'il faut écrire. Sans question, aucun découpage, mais la
     * version du crible est posée quand même : sans elle, la détection serait refaite à chaque
     * lancement. RIC-229 : les réponses « Pause » (et `null`) donnent [Outcome.pauses].
     */
    fun outcome(questions: List<Question>, answers: List<Answer?>): Outcome {
        // i18n-ok : message d'une exception de programmation, jamais affiché (RIC-227).
        require(answers.size == questions.size) { "${answers.size} réponse(s) pour ${questions.size} question(s)" }
        val (split, paused) = questions.zip(answers).partition { (_, answer) -> answer == Answer.SPLIT }
        fun byDay(answered: List<Pair<Question, Answer?>>) =
            answered.groupBy({ (question, _) -> question.dayPosition }, { (question, _) -> question.bivouac })
        return Outcome(byDay(split), BIVOUAC_REVIEW_VERSION, byDay(paused))
    }

    /**
     * RIC-229 : vrai si les réponses [answers] à [questions] changent les refus mémorisés d'une
     * rando sans la découper : une nuit qui n'était pas refusée reçoit « Pause ». (« Bivouac »
     * répondu à une nuit refusée découpe, ce que [Outcome.splits] dit déjà.)
     */
    fun addsRefusals(questions: List<Question>, answers: List<Answer?>): Boolean =
        questions.zip(answers).any { (question, answer) -> answer != Answer.SPLIT && !question.wasRefused }
}

/**
 * RIC-227 : les questions de plusieurs randos posées à la suite, avec le nom de chacune dans le
 * titre : crible du premier lancement (ElevationBackfillGate) et questions d'après le rapport d'un
 * import en sorties séparées (JournalViewModel). Les randos sans question sont écartées ; l'ordre
 * est celui de [tracks] puis de leurs questions ; le compteur porte sur le total des bivouacs de
 * toutes les randos ; les réponses d'une rando s'appliquent dès sa dernière question
 * ([completedBy]), sans attendre les suivantes.
 */
class BivouacReviewBatch<T>(tracks: List<Track<T>>) {

    /** Une rando : [key] l'identifie pour l'appelant (identifiant, liste des jours...), [name] va dans le titre. */
    data class Track<T>(val key: T, val name: String, val questions: List<BivouacReview.Question>)

    /** Une rando dont toutes les questions sont répondues, et ce qu'il faut écrire pour elle. */
    data class Completed<T>(val track: Track<T>, val outcome: BivouacReview.Outcome)

    val tracks: List<Track<T>> = tracks.filter { it.questions.isNotEmpty() }

    private val flat: List<Pair<Int, BivouacReview.Question>> =
        this.tracks.flatMapIndexed { position, track -> track.questions.map { position to it } }

    /** Nombre total de questions, toutes randos confondues. */
    val total: Int get() = flat.size

    /** La question de rang [index] (à partir de 0), telle que l'écran l'affiche. */
    fun questionAt(index: Int): BivouacQuestion {
        val (position, question) = flat[index]
        return BivouacQuestion(tracks[position].name, question.bivouac, index + 1, total)
    }

    /**
     * Après la réponse à la question de rang `answers.size - 1` ([answers] : toutes les réponses
     * données jusqu'ici, dans l'ordre) : la rando qu'elle complète et son [BivouacReview.Outcome],
     * ou `null` si cette rando a encore des questions.
     */
    fun completedBy(answers: List<BivouacReview.Answer?>): Completed<T>? {
        val index = answers.size - 1
        val position = flat.getOrNull(index)?.first ?: return null
        if (flat.getOrNull(index + 1)?.first == position) return null
        val first = flat.indexOfFirst { it.first == position }
        val track = tracks[position]
        return Completed(track, BivouacReview.outcome(track.questions, answers.subList(first, index + 1)))
    }
}

/**
 * RIC-227 : une question du crible telle que l'écran l'affiche (voir BivouacDetectedDialog).
 * [trackName] non nul au crible du premier lancement et après le rapport d'un import en sorties
 * séparées (titre « Bivouac détecté dans « %1$s » »), nul à l'import d'une sortie seule et dans
 * l'action de menu. [number] (à partir de 1) sur [total] : le compteur,
 * affiché seulement quand [total] dépasse 1.
 */
data class BivouacQuestion(
    val trackName: String?,
    val bivouac: DetectedBivouac,
    val number: Int,
    val total: Int,
)

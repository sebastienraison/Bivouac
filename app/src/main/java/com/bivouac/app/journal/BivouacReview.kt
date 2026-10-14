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
 * Les réponses sont ramenées par [outcome] à ce qu'il faut écrire : les bivouacs à découper, par
 * rang de jour, et la version du crible à poser sur la rando. Écrire est l'affaire de l'appelant
 * (LoggedTrackRepository.applyBivouacReview), qui appelle LoggedTrackRepository.splitDay une fois
 * par jour : un jour à deux nuits découpées aux deux reçoit ses deux bivouacs dans le même appel,
 * ce que splitDay exige (ses index sont ceux des points du jour d'avant tout découpage).
 *
 * « Ne pas découper » ne produit rien : les durées d'un jour gardé entier sont déjà sans la nuit
 * (lot 2, DayBivouacs). Une question laissée sans réponse ([outcome] reçoit `null`, l'utilisateur a
 * quitté le dialogue autrement que par un bouton) vaut « Ne pas découper ».
 */
object BivouacReview {

    enum class Answer { SPLIT, KEEP }

    /**
     * Une question : le [bivouac] détecté dans le jour de rang [dayPosition] (rang dans la liste
     * des jours passée à [questions], 0 pour le premier), en index des points de ce jour.
     */
    data class Question(val dayPosition: Int, val bivouac: DetectedBivouac)

    /**
     * Ce qu'il faut écrire une fois toutes les questions répondues : [splits], les bivouacs à
     * découper par rang de jour (absent : jour gardé tel quel), et [reviewVersion], la version du
     * crible à poser sur la rando, dans tous les cas.
     */
    data class Outcome(val splits: Map<Int, List<DetectedBivouac>>, val reviewVersion: Int)

    /** Ce que l'action de menu doit faire d'une rando. */
    sealed interface MenuResult {
        /** Aucun bivouac, ni à l'intérieur d'un jour ni entre deux jours : `bivouac_detect_none`. */
        data object NoneDetected : MenuResult

        /** Des bivouacs, mais tous entre deux jours (déjà des fins de journée) : `bivouac_detect_all_split`. */
        data object AllAlreadySplit : MenuResult

        /** Au moins une nuit à l'intérieur d'un jour : les questions, comme à l'import. */
        data class Ask(val questions: List<Question>) : MenuResult
    }

    /** Bivouacs détectés à l'intérieur de chacun des jours de [dayPoints], dans l'ordre des jours. */
    fun detectDays(
        dayPoints: List<List<TrackPoint>>,
        zoneFor: (TrackPoint) -> ZoneId = LocalZone::forPoint,
    ): List<List<DetectedBivouac>> = dayPoints.map { DayBivouacs.detect(it, zoneFor) }

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
     */
    fun menuResult(
        dayPoints: List<List<TrackPoint>>,
        zoneFor: (TrackPoint) -> ZoneId = LocalZone::forPoint,
    ): MenuResult {
        val internal = questions(detectDays(dayPoints, zoneFor))
        if (internal.isNotEmpty()) return MenuResult.Ask(internal)
        val junctionIsNight = dayPoints.filter { it.isNotEmpty() }.zipWithNext().any { (day, next) ->
            BivouacDetector.detect(listOf(day.last(), next.first()), zoneFor).isNotEmpty()
        }
        return if (junctionIsNight) MenuResult.AllAlreadySplit else MenuResult.NoneDetected
    }

    /**
     * Les réponses [answers] (une par question de [questions], dans le même ordre ; `null` vaut
     * [Answer.KEEP]) ramenées à ce qu'il faut écrire. Sans question, aucun découpage, mais la
     * version du crible est posée quand même : sans elle, la détection serait refaite à chaque
     * lancement.
     */
    fun outcome(questions: List<Question>, answers: List<Answer?>): Outcome {
        // i18n-ok : message d'une exception de programmation, jamais affiché (RIC-227).
        require(answers.size == questions.size) { "${answers.size} réponse(s) pour ${questions.size} question(s)" }
        val splits = questions.zip(answers)
            .filter { (_, answer) -> answer == Answer.SPLIT }
            .groupBy({ (question, _) -> question.dayPosition }, { (question, _) -> question.bivouac })
        return Outcome(splits, BIVOUAC_REVIEW_VERSION)
    }
}

/**
 * RIC-227 : une question du crible telle que l'écran l'affiche (voir BivouacDetectedDialog).
 * [trackName] non nul au crible du premier lancement (titre « Bivouac détecté dans « %1$s » »),
 * nul à l'import et dans l'action de menu. [number] (à partir de 1) sur [total] : le compteur,
 * affiché seulement quand [total] dépasse 1.
 */
data class BivouacQuestion(
    val trackName: String?,
    val bivouac: DetectedBivouac,
    val number: Int,
    val total: Int,
)

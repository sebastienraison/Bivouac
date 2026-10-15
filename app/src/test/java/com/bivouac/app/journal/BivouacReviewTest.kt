package com.bivouac.app.journal

import com.bivouac.app.data.gpx.BIVOUAC_REVIEW_VERSION
import com.bivouac.app.data.model.TrackPoint
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// RIC-227 : logique de décision du crible des bivouacs (import, premier lancement, action de menu),
// sans Compose ni base. Le fuseau est imposé par zoneFor : rien ne dépend de la bibliothèque de
// fuseaux ni du fuseau de la machine.
class BivouacReviewTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val zoneFor: (TrackPoint) -> ZoneId = { paris }

    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(paris).toInstant()

    private fun point(local: String?, north: Int = 0) =
        TrackPoint(latitude = 42.5 + north * 1e-4, longitude = 2.0, elevationMeters = 1_500.0, time = local?.let(::at))

    /**
     * Trois jours de marche dans un seul fichier, points espacés de moins de 4 h dans la journée :
     * deux nuits sans enregistrement, arrêts aux points 4 (20 h 00) et 9 (19 h 00).
     */
    private val twoNights = listOf(
        point("2026-08-12T09:00:00", 0),
        point("2026-08-12T11:30:00", 1),
        point("2026-08-12T14:00:00", 2),
        point("2026-08-12T17:00:00", 3),
        point("2026-08-12T20:00:00", 4),
        point("2026-08-13T07:15:00", 5),
        point("2026-08-13T10:00:00", 6),
        point("2026-08-13T13:00:00", 7),
        point("2026-08-13T16:00:00", 8),
        point("2026-08-13T19:00:00", 9),
        point("2026-08-14T06:30:00", 10),
        point("2026-08-14T09:00:00", 11),
        point("2026-08-14T11:00:00", 12),
    )

    private val noNight = listOf(
        point("2026-08-12T09:00:00", 0),
        point("2026-08-12T11:00:00", 1),
        point("2026-08-12T16:00:00", 2),
    )

    @Test
    fun `import d'un fichier a deux nuits, reponses mixtes`() {
        val questions = BivouacReview.questions(BivouacReview.detectDays(listOf(twoNights), zoneFor))
        assertEquals(2, questions.size)
        assertEquals(listOf(4, 9), questions.map { it.bivouac.stopIndex })
        assertEquals(listOf(0, 0), questions.map { it.dayPosition })
        assertEquals(at("2026-08-12T20:00:00"), questions[0].bivouac.stopAt)
        assertEquals(at("2026-08-13T07:15:00"), questions[0].bivouac.resumeAt)

        val outcome = BivouacReview.outcome(questions, listOf(BivouacReview.Answer.SPLIT, BivouacReview.Answer.PAUSE))
        assertEquals(setOf(0), outcome.splits.keys)
        assertEquals(listOf(4), outcome.splits.getValue(0).map { it.stopIndex })
        assertEquals(BIVOUAC_REVIEW_VERSION, outcome.reviewVersion)

        val both = BivouacReview.outcome(questions, listOf(BivouacReview.Answer.SPLIT, BivouacReview.Answer.SPLIT))
        assertEquals(listOf(4, 9), both.splits.getValue(0).map { it.stopIndex })

        // Quitter le dialogue autrement qu'un bouton (null) vaut « Pause ».
        val dismissed = BivouacReview.outcome(questions, listOf(null, BivouacReview.Answer.SPLIT))
        assertEquals(listOf(9), dismissed.splits.getValue(0).map { it.stopIndex })
    }

    @Test
    fun `import de deux fichiers, questions fichier par fichier puis chronologiques`() {
        val questions = BivouacReview.questions(BivouacReview.detectDays(listOf(noNight, twoNights), zoneFor))
        assertEquals(listOf(1, 1), questions.map { it.dayPosition })
        val outcome = BivouacReview.outcome(questions, listOf(BivouacReview.Answer.PAUSE, BivouacReview.Answer.SPLIT))
        assertEquals(mapOf(1 to listOf(9)), outcome.splits.mapValues { (_, b) -> b.map { it.stopIndex } })
    }

    @Test
    fun `fichier sans nuit, aucune question, rando marquee examinee`() {
        val questions = BivouacReview.questions(BivouacReview.detectDays(listOf(noNight), zoneFor))
        assertTrue(questions.isEmpty())
        val outcome = BivouacReview.outcome(questions, emptyList())
        assertTrue(outcome.splits.isEmpty())
        assertEquals(BIVOUAC_REVIEW_VERSION, outcome.reviewVersion)
    }

    @Test
    fun `crible d'une rando deja examinee, rien, pas meme une detection`() {
        var detected = false
        val questions = BivouacReview.startupQuestions(BIVOUAC_REVIEW_VERSION) {
            detected = true
            BivouacReview.detectDays(listOf(twoNights), zoneFor)
        }
        assertNull(questions)
        assertTrue(!detected)
        assertTrue(!BivouacReview.needsStartupReview(BIVOUAC_REVIEW_VERSION))
        assertTrue(BivouacReview.needsStartupReview(0))
    }

    @Test
    fun `crible d'une rando jamais examinee, ses questions`() {
        val questions = BivouacReview.startupQuestions(0) { BivouacReview.detectDays(listOf(twoNights), zoneFor) }
        assertEquals(2, questions?.size)
    }

    @Test
    fun `rando sans horodatage, aucune question, marquee examinee`() {
        val untimed = listOf(point(null, 0), point(null, 1), point(null, 2))
        val questions = BivouacReview.startupQuestions(0) { BivouacReview.detectDays(listOf(untimed, untimed), zoneFor) }
        assertEquals(emptyList<BivouacReview.Question>(), questions)
        val outcome = BivouacReview.outcome(questions!!, emptyList())
        assertTrue(outcome.splits.isEmpty())
        assertEquals(BIVOUAC_REVIEW_VERSION, outcome.reviewVersion)
        assertEquals(BivouacReview.MenuResult.NoneDetected, BivouacReview.menuResult(listOf(untimed, untimed), zoneFor))
    }

    @Test
    fun `action de menu sur une rando entierement decoupee`() {
        // Le fichier à deux nuits, découpé à ses deux bivouacs : trois jours, plus aucune nuit dedans.
        val days = listOf(twoNights.subList(0, 5), twoNights.subList(5, 10), twoNights.subList(10, 13))
        assertEquals(BivouacReview.MenuResult.AllAlreadySplit, BivouacReview.menuResult(days, zoneFor))
    }

    @Test
    fun `action de menu, une nuit encore a l'interieur d'un jour`() {
        val days = listOf(twoNights.subList(0, 5), twoNights.subList(5, 13))
        val result = BivouacReview.menuResult(days, zoneFor)
        assertTrue(result is BivouacReview.MenuResult.Ask)
        val questions = (result as BivouacReview.MenuResult.Ask).questions
        assertEquals(listOf(1), questions.map { it.dayPosition })
        // Index dans les points DU JOUR, comme splitDay les attend.
        assertEquals(listOf(4), questions.map { it.bivouac.stopIndex })
    }

    @Test
    fun `action de menu, aucun bivouac du tout`() {
        assertEquals(BivouacReview.MenuResult.NoneDetected, BivouacReview.menuResult(listOf(noNight), zoneFor))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `autant de reponses que de questions`() {
        val questions = BivouacReview.questions(BivouacReview.detectDays(listOf(twoNights), zoneFor))
        BivouacReview.outcome(questions, listOf(BivouacReview.Answer.SPLIT))
    }

    // --- Plusieurs randos à la suite (crible du lancement, sorties séparées) ---------------------

    private fun track(key: String, vararg days: List<TrackPoint>) =
        BivouacReviewBatch.Track(key, "Rando $key", BivouacReview.questions(BivouacReview.detectDays(days.toList(), zoneFor)))

    @Test
    fun `plusieurs randos, ordre des randos puis des nuits, compteur global, randos sans question ecartees`() {
        val batch = BivouacReviewBatch(listOf(track("a", twoNights), track("vide", noNight), track("b", noNight, twoNights)))
        assertEquals(listOf("a", "b"), batch.tracks.map { it.key })
        assertEquals(4, batch.total)
        val shown = (0 until batch.total).map { batch.questionAt(it) }
        assertEquals(listOf("Rando a", "Rando a", "Rando b", "Rando b"), shown.map { it.trackName })
        assertEquals(listOf(1, 2, 3, 4), shown.map { it.number })
        assertTrue(shown.all { it.total == 4 })
        assertEquals(listOf(4, 9, 4, 9), shown.map { it.bivouac.stopIndex })
    }

    @Test
    fun `plusieurs randos, chaque rando decoupee des sa derniere question avec ses seules reponses`() {
        val batch = BivouacReviewBatch(listOf(track("a", twoNights), track("b", noNight, twoNights)))
        val answers = mutableListOf<BivouacReview.Answer>()

        answers += BivouacReview.Answer.PAUSE
        assertNull("la rando a a encore une question", batch.completedBy(answers))

        answers += BivouacReview.Answer.SPLIT
        val a = batch.completedBy(answers)!!
        assertEquals("a", a.track.key)
        assertEquals(mapOf(0 to listOf(9)), a.outcome.splits.mapValues { (_, b) -> b.map { it.stopIndex } })
        assertEquals(BIVOUAC_REVIEW_VERSION, a.outcome.reviewVersion)

        answers += BivouacReview.Answer.SPLIT
        assertNull(batch.completedBy(answers))
        answers += BivouacReview.Answer.PAUSE
        val b = batch.completedBy(answers)!!
        assertEquals("b", b.track.key)
        // Rang du jour dans la rando b (le fichier à deux nuits est son second jour).
        assertEquals(mapOf(1 to listOf(4)), b.outcome.splits.mapValues { (_, bivouacs) -> bivouacs.map { it.stopIndex } })
    }

    @Test
    fun `plusieurs randos, toutes gardees, versions posees sans decoupage`() {
        val batch = BivouacReviewBatch(listOf(track("a", twoNights)))
        val done = batch.completedBy(listOf(BivouacReview.Answer.PAUSE, BivouacReview.Answer.PAUSE))!!
        assertTrue(done.outcome.splits.isEmpty())
        assertEquals(BIVOUAC_REVIEW_VERSION, done.outcome.reviewVersion)
        assertEquals(0, BivouacReviewBatch(listOf(track("vide", noNight))).total)
    }
}

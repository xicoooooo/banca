package com.banca.agents

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The blackjack drill: which hands it deals, and how it grades each way of playing them. */
class BlackjackDrillsTest {

    private val drills = BlackjackDrills()

    /** The best play for two cards against a dealer's, all given as card points with an ace as one. */
    private fun best(first: Int, second: Int, dealer: Int) = drills.question(first, second, dealer, Random(1)).best

    @Test
    fun `a drill is ten different hands, each given a few seconds`() {
        val drill = drills.deal(Random(7))

        assertEquals(10, drill.questions.size)
        assertEquals(10, drill.questions.map { Triple(it.cards.map { card -> card[0] }.sorted(), it.dealer[0], it.total) }.toSet().size)
        assertTrue(drill.secondsEach in 5..15)
    }

    @Test
    fun `every hand has one best play, which costs nothing, and its reason`() {
        repeat(30) { seed ->
            for (question in drills.deal(Random(seed)).questions) {
                val best = question.plays.single { it.action == question.best }
                assertEquals("best", best.verdict)
                assertEquals(0.0, best.cost)
                assertTrue(question.plays.filter { it.verdict != "best" }.all { it.cost > 0 }, "a play that is not the best gives something up: $question")
                assertTrue(question.reason.isNotBlank())
            }
        }
    }

    @Test
    fun `the hands are the ones worth practising, and never one already won or lost`() {
        repeat(30) { seed ->
            for (question in drills.deal(Random(seed)).questions) {
                assertEquals(2, question.cards.size)
                assertTrue(question.total in 4..20, "no naturals, and nothing bust: $question")
                val pair = question.plays.any { it.action == "split" }
                assertTrue(pair || question.soft || question.total in 9..16, "a hard hand is a stiff one or one to double on: $question")
            }
        }
    }

    @Test
    fun `a drill mixes stiff hands, doubling hands, soft hands and pairs`() {
        val questions = (0 until 20).flatMap { drills.deal(Random(it)).questions }
        val pairs = questions.count { question -> question.plays.any { it.action == "split" } }
        val soft = questions.count { question -> question.soft && question.plays.none { it.action == "split" } }

        assertTrue(pairs in 20..60, "$pairs pairs in 200 hands")
        assertTrue(soft in 20..60, "$soft soft hands in 200")
        assertTrue(questions.size - pairs - soft > 80, "and most of the rest hard")
    }

    @Test
    fun `splitting is offered for a pair and for nothing else`() {
        assertTrue(drills.question(8, 8, 10, Random(1)).plays.any { it.action == "split" })
        assertFalse(drills.question(10, 6, 10, Random(1)).plays.any { it.action == "split" })
        assertEquals(listOf("hit", "stand", "double"), drills.question(10, 6, 10, Random(1)).plays.map { it.action })
    }

    @Test
    fun `it grades by the book`() {
        assertEquals("hit", best(10, 6, 10), "sixteen against a ten")
        assertEquals("stand", best(10, 6, 6), "sixteen against a six")
        assertEquals("stand", best(10, 2, 4), "twelve against a four")
        assertEquals("hit", best(10, 2, 2), "twelve against a two")
        assertEquals("double", best(6, 5, 6), "eleven against a six")
        assertEquals("hit", best(5, 4, 10), "nine against a ten is not one to double")
        assertEquals("split", best(8, 8, 10), "eights are always split")
        assertEquals("split", best(1, 1, 10), "and so are aces")
        assertEquals("stand", best(10, 10, 6), "tens never are")
        assertEquals("hit", best(1, 7, 9), "soft eighteen against a nine")
        assertEquals("double", best(1, 6, 5), "soft seventeen against a five")
    }

    @Test
    fun `a costly mistake is called one, and what it costs is counted on a hundred staked`() {
        val tens = drills.question(10, 10, 6, Random(1))
        val split = tens.plays.single { it.action == "split" }

        assertEquals("mistake", split.verdict)
        assertTrue(split.cost > 5, "splitting tens against a six gives up ${split.cost} on a hundred")
    }

    @Test
    fun `the cards shown are worth what the hand says`() {
        val question = drills.question(1, 7, 10, Random(3))

        assertEquals('A', question.cards[0][0])
        assertEquals('7', question.cards[1][0])
        assertEquals(18, question.total)
        assertTrue(question.soft)
        assertTrue(question.dealer[0] in "TJQK")
    }
}

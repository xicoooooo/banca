package com.banca.games.poker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HandEvaluatorTest {

    private fun rank(vararg cards: String): HandRank = HandEvaluator.evaluate(Card.allOf(*cards))

    private fun categoryOf(vararg cards: String): HandCategory = rank(*cards).category

    @Test
    fun `recognises every category`() {
        assertEquals(HandCategory.STRAIGHT_FLUSH, categoryOf("9h", "8h", "7h", "6h", "5h"))
        assertEquals(HandCategory.FOUR_OF_A_KIND, categoryOf("9h", "9s", "9d", "9c", "5h"))
        assertEquals(HandCategory.FULL_HOUSE, categoryOf("9h", "9s", "9d", "5c", "5h"))
        assertEquals(HandCategory.FLUSH, categoryOf("Ah", "Jh", "9h", "6h", "2h"))
        assertEquals(HandCategory.STRAIGHT, categoryOf("9h", "8s", "7h", "6h", "5h"))
        assertEquals(HandCategory.THREE_OF_A_KIND, categoryOf("9h", "9s", "9d", "6c", "2h"))
        assertEquals(HandCategory.TWO_PAIR, categoryOf("9h", "9s", "6d", "6c", "2h"))
        assertEquals(HandCategory.PAIR, categoryOf("9h", "9s", "Kd", "6c", "2h"))
        assertEquals(HandCategory.HIGH_CARD, categoryOf("Ah", "Js", "9d", "6c", "2h"))
    }

    @Test
    fun `ace plays low in the wheel and the straight counts as five high`() {
        val wheel = rank("Ah", "5s", "4d", "3c", "2h")
        assertEquals(HandCategory.STRAIGHT, wheel.category)
        assertEquals(listOf(5), wheel.tiebreakers)

        // The wheel is the weakest straight, so a six high beats it.
        assertTrue(rank("6h", "5s", "4d", "3c", "2h") > wheel)
    }

    @Test
    fun `steel wheel is a straight flush, not merely a flush`() {
        val steelWheel = rank("Ah", "5h", "4h", "3h", "2h")
        assertEquals(HandCategory.STRAIGHT_FLUSH, steelWheel.category)
        assertEquals(listOf(5), steelWheel.tiebreakers)
        assertTrue(rank("Kh", "Qh", "Jh", "Th", "9h") > steelWheel)
    }

    @Test
    fun `ace king queen jack ten is not a wheel wrap around`() {
        // Q-K-A-2-3 must not be read as a straight.
        assertEquals(HandCategory.HIGH_CARD, categoryOf("Qh", "Ks", "Ad", "2c", "3h"))
    }

    @Test
    fun `categories beat each other in the right order`() {
        val ascending = listOf(
            rank("Ah", "Js", "9d", "6c", "2h"),  // high card
            rank("2h", "2s", "9d", "6c", "Ah"),  // pair
            rank("2h", "2s", "6d", "6c", "Ah"),  // two pair
            rank("2h", "2s", "2d", "6c", "Ah"),  // trips
            rank("9h", "8s", "7d", "6c", "5h"),  // straight
            rank("Ah", "Jh", "9h", "6h", "2h"),  // flush
            rank("2h", "2s", "2d", "6c", "6h"),  // full house
            rank("2h", "2s", "2d", "2c", "6h"),  // quads
            rank("9h", "8h", "7h", "6h", "5h"),  // straight flush
        )

        ascending.zipWithNext { weaker, stronger ->
            assertTrue(stronger > weaker, "$stronger should beat $weaker")
        }
    }

    @Test
    fun `kickers decide hands in the same category`() {
        assertTrue(rank("9h", "9s", "Kd", "6c", "2h") > rank("9h", "9s", "Qd", "6c", "2h"))
        assertTrue(rank("9h", "9s", "8d", "8c", "Ah") > rank("9h", "9s", "8d", "8c", "Kh"))
        assertTrue(rank("Ah", "Qh", "9h", "6h", "2h") > rank("Ah", "Jh", "9h", "6h", "2h"))
    }

    @Test
    fun `two pair is decided by the higher pair first`() {
        // Aces and twos beats kings and queens.
        assertTrue(rank("Ah", "As", "2d", "2c", "5h") > rank("Kh", "Ks", "Qd", "Qc", "5h"))
    }

    @Test
    fun `full house is decided by the trips first`() {
        assertTrue(rank("9h", "9s", "9d", "2c", "2h") > rank("8h", "8s", "8d", "Ac", "Ah"))
    }

    @Test
    fun `identical hands of different suits tie`() {
        assertEquals(0, rank("9h", "9s", "Kd", "6c", "2h").compareTo(rank("9d", "9c", "Kh", "6s", "2s")))
    }

    @Test
    fun `seven cards are scored as the best five`() {
        // Board plus hole cards containing a flush and a lesser straight.
        val hand = rank("Ah", "Kh", "Qh", "Jh", "Th", "2c", "3d")
        assertEquals(HandCategory.STRAIGHT_FLUSH, hand.category)
        assertEquals(listOf(14), hand.tiebreakers)
    }

    @Test
    fun `seven cards ignore cards that weaken the hand`() {
        val quads = rank("9h", "9s", "9d", "9c", "2h", "3d", "4s")
        assertEquals(HandCategory.FOUR_OF_A_KIND, quads.category)
        assertEquals(listOf(9, 4), quads.tiebreakers, "the kicker should be the best remaining card")
    }

    @Test
    fun `six cards are accepted`() {
        assertEquals(HandCategory.FLUSH, categoryOf("Ah", "Jh", "9h", "6h", "2h", "3d"))
    }

    @Test
    fun `a flush of six suited cards keeps the five highest`() {
        val flush = rank("Ah", "Kh", "Qh", "2h", "3h", "4h", "9s")
        assertEquals(HandCategory.FLUSH, flush.category)
        assertEquals(listOf(14, 13, 12, 4, 3), flush.tiebreakers)
    }

    @Test
    fun `rejects the wrong number of cards`() {
        assertFailsWith<IllegalArgumentException> { rank("Ah", "Kh", "Qh", "Jh") }
        assertFailsWith<IllegalArgumentException> { rank("Ah", "Kh", "Qh", "Jh", "Th", "9h", "8h", "7h") }
    }

    @Test
    fun `rejects duplicate cards`() {
        assertFailsWith<IllegalArgumentException> { rank("Ah", "Ah", "Qh", "Jh", "Th") }
    }
}

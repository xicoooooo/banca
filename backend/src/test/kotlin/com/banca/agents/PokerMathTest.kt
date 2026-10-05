package com.banca.agents

import com.banca.games.cards.Card
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PokerMathTest {

    private fun equity(hole: String, board: String = "", opponents: Int = 1): Double =
        PokerMath.equity(
            hole = hole.split(" ").map(Card::of),
            board = board.split(" ").filter { it.isNotEmpty() }.map(Card::of),
            opponents = opponents,
            iterations = 6_000,
            random = Random(42),
        )

    @Test
    fun `pocket aces win about eighty five percent against one random hand`() {
        assertEquals(0.85, equity("Ah Ad"), absoluteTolerance = 0.03)
    }

    @Test
    fun `seven deuce offsuit is an underdog`() {
        assertEquals(0.35, equity("7h 2c"), absoluteTolerance = 0.04)
    }

    @Test
    fun `the unbeatable hand on the river always wins`() {
        assertEquals(1.0, equity("Ah Kh", board = "Qh Jh Th 2c 3d"))
    }

    @Test
    fun `playing the board on the river is an even split at best`() {
        // The board is a royal flush, so every hand ties.
        assertEquals(0.5, equity("2c 3d", board = "Ah Kh Qh Jh Th"))
    }

    @Test
    fun `more opponents means less equity`() {
        assertTrue(equity("Ah Ad", opponents = 4) < equity("Ah Ad", opponents = 1))
    }

    @Test
    fun `equity is always a share between nothing and everything`() {
        val value = equity("9s 8s", board = "7s 6d 2h")
        assertTrue(value in 0.0..1.0)
    }

    @Test
    fun `pot odds are the call as a share of the pot after calling`() {
        assertEquals(0.25, PokerMath.potOdds(pot = 150, callCost = 50))
        assertEquals(0.5, PokerMath.potOdds(pot = 100, callCost = 100))
    }

    @Test
    fun `nothing to call means no price to pay`() {
        assertEquals(0.0, PokerMath.potOdds(pot = 200, callCost = 0))
    }

    @Test
    fun `equity refuses malformed input`() {
        assertFailsWith<IllegalArgumentException> {
            PokerMath.equity(listOf(Card.of("Ah")), emptyList(), 1, 10, Random(1))
        }
        assertFailsWith<IllegalArgumentException> {
            PokerMath.equity(Card.allOf("Ah", "Ad"), emptyList(), 0, 10, Random(1))
        }
    }
}

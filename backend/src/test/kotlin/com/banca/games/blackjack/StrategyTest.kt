package com.banca.games.blackjack

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The strategy is worked out from the rules, so it is checked against the
 * published basic strategy for the same rules: six decks, dealer stands on
 * every seventeen, doubling after a split allowed.
 */
class StrategyTest {

    private val strategy = Strategy()

    private fun best(total: Int, dealerUp: Int, soft: Boolean = false, pairOf: Int? = null, twoCards: Boolean = true) =
        strategy.evaluate(
            Decision(total, soft, pairOf, dealerUp, canDouble = twoCards, canSplit = pairOf != null),
        ).first().action

    private fun assertClose(expected: Double, actual: Double, within: Double = 0.01) =
        assertTrue(abs(expected - actual) <= within, "expected about $expected but was $actual")

    @Test
    fun `the dealer's outcomes are chances that sum to one`() {
        for (up in 1..10) {
            val outcomes = strategy.dealerOutcomes(up)
            assertClose(1.0, outcomes.bust + outcomes.totals.values.sum(), within = 1e-9)
            assertEquals(setOf(17, 18, 19, 20, 21), outcomes.totals.keys, "a dealer who stands has seventeen to twenty-one")
        }
    }

    @Test
    fun `a dealer showing a small card busts far more often than one showing a ten`() {
        assertClose(0.42, strategy.dealerOutcomes(6).bust)
        assertClose(0.35, strategy.dealerOutcomes(2).bust)
        assertClose(0.26, strategy.dealerOutcomes(7).bust)
        assertClose(0.23, strategy.dealerOutcomes(10).bust)
    }

    @Test
    fun `a hand's chance of busting on the next card`() {
        assertEquals(0.0, strategy.bustChance(11, soft = false))
        assertClose(4.0 / 13, strategy.bustChance(12, soft = false), within = 1e-9)
        assertClose(8.0 / 13, strategy.bustChance(16, soft = false), within = 1e-9)
        assertClose(12.0 / 13, strategy.bustChance(20, soft = false), within = 1e-9)
        assertEquals(0.0, strategy.bustChance(18, soft = true), "a soft hand cannot bust on one card")
    }

    @Test
    fun `standing on twenty against a ten is worth a little over half a bet`() {
        val stand = strategy.evaluate(Decision(20, false, 10, 10, canDouble = true, canSplit = true))
            .first { it.action == BlackjackAction.Stand }
        assertClose(0.55, stand.value, within = 0.02)
    }

    @Test
    fun `hard totals are played by the book`() {
        assertEquals(BlackjackAction.Hit, best(16, dealerUp = 10))
        assertEquals(BlackjackAction.Stand, best(16, dealerUp = 6))
        assertEquals(BlackjackAction.Hit, best(12, dealerUp = 2))
        assertEquals(BlackjackAction.Hit, best(12, dealerUp = 3))
        assertEquals(BlackjackAction.Stand, best(12, dealerUp = 4))
        assertEquals(BlackjackAction.Stand, best(13, dealerUp = 2))
        assertEquals(BlackjackAction.Hit, best(15, dealerUp = 7))
        assertEquals(BlackjackAction.Stand, best(17, dealerUp = 10))
        assertEquals(BlackjackAction.Hit, best(8, dealerUp = 6))
    }

    @Test
    fun `doubling is advised where the book doubles, and only with two cards`() {
        assertEquals(BlackjackAction.Double, best(11, dealerUp = 10))
        assertEquals(BlackjackAction.Double, best(11, dealerUp = 6))
        assertEquals(BlackjackAction.Double, best(10, dealerUp = 9))
        assertEquals(BlackjackAction.Hit, best(10, dealerUp = 10))
        assertEquals(BlackjackAction.Double, best(9, dealerUp = 3))
        assertEquals(BlackjackAction.Hit, best(9, dealerUp = 2))
        assertEquals(BlackjackAction.Hit, best(9, dealerUp = 7))

        assertEquals(BlackjackAction.Hit, best(11, dealerUp = 6, twoCards = false), "three cards cannot be doubled")
    }

    @Test
    fun `soft hands are played by the book`() {
        assertEquals(BlackjackAction.Hit, best(18, dealerUp = 9, soft = true))
        assertEquals(BlackjackAction.Hit, best(18, dealerUp = 10, soft = true))
        assertEquals(BlackjackAction.Stand, best(18, dealerUp = 7, soft = true))
        assertEquals(BlackjackAction.Stand, best(18, dealerUp = 8, soft = true))
        assertEquals(BlackjackAction.Double, best(18, dealerUp = 3, soft = true))
        assertEquals(BlackjackAction.Double, best(17, dealerUp = 3, soft = true))
        assertEquals(BlackjackAction.Hit, best(17, dealerUp = 7, soft = true))
        assertEquals(BlackjackAction.Double, best(13, dealerUp = 6, soft = true))
        assertEquals(BlackjackAction.Hit, best(13, dealerUp = 4, soft = true))
        assertEquals(BlackjackAction.Stand, best(19, dealerUp = 6, soft = true))
        assertEquals(BlackjackAction.Stand, best(20, dealerUp = 6, soft = true))
    }

    @Test
    fun `pairs are played by the book`() {
        assertEquals(BlackjackAction.Split, best(16, dealerUp = 10, pairOf = 8))
        assertEquals(BlackjackAction.Split, best(12, dealerUp = 10, soft = true, pairOf = 1), "aces")
        assertEquals(BlackjackAction.Stand, best(20, dealerUp = 6, pairOf = 10), "never split tens")
        assertEquals(BlackjackAction.Double, best(10, dealerUp = 6, pairOf = 5), "fives are a ten, not a pair")
        assertEquals(BlackjackAction.Stand, best(18, dealerUp = 7, pairOf = 9))
        assertEquals(BlackjackAction.Split, best(18, dealerUp = 8, pairOf = 9))
        assertEquals(BlackjackAction.Stand, best(18, dealerUp = 10, pairOf = 9))
        assertEquals(BlackjackAction.Split, best(14, dealerUp = 7, pairOf = 7))
        assertEquals(BlackjackAction.Hit, best(14, dealerUp = 8, pairOf = 7))
        assertEquals(BlackjackAction.Split, best(12, dealerUp = 6, pairOf = 6))
        assertEquals(BlackjackAction.Hit, best(12, dealerUp = 7, pairOf = 6))
        // Worth splitting only because the split hands may be doubled.
        assertEquals(BlackjackAction.Split, best(8, dealerUp = 5, pairOf = 4))
        assertEquals(BlackjackAction.Hit, best(8, dealerUp = 4, pairOf = 4))
        assertEquals(BlackjackAction.Split, best(4, dealerUp = 2, pairOf = 2))
        assertEquals(BlackjackAction.Split, best(6, dealerUp = 7, pairOf = 3))
        assertEquals(BlackjackAction.Hit, best(6, dealerUp = 8, pairOf = 3))
    }

    @Test
    fun `where this differs from the book, the two plays are worth almost the same`() {
        // The book doubles ace-two against a five; drawing from an endless shoe
        // makes hitting better by a hair. Neither is a mistake.
        val values = strategy.evaluate(Decision(13, true, null, 5, canDouble = true, canSplit = false))
        val hit = values.first { it.action == BlackjackAction.Hit }.value
        val double = values.first { it.action == BlackjackAction.Double }.value
        assertClose(hit, double, within = 0.01)
    }

    @Test
    fun `a rule change moves the advice with it`() {
        val standsOnSoft17 = Strategy(Rules(dealerHitsSoft17 = false))
        val hitsSoft17 = Strategy(Rules(dealerHitsSoft17 = true))
        fun softNineteenAgainstSix(strategy: Strategy) =
            strategy.evaluate(Decision(19, true, null, 6, canDouble = true, canSplit = false)).first().action

        assertEquals(BlackjackAction.Stand, softNineteenAgainstSix(standsOnSoft17))
        assertEquals(BlackjackAction.Double, softNineteenAgainstSix(hitsSoft17), "the book's one soft nineteen double")
    }

    @Test
    fun `insurance is never the better choice`() {
        val (best, worse) = strategy.insurance()
        assertEquals(BlackjackAction.DeclineInsurance, best.action)
        assertEquals(0.0, best.value)
        assertEquals(BlackjackAction.Insure, worse.action)
        assertClose(-1.0 / 26, worse.value, within = 1e-9)
    }

    @Test
    fun `only the actions that are open are valued, the best first`() {
        val values = strategy.evaluate(Decision(16, false, 8, 10, canDouble = false, canSplit = false))
        assertEquals(setOf(BlackjackAction.Hit, BlackjackAction.Stand), values.map { it.action }.toSet())
        assertTrue(values[0].value >= values[1].value)
    }
}

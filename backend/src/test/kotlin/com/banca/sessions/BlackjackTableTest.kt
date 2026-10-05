package com.banca.sessions

import com.banca.games.blackjack.BlackjackAction
import com.banca.games.blackjack.Rules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlackjackTableTest {

    private fun table(seed: Int = 1, startingStack: Long = 2_000, minBet: Long = 10) = BlackjackTable(
        startingStack = startingStack,
        minBet = minBet,
        maxBet = 500,
        rules = Rules(),
        random = Random(seed),
    )

    /** A table dealt into a round the player actually has to play. */
    private fun tableInPlay(): BlackjackTable =
        (1..200).firstNotNullOf { seed -> table(seed).also { it.bet(100) }.takeIf { it.view().phase == "player" } }

    /** Answers whatever is asked in the plainest way until the round is settled. */
    private fun BlackjackTable.playOut() {
        while (!isBetting) act(if (view().phase == "insurance") BlackjackAction.DeclineInsurance else BlackjackAction.Stand)
    }

    @Test
    fun `a new table is waiting for a bet and offers nothing else`() {
        val view = table().view()

        assertEquals("betting", view.phase)
        assertEquals(2_000, view.stack)
        assertNull(view.dealer)
        assertTrue(view.hands.isEmpty())
        assertTrue(view.legal.bet)
        assertFalse(view.legal.hit || view.legal.stand || view.legal.double || view.legal.split || view.legal.insurance)
    }

    @Test
    fun `a bet outside the limits or beyond the stack is refused`() {
        assertFailsWith<IllegalArgumentException> { table().bet(5) }
        assertFailsWith<IllegalArgumentException> { table().bet(501) }
        assertFailsWith<IllegalArgumentException> { table(startingStack = 50).bet(100) }
    }

    @Test
    fun `nothing can be played before a bet, and no bet placed mid-round`() {
        assertFailsWith<IllegalStateException> { table().act(BlackjackAction.Hit) }
        assertFailsWith<IllegalStateException> { tableInPlay().bet(100) }
    }

    @Test
    fun `the dealer's hole card is hidden while the round is played`() {
        val view = tableInPlay().view()
        val dealer = assertNotNull(view.dealer)

        assertEquals(2, dealer.cards.size)
        assertNotNull(dealer.cards[0])
        assertNull(dealer.cards[1], "the hole card must not be sent")
        assertTrue(dealer.total <= 11, "the total shown counts the up card alone")
        assertEquals(1_900, view.stack)
        assertEquals("playing", view.hands.single().status)
        assertEquals(0, view.activeHand)
        assertFalse(view.legal.bet)
    }

    @Test
    fun `settling shows the dealer's whole hand and the outcome`() {
        val table = tableInPlay()
        table.act(BlackjackAction.Stand)
        val view = table.view()

        assertEquals("settled", view.phase)
        assertTrue(assertNotNull(view.dealer).cards.all { it != null })
        val hand = view.hands.single()
        assertNotNull(hand.outcome)
        assertEquals(1_900 + assertNotNull(hand.returned), view.stack)
        assertEquals(view.stack - 2_000, assertNotNull(view.result).net)
        assertTrue(view.legal.bet, "ready for the next bet")
        assertNull(view.activeHand)
    }

    @Test
    fun `chips and the last bet carry over to the next round`() {
        val table = tableInPlay()
        table.act(BlackjackAction.Stand)
        val after = table.view().stack

        table.bet(50)
        val next = table.view()

        assertEquals(2, next.roundNumber)
        assertEquals(50, next.lastBet)
        // A natural settles at once, so only the stake's whereabouts is certain.
        if (next.phase != "settled") assertEquals(after - 50, next.stack)
    }

    @Test
    fun `a player who cannot cover the smallest bet is staked again`() {
        // One bet's worth of chips, so the first loss leaves nothing.
        val refilled = (1..200).firstNotNullOf { seed ->
            val table = table(seed, startingStack = 10)
            table.bet(10)
            table.playOut()
            table.view().takeIf { assertNotNull(it.result).net < 0 }
        }

        assertTrue(assertNotNull(refilled.result).refilled)
        assertEquals(10, refilled.stack)
    }

    @Test
    fun `the shoe is reshuffled as needed over a long session`() {
        val table = table()

        repeat(400) {
            table.bet(10)
            table.playOut()
        }

        assertEquals(400, table.roundNumber)
        assertTrue(table.view().stack >= 0)
    }
}

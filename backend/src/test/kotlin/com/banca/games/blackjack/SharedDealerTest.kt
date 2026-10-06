package com.banca.games.blackjack

import com.banca.games.cards.Card
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Several players against one dealer hand, each still playing a round of their own. */
class SharedDealerTest {

    private fun cards(vararg names: String) = names.map(Card::of)

    private fun seated(vararg hand: String, dealer: List<Card> = cards("9h", "7d"), shoe: List<Card> = cards("2c", "3c", "4c", "5c", "6c", "Tc")) =
        Round.seated(bet = 100, stack = 1_000, cards = cards(*hand), dealer = dealer, shoe = shoe)

    @Test
    fun `a player who stands waits for the dealer instead of playing them`() {
        val stood = seated("Ts", "8d").act(BlackjackAction.Stand)

        assertEquals(Phase.WAITING, stood.phase)
        assertFalse(stood.isSettled)
        assertNull(stood.result)
        assertEquals(cards("9h", "7d"), stood.dealer, "the dealer has not drawn")
        assertEquals(6, stood.shoe.size, "and no card has left the shoe")
        assertTrue(stood.hasLiveHand)
    }

    @Test
    fun `once the dealer has played, each player is settled against the same hand`() {
        val (dealerFinal, _) = Round.drawDealer(cards("9h", "7d"), cards("3c", "Kc"), Rules())
        assertEquals(cards("9h", "7d", "3c"), dealerFinal, "sixteen draws; nineteen stands")

        val twenty = seated("Ts", "Kd").act(BlackjackAction.Stand).settledAgainst(dealerFinal)
        val nineteen = seated("Ts", "9d").act(BlackjackAction.Stand).settledAgainst(dealerFinal)
        val eighteen = seated("Ts", "8d").act(BlackjackAction.Stand).settledAgainst(dealerFinal)

        assertEquals(listOf(100L, 0L, -100L), listOf(twenty, nineteen, eighteen).map { it.result!!.net })
        assertEquals(listOf(Outcome.WIN, Outcome.PUSH, Outcome.LOSE), listOf(twenty, nineteen, eighteen).map { it.result!!.hands.single().outcome })
        assertEquals(1_100, twenty.stack)
    }

    @Test
    fun `a player who busts is finished but still waits to be settled with the table`() {
        val bust = seated("Ts", "6d", shoe = cards("Kc", "2c")).act(BlackjackAction.Hit)

        assertEquals(Phase.WAITING, bust.phase)
        assertFalse(bust.hasLiveHand, "the dealer has nothing of theirs to beat")
        assertEquals(-100, bust.settledAgainst(cards("9h", "7d")).result!!.net)
    }

    @Test
    fun `a natural is paid at once, whatever the dealer goes on to draw`() {
        val natural = seated("As", "Kd")

        assertTrue(natural.isSettled)
        assertEquals(150, natural.result!!.net)
        assertEquals(Outcome.BLACKJACK, natural.result!!.hands.single().outcome)
    }

    @Test
    fun `a dealer natural settles everyone on the deal`() {
        val dealer = cards("Kh", "Ad")
        assertEquals(-100, seated("Ts", "9d", dealer = dealer).result!!.net)
        assertEquals(0, seated("As", "Kd", dealer = dealer).result!!.net, "a natural against a natural is a push")
    }

    @Test
    fun `insurance is offered to each player, and settles them all if the dealer has it`() {
        val offered = seated("Ts", "9d", dealer = cards("Ah", "Kd"))
        assertEquals(Phase.INSURANCE, offered.phase)

        assertEquals(0, offered.act(BlackjackAction.Insure).result!!.net, "the insurance pays for the lost hand")
        assertEquals(-100, offered.act(BlackjackAction.DeclineInsurance).result!!.net)

        val noNatural = seated("Ts", "9d", dealer = cards("Ah", "7d")).act(BlackjackAction.DeclineInsurance)
        assertEquals(Phase.PLAYER, noNatural.phase, "and play goes on if the dealer does not")
    }

    @Test
    fun `splitting and doubling work as at a table alone, and still wait for the dealer`() {
        val split = seated("8s", "8d", shoe = cards("3c", "Tc", "9c", "2c")).act(BlackjackAction.Split)
        assertEquals(2, split.hands.size)
        assertEquals(Phase.PLAYER, split.phase)

        val played = split.act(BlackjackAction.Stand).act(BlackjackAction.Stand)
        assertEquals(Phase.WAITING, played.phase)
        assertEquals(800, played.stack, "two stakes are on the felt")

        val doubled = seated("6s", "5d", shoe = cards("Tc", "2c")).act(BlackjackAction.Double)
        assertEquals(Phase.WAITING, doubled.phase)
        assertEquals(200, doubled.settledAgainst(cards("9h", "7d", "2h")).result!!.net, "twenty-one beats eighteen, at twice the stake")
    }

    @Test
    fun `only a round waiting on the dealer can be settled against one`() {
        assertFailsWith<IllegalStateException> { seated("Ts", "8d").settledAgainst(cards("9h", "7d", "2h")) }
        assertFailsWith<IllegalStateException> { seated("As", "Kd").settledAgainst(cards("9h", "7d", "2h")) }
    }

    @Test
    fun `a round at a table alone is untouched by any of this`() {
        val alone = Round.deal(bet = 100, stack = 1_000, shoe = cards("Ts", "9h", "8d", "7d") + List(60) { Card.of("5c") })
        val stood = alone.act(BlackjackAction.Stand)

        assertTrue(stood.isSettled, "the dealer plays as soon as the player is done")
        assertEquals(cards("9h", "7d", "5c"), stood.dealer)
    }
}

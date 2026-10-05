package com.banca.games.blackjack

import com.banca.games.cards.Card
import com.banca.games.cards.Deck
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoundTest {

    /**
     * A shoe that deals the named cards first: player, dealer's up card,
     * player, dealer's hole card, then whatever is drawn. Padded with low
     * cards so a round never runs out.
     */
    private fun shoe(vararg cards: String): List<Card> {
        val named = Card.allOf(*cards)
        val padding = generateSequence { Card.allOf("2c", "3d", "4h", "5s", "6c") }.flatten().take(80)
        return named + padding
    }

    private fun deal(vararg cards: String, bet: Long = 100, stack: Long = 1000, rules: Rules = Rules()) =
        Round.deal(bet = bet, stack = stack, shoe = shoe(*cards), rules = rules)

    private fun value(vararg cards: String) = valueOf(Card.allOf(*cards))

    // Counting a hand ----------------------------------------------------

    @Test
    fun `an ace counts eleven until that would bust the hand`() {
        assertEquals(HandValue(18, soft = true), value("As", "7d"))
        assertEquals(HandValue(18, soft = false), value("As", "7d", "Kc"))
        assertEquals(HandValue(12, soft = true), value("As", "Ad"))
        assertEquals(HandValue(21, soft = true), value("As", "Ad", "9c"))
        assertEquals(HandValue(13, soft = false), value("As", "Ad", "Ac", "Kd"))
    }

    @Test
    fun `face cards count ten`() {
        assertEquals(20, value("Ks", "Qd").total)
        assertEquals(30, value("Js", "Td", "Kc").total)
        assertTrue(value("Js", "Td", "Kc").isBust)
    }

    @Test
    fun `only two cards making twenty-one are a natural`() {
        assertTrue(isNatural(Card.allOf("As", "Kd")))
        assertFalse(isNatural(Card.allOf("7s", "7d", "7c")))
        assertFalse(isNatural(Card.allOf("Ks", "Qd")))
    }

    // The deal -----------------------------------------------------------

    @Test
    fun `the deal takes the bet and waits for the player`() {
        val round = deal("9s", "7d", "8c", "Kh")

        assertEquals(Phase.PLAYER, round.phase)
        assertEquals(900, round.stack, "the bet has left the stack")
        assertEquals(Card.allOf("9s", "8c"), round.hands.single().cards)
        assertEquals(Card.of("7d"), round.dealerUpCard)
        assertNull(round.result)
    }

    @Test
    fun `a bet larger than the stack is refused`() {
        assertFailsWith<IllegalArgumentException> { deal("9s", "7d", "8c", "Kh", bet = 2000) }
        assertFailsWith<IllegalArgumentException> { deal("9s", "7d", "8c", "Kh", bet = 0) }
    }

    @Test
    fun `a natural is paid three to two at once`() {
        val round = deal("As", "7d", "Kc", "9h")

        assertTrue(round.isSettled)
        val result = assertNotNull(round.result)
        assertEquals(Outcome.BLACKJACK, result.hands.single().outcome)
        assertEquals(150, result.net)
        assertEquals(1150, round.stack)
    }

    @Test
    fun `the dealer peeks under a ten and a natural there ends the round`() {
        val round = deal("9s", "Kd", "8c", "Ah")

        assertTrue(round.isSettled, "the player never gets to act against a dealer natural")
        assertEquals(Outcome.LOSE, assertNotNull(round.result).hands.single().outcome)
        assertEquals(900, round.stack)
    }

    @Test
    fun `two naturals push`() {
        val round = deal("As", "Kd", "Kc", "Ah")

        val result = assertNotNull(round.result)
        assertEquals(Outcome.PUSH, result.hands.single().outcome)
        assertEquals(0, result.net)
        assertEquals(1000, round.stack)
    }

    // Hitting and standing -----------------------------------------------

    @Test
    fun `hitting adds a card and busting loses the bet without the dealer drawing`() {
        var round = deal("Ks", "7d", "6c", "9h", "Qd")
        round = round.act(BlackjackAction.Hit)

        assertTrue(round.isSettled)
        assertEquals(Outcome.BUST, assertNotNull(round.result).hands.single().outcome)
        assertEquals(2, round.dealer.size, "the dealer has nothing to beat")
        assertEquals(900, round.stack)
    }

    @Test
    fun `standing lets the dealer draw to seventeen`() {
        // Dealer holds 7 and 5, draws a 2 (14), then a 3 (17), and stands.
        var round = deal("Ks", "7d", "9c", "5h", "2c", "3d")
        round = round.act(BlackjackAction.Stand)

        assertEquals(Card.allOf("7d", "5h", "2c", "3d"), round.dealer)
        assertEquals(Outcome.WIN, assertNotNull(round.result).hands.single().outcome, "nineteen beats seventeen")
        assertEquals(1100, round.stack)
    }

    @Test
    fun `the dealer busting pays every hand still standing`() {
        var round = deal("Ks", "6d", "2c", "Kh", "Qd")
        round = round.act(BlackjackAction.Stand)

        assertTrue(valueOf(round.dealer).isBust)
        assertEquals(Outcome.WIN, assertNotNull(round.result).hands.single().outcome, "even twelve wins")
    }

    @Test
    fun `equal totals push and the lower total loses`() {
        val push = deal("Ks", "Kd", "8c", "8h").act(BlackjackAction.Stand)
        assertEquals(Outcome.PUSH, assertNotNull(push.result).hands.single().outcome)
        assertEquals(1000, push.stack)

        val lose = deal("Ks", "Kd", "7c", "8h").act(BlackjackAction.Stand)
        assertEquals(Outcome.LOSE, assertNotNull(lose.result).hands.single().outcome)
        assertEquals(900, lose.stack)
    }

    @Test
    fun `reaching twenty-one ends the hand without asking`() {
        var round = deal("5s", "7d", "6c", "Kh", "Td")
        round = round.act(BlackjackAction.Hit)

        assertTrue(round.isSettled, "nobody hits twenty-one")
        assertEquals(Outcome.WIN, assertNotNull(round.result).hands.single().outcome)
    }

    @Test
    fun `the dealer stands on soft seventeen unless the rules say hit`() {
        val stands = deal("Ks", "6d", "9c", "Ah").act(BlackjackAction.Stand)
        assertEquals(2, stands.dealer.size)

        val hits = deal("Ks", "6d", "9c", "Ah", "2c", rules = Rules(dealerHitsSoft17 = true)).act(BlackjackAction.Stand)
        assertEquals(3, hits.dealer.size)
        assertEquals(19, valueOf(hits.dealer).total)
    }

    // Doubling -----------------------------------------------------------

    @Test
    fun `doubling doubles the bet and takes exactly one card`() {
        var round = deal("5s", "6d", "6c", "Kh", "Td", "9c")
        round = round.act(BlackjackAction.Double)

        assertTrue(round.isSettled)
        val hand = round.hands.single()
        assertEquals(200, hand.bet)
        assertEquals(3, hand.cards.size)
        // Twenty-one against a dealer who drew to twenty-five.
        assertEquals(400, assertNotNull(round.result).hands.single().returned)
        assertEquals(1200, round.stack)
    }

    @Test
    fun `doubling needs two cards and the chips to cover it`() {
        val afterHit = deal("2s", "6d", "3c", "Kh", "4d").act(BlackjackAction.Hit)
        assertFalse(afterHit.legalActions().double, "not after hitting")
        assertFailsWith<IllegalArgumentException> { afterHit.act(BlackjackAction.Double) }

        val broke = deal("5s", "6d", "6c", "Kh", bet = 600, stack = 1000)
        assertFalse(broke.legalActions().double, "400 left cannot cover another 600")
    }

    // Splitting ----------------------------------------------------------

    @Test
    fun `splitting makes two hands, each with its own bet and a new card`() {
        var round = deal("8s", "6d", "8c", "Kh", "3d", "Tc")
        round = round.act(BlackjackAction.Split)

        assertEquals(2, round.hands.size)
        assertEquals(Card.allOf("8s", "3d"), round.hands[0].cards)
        assertEquals(Card.allOf("8c", "Tc"), round.hands[1].cards)
        assertEquals(800, round.stack, "a second bet has gone in")
        assertEquals(0, round.active)
        assertEquals(Phase.PLAYER, round.phase)
    }

    @Test
    fun `split hands are played in turn and settled separately`() {
        // Dealer: 6 and K, then draws a 5 for twenty-one.
        var round = deal("8s", "6d", "8c", "Kh", "3d", "Tc", "Kd", "5c")
        round = round.act(BlackjackAction.Split)
        round = round.act(BlackjackAction.Hit)   // first hand: 8 3 K, twenty-one
        assertEquals(1, round.active, "play moves to the second hand")
        round = round.act(BlackjackAction.Stand) // second hand: eighteen

        val result = assertNotNull(round.result)
        assertEquals(listOf(Outcome.PUSH, Outcome.LOSE), result.hands.map { it.outcome })
        assertEquals(-100, result.net)
        assertEquals(900, round.stack)
    }

    @Test
    fun `ten-value cards of different faces may be split`() {
        assertTrue(deal("Ks", "6d", "Tc", "9h").legalActions().split)
        assertFalse(deal("Ks", "6d", "9c", "9h").legalActions().split)
    }

    @Test
    fun `split aces get one card each and twenty-one there is not a natural`() {
        var round = deal("As", "6d", "Ac", "9h", "Kd", "5c", "Ts")
        round = round.act(BlackjackAction.Split)

        assertTrue(round.isSettled, "neither hand may be played further")
        assertEquals(listOf(21, 16), round.hands.map { it.value.total })
        val result = assertNotNull(round.result)
        // Dealer 6 and 9 draws a ten and busts, so both hands win even money.
        assertEquals(listOf(Outcome.WIN, Outcome.WIN), result.hands.map { it.outcome })
        assertEquals(200, result.hands[0].returned, "twenty-one after a split pays one to one, not three to two")
    }

    @Test
    fun `splitting stops at the limit of hands`() {
        var round = deal("8s", "6d", "8c", "Kh", "8d", "8h", rules = Rules(maxHands = 2))
        round = round.act(BlackjackAction.Split)

        assertTrue(round.hands[0].isPair)
        assertFalse(round.legalActions().split, "two hands is the limit here")
    }

    @Test
    fun `doubling is allowed on a hand made by splitting`() {
        var round = deal("8s", "6d", "8c", "Kh", "3d", "Tc")
        round = round.act(BlackjackAction.Split)

        assertTrue(round.legalActions().double)
    }

    // Insurance ----------------------------------------------------------

    @Test
    fun `an ace up offers insurance before anything else`() {
        val round = deal("9s", "Ad", "8c", "7h")

        assertEquals(Phase.INSURANCE, round.phase)
        val legal = round.legalActions()
        assertTrue(legal.insurance)
        assertFalse(legal.hit, "the offer is answered first")
        assertFailsWith<IllegalArgumentException> { round.act(BlackjackAction.Hit) }
    }

    @Test
    fun `insurance pays two to one when the dealer has the natural`() {
        var round = deal("9s", "Ad", "8c", "Kh")
        round = round.act(BlackjackAction.Insure)

        val result = assertNotNull(round.result)
        assertEquals(150, result.insuranceReturned, "the fifty staked and a hundred won")
        assertEquals(0, result.net, "which exactly covers the lost bet")
        assertEquals(1000, round.stack)
    }

    @Test
    fun `insurance is lost when the dealer does not have it and play goes on`() {
        var round = deal("9s", "Ad", "8c", "7h")
        round = round.act(BlackjackAction.Insure)

        assertEquals(Phase.PLAYER, round.phase)
        assertEquals(850, round.stack, "the bet and the insurance are both out")

        round = round.act(BlackjackAction.Stand)
        // Seventeen against the dealer's soft eighteen.
        assertEquals(-150, assertNotNull(round.result).net)
    }

    @Test
    fun `declining insurance costs nothing`() {
        val declined = deal("9s", "Ad", "8c", "7h").act(BlackjackAction.DeclineInsurance)
        assertEquals(Phase.PLAYER, declined.phase)
        assertEquals(900, declined.stack)

        val unlucky = deal("9s", "Ad", "8c", "Kh").act(BlackjackAction.DeclineInsurance)
        assertEquals(-100, assertNotNull(unlucky.result).net)
    }

    // Invariants ---------------------------------------------------------

    @Test
    fun `acting on a settled round is refused`() {
        val round = deal("As", "7d", "Kc", "9h")
        assertFailsWith<IllegalStateException> { round.act(BlackjackAction.Hit) }
    }

    @Test
    fun `the chips always add up over many random rounds`() {
        val random = Random(20261006)

        repeat(3_000) {
            val shoe = (1..6).flatMap { Deck.full() }.shuffled(random)
            val start = 1_000L
            var round = Round.deal(bet = 100, stack = start, shoe = shoe)

            var guard = 0
            while (!round.isSettled) {
                check(guard++ < 60) { "the round did not finish" }
                val legal = round.legalActions()
                val choices = buildList {
                    if (legal.insurance) add(BlackjackAction.Insure)
                    if (round.phase == Phase.INSURANCE) add(BlackjackAction.DeclineInsurance)
                    if (legal.hit) add(BlackjackAction.Hit)
                    if (legal.stand) add(BlackjackAction.Stand)
                    if (legal.double) add(BlackjackAction.Double)
                    if (legal.split) add(BlackjackAction.Split)
                }
                round = round.act(choices.random(random))

                val onTable = round.hands.sumOf { it.bet } + round.insurance
                if (!round.isSettled) assertEquals(start, round.stack + onTable, "chips went missing mid-round")
                assertTrue(round.stack >= 0, "the stack went negative")
            }

            val result = assertNotNull(round.result)
            assertEquals(start + result.net, round.stack, "the result does not explain the stack")
            assertEquals(round.hands.size, result.hands.size)
            assertTrue(round.hands.size <= round.rules.maxHands)
        }
    }
}

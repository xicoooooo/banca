package com.banca.games.poker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BettingRoundTest {

    private fun seats(vararg stacks: Long): List<Player> =
        stacks.mapIndexed { seat, stack -> Player(seat = seat, stack = stack) }

    /** Stacks plus chips already in front of players never changes. */
    private fun chipsInPlay(round: BettingRound): Long =
        round.players.sumOf { it.stack + it.committed }

    // Postflop -----------------------------------------------------------

    @Test
    fun `checking round the table ends the street`() {
        var round = BettingRound.open(seats(1000, 1000, 1000), bigBlind = 20, firstToAct = 0)

        assertEquals(0, round.actorSeat)
        round = round.apply(Action.Check)
        assertEquals(1, round.actorSeat)
        round = round.apply(Action.Check)
        assertEquals(2, round.actorSeat)
        round = round.apply(Action.Check)

        assertTrue(round.isComplete)
        assertNull(round.actorSeat)
    }

    @Test
    fun `a bet must be called or folded to before the street ends`() {
        var round = BettingRound.open(seats(1000, 1000, 1000), bigBlind = 20, firstToAct = 0)

        round = round.apply(Action.Bet(100))
        assertFalse(round.isComplete)
        assertEquals(100, round.currentBet)

        round = round.apply(Action.Call)
        assertFalse(round.isComplete, "seat 2 has not acted yet")

        round = round.apply(Action.Fold)
        assertTrue(round.isComplete)
        assertEquals(900, round.player(0).stack)
        assertEquals(900, round.player(1).stack)
        assertEquals(PlayerStatus.FOLDED, round.player(2).status)
    }

    @Test
    fun `cannot check when facing a bet`() {
        val round = BettingRound.open(seats(1000, 1000), bigBlind = 20, firstToAct = 0)
            .apply(Action.Bet(100))

        assertFalse(round.legalActions().canCheck)
        assertFailsWith<IllegalArgumentException> { round.apply(Action.Check) }
    }

    @Test
    fun `betting below a big blind is rejected unless it is everything`() {
        val round = BettingRound.open(seats(1000, 15), bigBlind = 20, firstToAct = 0)

        assertFailsWith<IllegalArgumentException> { round.apply(Action.Bet(19)) }

        // Seat 1 has only 15, so shoving 15 is legal despite being under the blind.
        val short = round.apply(Action.Check).apply(Action.Bet(15))
        assertEquals(15, short.currentBet)
        assertEquals(PlayerStatus.ALL_IN, short.player(1).status)
    }

    @Test
    fun `a raise must be at least the size of the last raise`() {
        val round = BettingRound.open(seats(1000, 1000, 1000), bigBlind = 20, firstToAct = 0)
            .apply(Action.Bet(100))

        assertEquals(200, round.legalActions().minRaiseTo)
        assertFailsWith<IllegalArgumentException> { round.apply(Action.Raise(150)) }

        val raised = round.apply(Action.Raise(200))
        assertEquals(200, raised.currentBet)
        assertEquals(300, raised.legalActions().minRaiseTo, "the next raise must add another 100")
    }

    @Test
    fun `a full raise reopens the betting for players who already acted`() {
        var round = BettingRound.open(seats(1000, 1000, 1000), bigBlind = 20, firstToAct = 0)

        round = round.apply(Action.Bet(100))   // seat 0
        round = round.apply(Action.Call)       // seat 1 calls 100
        round = round.apply(Action.Raise(300)) // seat 2 makes a full raise

        assertEquals(0, round.actorSeat, "seat 0 must answer the raise")
        assertTrue(round.legalActions().canRaise, "a full raise lets seat 0 come back over the top")
    }

    @Test
    fun `an all-in for less than a full raise does not reopen the betting`() {
        // Seat 2 can only make a small raise, which must not let seat 1 re-raise.
        var round = BettingRound.open(seats(1000, 1000, 350), bigBlind = 20, firstToAct = 0)

        round = round.apply(Action.Bet(100))   // seat 0 bets
        round = round.apply(Action.Raise(300)) // seat 1 makes a full raise, size 200
        round = round.apply(Action.Raise(350)) // seat 2 shoves, only 50 more

        assertEquals(PlayerStatus.ALL_IN, round.player(2).status)
        assertEquals(350, round.currentBet, "the price to play did go up")

        assertEquals(0, round.actorSeat)
        assertTrue(round.legalActions().canRaise, "seat 0 has not acted since the full raise")

        round = round.apply(Action.Fold)

        assertEquals(1, round.actorSeat)
        val legal = round.legalActions()
        assertFalse(legal.canRaise, "seat 1 already acted, a short all-in does not reopen betting")
        assertTrue(legal.canCall)
        assertEquals(50, legal.callCost)
        assertFailsWith<IllegalArgumentException> { round.apply(Action.Raise(600)) }
    }

    @Test
    fun `calling more than the stack puts a player all-in for what they have`() {
        var round = BettingRound.open(seats(1000, 120), bigBlind = 20, firstToAct = 0)

        round = round.apply(Action.Bet(500))
        assertEquals(120, round.legalActions().callCost, "seat 1 can only call what it holds")

        round = round.apply(Action.Call)
        assertEquals(0, round.player(1).stack)
        assertEquals(PlayerStatus.ALL_IN, round.player(1).status)
        assertTrue(round.isComplete)
    }

    @Test
    fun `the street ends immediately when everyone folds to one player`() {
        var round = BettingRound.open(seats(1000, 1000, 1000), bigBlind = 20, firstToAct = 0)

        round = round.apply(Action.Bet(100))
        round = round.apply(Action.Fold)
        round = round.apply(Action.Fold)

        assertTrue(round.isComplete)
        assertEquals(1, round.players.count { it.isContesting })
    }

    // Preflop ------------------------------------------------------------

    private fun preflop(): BettingRound {
        // Three handed: seat 0 button, seat 1 small blind, seat 2 big blind.
        val players = listOf(
            Player(seat = 0, stack = 1000),
            Player(seat = 1, stack = 990, committed = 10, contributed = 10),
            Player(seat = 2, stack = 980, committed = 20, contributed = 20),
        )
        return BettingRound.afterBlinds(players, bigBlind = 20, firstToAct = 0)
    }

    @Test
    fun `preflop starts with the blinds owed and the button to act`() {
        val round = preflop()

        assertEquals(0, round.actorSeat)
        assertEquals(20, round.currentBet)
        assertEquals(20, round.legalActions().callCost)
        assertEquals(40, round.legalActions().minRaiseTo)
    }

    @Test
    fun `the big blind keeps the option to raise after everyone calls`() {
        var round = preflop()

        round = round.apply(Action.Call) // button calls 20
        round = round.apply(Action.Call) // small blind completes

        assertEquals(2, round.actorSeat, "posting a blind is not acting, so the big blind still decides")
        val legal = round.legalActions()
        assertTrue(legal.canCheck, "the big blind has already matched the bet")
        assertTrue(legal.canRaise, "and may still raise")

        round = round.apply(Action.Check)
        assertTrue(round.isComplete)
    }

    @Test
    fun `the small blind only owes the difference`() {
        val round = preflop().apply(Action.Call)

        assertEquals(1, round.actorSeat)
        assertEquals(10, round.legalActions().callCost, "ten is already posted")
    }

    // Invariants ---------------------------------------------------------

    @Test
    fun `chips are never created or destroyed`() {
        var round = BettingRound.open(seats(1000, 500, 350, 80), bigBlind = 20, firstToAct = 0)
        val start = chipsInPlay(round)

        val script = listOf(
            Action.Bet(60),
            Action.Raise(180),
            Action.Raise(350),  // seat 2 all-in
            Action.Call,        // seat 3 all-in for 80
            Action.Call,        // seat 0
            Action.Call,        // seat 1
        )

        for (action in script) {
            if (round.isComplete) break
            round = round.apply(action)
            assertEquals(start, chipsInPlay(round), "chips changed after $action")
        }

        assertTrue(round.isComplete)
        assertEquals(start, chipsInPlay(round))
        assertEquals(start, Pots.total(Pots.build(round.players)) + round.players.sumOf { it.stack })
    }

    @Test
    fun `acting after the street is complete is refused`() {
        val round = BettingRound.open(seats(1000, 1000), bigBlind = 20, firstToAct = 0)
            .apply(Action.Check)
            .apply(Action.Check)

        assertTrue(round.isComplete)
        assertFailsWith<IllegalStateException> { round.apply(Action.Check) }
    }
}

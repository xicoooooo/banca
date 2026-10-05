package com.banca.games.poker


import com.banca.games.cards.Card
import com.banca.games.cards.Deck
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HandTest {

    private fun seats(vararg stacks: Long): List<Player> =
        stacks.mapIndexed { seat, stack -> Player(seat = seat, stack = stack) }

    private fun deck(seed: Int = 1) = Deck.shuffled(Random(seed))

    /**
     * A deck that deals a chosen hand: hole cards go out in seat order starting
     * left of the button, then the five board cards.
     */
    private fun stacked(vararg cards: String) = Deck.stacked(Card.allOf(*cards))

    private fun chips(hand: Hand): Long = hand.players.sumOf { it.stack + it.contributed }

    // Setup --------------------------------------------------------------

    @Test
    fun `blinds are posted and the player left of the big blind acts first`() {
        val hand = Hand.start(seats(1000, 1000, 1000), buttonSeat = 0, smallBlind = 10, bigBlind = 20, deck = deck())

        assertEquals(10, hand.player(1).committed, "seat 1 is the small blind")
        assertEquals(20, hand.player(2).committed, "seat 2 is the big blind")
        assertEquals(0, hand.actorSeat, "the button acts first three handed")
        assertEquals(Street.PREFLOP, hand.street)
        assertTrue(hand.board.isEmpty())
    }

    @Test
    fun `heads up the button posts the small blind and acts first`() {
        val hand = Hand.start(seats(1000, 1000), buttonSeat = 0, smallBlind = 10, bigBlind = 20, deck = deck())

        assertEquals(10, hand.player(0).committed, "the button is the small blind heads up")
        assertEquals(20, hand.player(1).committed)
        assertEquals(0, hand.actorSeat, "and acts first before the flop")
    }

    @Test
    fun `heads up the big blind acts first after the flop`() {
        var hand = Hand.start(seats(1000, 1000), buttonSeat = 0, smallBlind = 10, bigBlind = 20, deck = deck())

        hand = hand.act(Action.Call)  // button completes
        hand = hand.act(Action.Check) // big blind checks

        assertEquals(Street.FLOP, hand.street)
        assertEquals(1, hand.actorSeat, "out of position player acts first postflop")
    }

    @Test
    fun `every player gets two cards and the board stays hidden until its street`() {
        var hand = Hand.start(seats(1000, 1000, 1000), buttonSeat = 0, smallBlind = 10, bigBlind = 20, deck = deck())

        assertEquals(3, hand.holeCards.size)
        assertTrue(hand.holeCards.values.all { it.size == 2 })
        assertEquals(6, hand.holeCards.values.flatten().distinct().size, "no card is dealt twice")

        assertEquals(0, hand.board.size)
        hand = hand.act(Action.Call).act(Action.Call).act(Action.Check)
        assertEquals(3, hand.board.size)
    }

    @Test
    fun `a player without chips cannot be dealt in`() {
        assertFailsWith<IllegalArgumentException> {
            Hand.start(seats(1000, 0), buttonSeat = 0, smallBlind = 10, bigBlind = 20, deck = deck())
        }
    }

    // Playing it out -----------------------------------------------------

    @Test
    fun `everyone folding hands the pot to the last player standing`() {
        var hand = Hand.start(seats(1000, 1000, 1000), buttonSeat = 0, smallBlind = 10, bigBlind = 20, deck = deck())

        hand = hand.act(Action.Fold) // button
        hand = hand.act(Action.Fold) // small blind

        val result = assertNotNull(hand.result)
        assertFalse(result.wentToShowdown, "nobody needs to show when everyone folds")
        assertEquals(mapOf(2 to 30L), result.winnings, "the big blind collects the blinds")
        assertEquals(1010, hand.player(2).stack)
        assertEquals(990, hand.player(1).stack)
    }

    @Test
    fun `a hand runs through every street to a showdown`() {
        var hand = Hand.start(seats(1000, 1000), buttonSeat = 0, smallBlind = 10, bigBlind = 20, deck = deck(5))

        hand = hand.act(Action.Call).act(Action.Check)
        assertEquals(Street.FLOP, hand.street)

        hand = hand.act(Action.Check).act(Action.Check)
        assertEquals(Street.TURN, hand.street)

        hand = hand.act(Action.Check).act(Action.Check)
        assertEquals(Street.RIVER, hand.street)

        hand = hand.act(Action.Check).act(Action.Check)

        assertEquals(Street.SHOWDOWN, hand.street)
        val result = assertNotNull(hand.result)
        assertTrue(result.wentToShowdown)
        assertEquals(2, result.showdown.size)
        assertEquals(40, result.winnings.values.sum(), "the whole pot is paid out")
    }

    @Test
    fun `the better hand wins at showdown`() {
        // Hole cards go out left of the button first: seat 1, then seat 0.
        val rigged = stacked(
            "Ah", "Ad", // seat 1 gets aces
            "7c", "2d", // seat 0 gets rags
            "As", "Kh", "Qc", "3s", "4d", // board
        )
        var hand = Hand.start(seats(1000, 1000), buttonSeat = 0, smallBlind = 10, bigBlind = 20, deck = rigged)

        hand = hand.act(Action.Call).act(Action.Check)
        repeat(3) { hand = hand.act(Action.Check).act(Action.Check) }

        val result = assertNotNull(hand.result)
        assertEquals(mapOf(1 to 40L), result.winnings, "trip aces beat ace high")
        assertEquals(HandCategory.THREE_OF_A_KIND, result.showdown.getValue(1).category)
    }

    @Test
    fun `a tied pot is split`() {
        // Both players play the board: a straight flush nobody can beat.
        val rigged = stacked(
            "2c", "3d",
            "2h", "3s",
            "9s", "8s", "7s", "6s", "5s",
        )
        var hand = Hand.start(seats(1000, 1000), buttonSeat = 0, smallBlind = 10, bigBlind = 20, deck = rigged)

        hand = hand.act(Action.Call).act(Action.Check)
        repeat(3) { hand = hand.act(Action.Check).act(Action.Check) }

        val result = assertNotNull(hand.result)
        assertEquals(mapOf(0 to 20L, 1 to 20L), result.winnings)
        assertEquals(1000, hand.player(0).stack, "both get their twenty back")
        assertEquals(1000, hand.player(1).stack)
    }

    @Test
    fun `an odd chip goes to the first winner left of the button`() {
        val rigged = stacked(
            "2c", "3d",
            "2h", "3s",
            "9s", "8s", "7s", "6s", "5s",
        )
        // Blinds of 5 and 15 leave a pot of 25 to split two ways.
        var hand = Hand.start(seats(1000, 1000), buttonSeat = 0, smallBlind = 5, bigBlind = 15, deck = rigged)

        hand = hand.act(Action.Call).act(Action.Check)
        repeat(3) { hand = hand.act(Action.Check).act(Action.Check) }

        val result = assertNotNull(hand.result)
        assertEquals(30, result.winnings.values.sum())
        assertEquals(listOf(1), result.awards.single().winners.take(1), "seat 1 is first left of the button")
    }

    // All-ins ------------------------------------------------------------

    @Test
    fun `when everyone is all-in the rest of the board is dealt without betting`() {
        var hand = Hand.start(seats(200, 200), buttonSeat = 0, smallBlind = 10, bigBlind = 20, deck = deck(9))

        hand = hand.act(Action.Raise(200)) // button shoves preflop
        hand = hand.act(Action.Call)       // and is called

        assertTrue(hand.isComplete, "no more decisions, so the hand runs itself out")
        assertEquals(Street.SHOWDOWN, hand.street)
        assertEquals(5, hand.board.size)
        assertEquals(400, assertNotNull(hand.result).winnings.values.sum())
    }

    @Test
    fun `a short stack can only win the part of the pot it paid for`() {
        // Seat 1 is all-in for 60 preflop; seats 2 and 0 keep betting.
        val rigged = stacked(
            "Ah", "Ad", // seat 1, short stack, best hand
            "Kh", "Kd", // seat 2
            "7c", "2d", // seat 0
            "As", "Kc", "9h", "3s", "4d", // board: trip aces for seat 1, trip kings for seat 2
        )
        var hand = Hand.start(
            seats = listOf(Player(0, 1000), Player(1, 60), Player(2, 1000)),
            buttonSeat = 0,
            smallBlind = 10,
            bigBlind = 20,
            deck = rigged,
        )

        hand = hand.act(Action.Raise(200)) // seat 0 raises
        // Seat 1 holds 60 in total, so calling a bet of 200 is an all-in call,
        // not a raise: it cannot put in more than it has.
        hand = hand.act(Action.Call)
        assertEquals(PlayerStatus.ALL_IN, hand.player(1).status)
        assertEquals(60, hand.player(1).contributed)

        hand = hand.act(Action.Call) // seat 2 calls 200
        while (!hand.isComplete) hand = hand.act(Action.Check)

        val result = assertNotNull(hand.result)
        assertEquals(180, result.winnings[1], "the short stack wins only 60 from each player")
        assertEquals(280, result.winnings[2], "seat 2 takes the side pot")
        assertEquals(460, result.winnings.values.sum())
    }

    // Invariants ---------------------------------------------------------

    @Test
    fun `chips are conserved across many random hands`() {
        val random = Random(20260927)

        repeat(300) { iteration ->
            val stacks = (0 until random.nextInt(2, 7)).map { random.nextLong(40, 2000) }
            val start = stacks.sum()

            var hand = Hand.start(
                seats = stacks.mapIndexed { seat, stack -> Player(seat, stack) },
                buttonSeat = random.nextInt(stacks.size),
                smallBlind = 10,
                bigBlind = 20,
                deck = Deck.shuffled(Random(iteration)),
            )

            var guard = 0
            while (!hand.isComplete) {
                check(guard++ < 200) { "hand did not finish" }
                val legal = hand.legalActions()
                hand = when {
                    random.nextInt(10) == 0 && legal.canFold && !legal.canCheck -> hand.act(Action.Fold)
                    random.nextInt(6) == 0 && legal.canRaise -> hand.act(Action.Raise(legal.minRaiseTo))
                    random.nextInt(8) == 0 && legal.canBet -> hand.act(Action.Bet(legal.minBet))
                    legal.canCheck -> hand.act(Action.Check)
                    else -> hand.act(Action.Call)
                }
            }

            assertEquals(start, hand.players.sumOf { it.stack }, "chips changed in hand $iteration")

            val result = assertNotNull(hand.result)
            assertEquals(
                hand.players.sumOf { it.contributed },
                result.winnings.values.sum(),
                "everything paid in must be paid back out in hand $iteration",
            )
        }
    }

    @Test
    fun `acting after the hand is over is refused`() {
        var hand = Hand.start(seats(1000, 1000, 1000), buttonSeat = 0, smallBlind = 10, bigBlind = 20, deck = deck())
        hand = hand.act(Action.Fold).act(Action.Fold)

        assertTrue(hand.isComplete)
        assertFailsWith<IllegalStateException> { hand.act(Action.Check) }
    }
}

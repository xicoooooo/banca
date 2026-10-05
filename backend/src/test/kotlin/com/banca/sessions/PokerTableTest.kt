package com.banca.sessions

import com.banca.games.poker.Action
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PokerTableTest {

    private fun table(startingStack: Long = 2_000) = PokerTable(
        names = mapOf(0 to "You", 1 to "Banca"),
        startingStack = startingStack,
        smallBlind = 10,
        bigBlind = 20,
        random = Random(11),
    ).also { it.startHand() }

    /** Plays checks and calls from whoever is to act until the hand ends. */
    private fun PokerTable.playPassively() {
        while (!isHandComplete) {
            val seat = assertNotNull(actorSeat)
            val legal = assertNotNull(view(seat).legal)
            act(seat, if (legal.canCheck) Action.Check else Action.Call)
        }
    }

    @Test
    fun `a seat sees its own cards and not its opponent's`() {
        val table = table()

        for (seat in 0..1) {
            val view = table.view(seat)
            val me = view.players.single { it.seat == seat }
            val other = view.players.single { it.seat != seat }

            assertEquals(2, assertNotNull(me.cards).size)
            assertNull(other.cards, "seat $seat must not receive the other seat's cards")
        }
    }

    @Test
    fun `both seats see different cards for themselves`() {
        val table = table()
        val mine = table.view(0).players.single { it.seat == 0 }.cards
        val theirs = table.view(1).players.single { it.seat == 1 }.cards
        assertTrue(assertNotNull(mine).intersect(assertNotNull(theirs).toSet()).isEmpty())
    }

    @Test
    fun `only the seat to act is told what it may do`() {
        val table = table()
        val actor = assertNotNull(table.actorSeat)

        assertNotNull(table.view(actor).legal)
        assertNull(table.view(1 - actor).legal)
    }

    @Test
    fun `cards are revealed to everyone at a showdown`() {
        val table = table()
        table.playPassively()

        val view = table.view(0)
        val result = assertNotNull(view.result)
        assertEquals(2, result.showdown.size)
        assertTrue(view.players.all { it.cards != null }, "a showdown shows every contesting hand")
        assertEquals(5, view.board.size)
    }

    @Test
    fun `a folded hand is never revealed`() {
        val table = table()
        val folder = assertNotNull(table.actorSeat)
        table.act(folder, Action.Fold)

        val winnerView = table.view(1 - folder)
        assertNotNull(winnerView.result)
        assertNull(winnerView.players.single { it.seat == folder }.cards)
    }

    @Test
    fun `acting out of turn is refused`() {
        val table = table()
        val waiting = 1 - assertNotNull(table.actorSeat)

        assertFailsWith<IllegalArgumentException> { table.act(waiting, Action.Fold) }
    }

    @Test
    fun `a new hand cannot start while one is being played`() {
        assertFailsWith<IllegalStateException> { table().startHand() }
    }

    @Test
    fun `the button moves and stacks carry over between hands`() {
        val table = table()
        val firstButton = table.view(0).buttonSeat
        table.act(assertNotNull(table.actorSeat), Action.Fold)
        val stacksAfter = table.view(0).players.associate { it.seat to it.stack }

        table.startHand()
        val next = table.view(0)

        assertEquals(2, next.handNumber)
        assertEquals(1 - firstButton, next.buttonSeat)
        assertEquals(4_000, next.players.sumOf { it.stack + it.committed }, "no chips appear or vanish between hands")
        assertEquals(4_000, stacksAfter.values.sum())
    }

    @Test
    fun `a busted table starts over with fresh stacks`() {
        val table = table(startingStack = 40)

        // With stacks this short someone is felted within a few hands.
        var guard = 0
        while (true) {
            check(guard++ < 200) { "nobody went broke" }
            val seat = assertNotNull(table.actorSeat)
            val legal = assertNotNull(table.view(seat).legal)
            table.act(
                seat,
                when {
                    legal.canRaise -> Action.Raise(legal.maxTo)
                    legal.canBet -> Action.Bet(legal.maxTo)
                    legal.canCheck -> Action.Check
                    else -> Action.Call
                },
            )
            if (table.isHandComplete) {
                val broke = table.view(0).players.any { it.stack == 0L }
                table.startHand()
                if (broke) break
            }
        }

        assertEquals(80, table.view(0).players.sumOf { it.stack + it.committed })
        assertTrue(table.view(0).players.all { it.stack + it.committed == 40L })
    }
}

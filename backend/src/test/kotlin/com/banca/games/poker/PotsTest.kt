package com.banca.games.poker

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PotsTest {

    private fun player(seat: Int, contributed: Long, status: PlayerStatus = PlayerStatus.ACTIVE) =
        Player(seat = seat, stack = 0, contributed = contributed, status = status)

    @Test
    fun `nothing contributed means no pots`() {
        assertEquals(emptyList(), Pots.build(listOf(player(0, 0), player(1, 0))))
    }

    @Test
    fun `equal contributions make a single pot`() {
        val pots = Pots.build(listOf(player(0, 100), player(1, 100), player(2, 100)))

        assertEquals(1, pots.size)
        assertEquals(300, pots[0].amount)
        assertEquals(setOf(0, 1, 2), pots[0].eligibleSeats)
    }

    @Test
    fun `a short all-in creates a side pot the short player cannot win`() {
        // Seat 0 is all-in for 50; the others keep betting to 200.
        val pots = Pots.build(listOf(player(0, 50), player(1, 200), player(2, 200)))

        assertEquals(2, pots.size)
        assertEquals(150, pots[0].amount, "main pot is 50 from each of the three")
        assertEquals(setOf(0, 1, 2), pots[0].eligibleSeats)
        assertEquals(300, pots[1].amount, "side pot is the 150 extra from each of the two")
        assertEquals(setOf(1, 2), pots[1].eligibleSeats)
    }

    @Test
    fun `chips from a folded player stay in the pot but cannot be won by them`() {
        val pots = Pots.build(
            listOf(
                player(0, 100, PlayerStatus.FOLDED),
                player(1, 100),
                player(2, 100),
            ),
        )

        assertEquals(1, pots.size)
        assertEquals(300, pots[0].amount, "the folded chips stay in play")
        assertEquals(setOf(1, 2), pots[0].eligibleSeats)
    }

    @Test
    fun `two all-ins at different sizes make two side pots`() {
        val pots = Pots.build(listOf(player(0, 50), player(1, 120), player(2, 300), player(3, 300)))

        assertEquals(3, pots.size)
        assertEquals(200, pots[0].amount, "50 from four players")
        assertEquals(setOf(0, 1, 2, 3), pots[0].eligibleSeats)
        assertEquals(210, pots[1].amount, "70 more from three players")
        assertEquals(setOf(1, 2, 3), pots[1].eligibleSeats)
        assertEquals(360, pots[2].amount, "180 more from two players")
        assertEquals(setOf(2, 3), pots[2].eligibleSeats)
    }

    @Test
    fun `a layer nobody contesting can win rides along with the pot below`() {
        // Seat 2 bet more than anyone could call, then folded.
        val players = listOf(player(0, 100), player(1, 100), player(2, 250, PlayerStatus.FOLDED))
        val pots = Pots.build(players)

        assertEquals(450, Pots.total(pots), "no chips may disappear")
        assertTrue(pots.all { it.eligibleSeats.isNotEmpty() })
        assertEquals(setOf(0, 1), pots.last().eligibleSeats)
    }

    @Test
    fun `everyone folding to one player gives that player everything`() {
        val pots = Pots.build(
            listOf(
                player(0, 100, PlayerStatus.FOLDED),
                player(1, 30, PlayerStatus.FOLDED),
                player(2, 100),
            ),
        )

        assertEquals(230, Pots.total(pots))
        assertTrue(pots.all { it.eligibleSeats == setOf(2) })
    }

    @Test
    fun `pots always hold exactly what was contributed`() {
        val random = Random(20260927)

        repeat(500) {
            val players = (0 until random.nextInt(2, 7)).map { seat ->
                player(
                    seat = seat,
                    contributed = random.nextLong(0, 500),
                    status = if (random.nextBoolean()) PlayerStatus.ACTIVE else PlayerStatus.FOLDED,
                )
            }
            // At least one player must still be contesting for a hand to make sense.
            val contested = players.mapIndexed { index, p ->
                if (index == 0) p.copy(status = PlayerStatus.ACTIVE) else p
            }

            val expected = contested.sumOf { it.contributed }
            assertEquals(expected, Pots.total(Pots.build(contested)), "chips changed for $contested")
        }
    }
}

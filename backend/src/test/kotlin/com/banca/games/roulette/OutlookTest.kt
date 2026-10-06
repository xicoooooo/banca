package com.banca.games.roulette

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutlookTest {

    private fun outlook(vararg wagers: Pair<Bet, Long>) = Outlook.of(wagers.map { (bet, amount) -> Wager(bet, amount) })

    @Test
    fun `every pocket is counted once, as ahead, level, behind or nothing back`() {
        val mixed = outlook(Bet.Straight(17) to 10, Bet.Red to 50, Bet.Dozen(2) to 50, Bet.Low to 20)
        assertEquals(37, mixed.ahead + mixed.level + mixed.behind + mixed.nothing)
    }

    @Test
    fun `a single number wins once in 37 and loses everything otherwise`() {
        val single = outlook(Bet.Straight(17) to 10)

        assertEquals(1, single.ahead)
        assertEquals(36, single.nothing)
        assertEquals(350, single.best)
        assertEquals(listOf(17), single.bestPockets)
    }

    @Test
    fun `an even-money bet wins on 18 pockets and loses on 19`() {
        val red = outlook(Bet.Red to 100)

        assertEquals(18, red.ahead)
        assertEquals(19, red.nothing)
        assertEquals(100, red.best)
    }

    @Test
    fun `whatever is bet, the layout loses one part in 37 on average`() {
        val layouts = listOf(
            outlook(Bet.Straight(17) to 10),
            outlook(Bet.Red to 100),
            outlook(Bet.Straight(0) to 30, Bet.Column(2) to 50, Bet.Odd to 200, Bet.Corner(16) to 10),
            outlook(*(0..36).map { Bet.Straight(it) to 10L }.toTypedArray()),
        )
        for (layout in layouts) {
            assertTrue(abs(layout.average - (-layout.staked / 37.0)) < 1e-9, "${layout.staked} staked averaged ${layout.average}")
        }
    }

    @Test
    fun `getting chips back is not the same as coming out ahead`() {
        // Two dozens: either pays 3 for 1, which is a win; neither is a wipe-out.
        val twoDozens = outlook(Bet.Dozen(1) to 50, Bet.Dozen(2) to 50)
        assertEquals(24, twoDozens.ahead)
        assertEquals(13, twoDozens.nothing)

        // A big bet on red and a small one on the first dozen: on a black number
        // in that dozen the small bet pays, and the spin still loses.
        val lopsided = outlook(Bet.Red to 100, Bet.Dozen(1) to 10)
        assertEquals(18, lopsided.ahead)
        assertEquals(6, lopsided.behind, "2, 4, 6, 8, 10 and 11")
        assertEquals(13, lopsided.nothing)
    }

    @Test
    fun `opposite bets are noticed, since one only pays for the other`() {
        val both = outlook(Bet.Red to 50, Bet.Black to 50, Bet.Even to 10)

        assertEquals(listOf(Bet.Red to Bet.Black), both.cancelling)
        assertEquals(0, outlook(Bet.Red to 50, Bet.Black to 50).best, "evenly matched, the most they can do is nothing")
        assertEquals(0, outlook(Bet.Red to 50, Bet.Black to 50).ahead)
        assertEquals(36, outlook(Bet.Red to 50, Bet.Black to 50).level)
        assertEquals(1, outlook(Bet.Red to 50, Bet.Black to 50).nothing, "zero takes both")
        assertTrue(outlook(Bet.Red to 50, Bet.Odd to 50).cancelling.isEmpty())
    }

    @Test
    fun `covering every number wins every spin and still loses`() {
        val everything = outlook(*(0..36).map { Bet.Straight(it) to 10L }.toTypedArray())

        assertEquals(0, everything.ahead)
        assertEquals(37, everything.behind)
        assertEquals(-10, everything.best)
        assertEquals(37, everything.bestPockets.size)
    }
}

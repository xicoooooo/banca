package com.banca.games.roulette

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouletteTest {

    private val everyBet: List<Bet> = buildList {
        (0..36).forEach { add(Bet.Straight(it)) }
        add(Bet.Split(0, 1)); add(Bet.Split(0, 2)); add(Bet.Split(0, 3))
        add(Bet.Split(1, 2)); add(Bet.Split(2, 3)); add(Bet.Split(1, 4)); add(Bet.Split(33, 36)); add(Bet.Split(35, 36))
        (1..34 step 3).forEach { add(Bet.Street(it)) }
        (1..32).filter { it % 3 != 0 }.forEach { add(Bet.Corner(it)) }
        (1..31 step 3).forEach { add(Bet.SixLine(it)) }
        (1..3).forEach { add(Bet.Dozen(it)); add(Bet.Column(it)) }
        addAll(listOf(Bet.Red, Bet.Black, Bet.Even, Bet.Odd, Bet.Low, Bet.High))
    }

    @Test
    fun `the wheel has every number once, half of them red`() {
        assertEquals((0..36).toList(), Wheel.ORDER.sorted())
        assertEquals(PocketColor.GREEN, Wheel.colorOf(0))
        assertEquals(18, (1..36).count { Wheel.colorOf(it) == PocketColor.RED })
        assertEquals(18, (1..36).count { Wheel.colorOf(it) == PocketColor.BLACK })
        assertEquals(PocketColor.RED, Wheel.colorOf(32))
        assertEquals(PocketColor.BLACK, Wheel.colorOf(17))
    }

    @Test
    fun `red and black alternate round the wheel`() {
        val colours = Wheel.ORDER.drop(1).map(Wheel::colorOf)
        assertTrue(colours.zipWithNext().all { (a, b) -> a != b })
    }

    @Test
    fun `a spin lands on every pocket about as often as any other`() {
        val random = Random(7)
        val counts = IntArray(Wheel.POCKETS)
        repeat(37_000) { counts[Wheel.spin(random)]++ }
        assertTrue(counts.all { it in 850..1150 }, counts.toList().toString())
    }

    @Test
    fun `each bet pays what the table says it does`() {
        assertEquals(35, Bet.Straight(17).pays)
        assertEquals(17, Bet.Split(17, 20).pays)
        assertEquals(11, Bet.Street(16).pays)
        assertEquals(8, Bet.Corner(16).pays)
        assertEquals(5, Bet.SixLine(16).pays)
        assertEquals(2, Bet.Dozen(2).pays)
        assertEquals(2, Bet.Column(2).pays)
        assertEquals(1, Bet.Red.pays)
        assertEquals(1, Bet.High.pays)
    }

    @Test
    fun `each bet covers the numbers its name promises`() {
        assertEquals(setOf(16, 17, 18), Bet.Street(16).numbers)
        assertEquals(setOf(16, 17, 19, 20), Bet.Corner(16).numbers)
        assertEquals(setOf(16, 17, 18, 19, 20, 21), Bet.SixLine(16).numbers)
        assertEquals((13..24).toSet(), Bet.Dozen(2).numbers)
        assertEquals(setOf(2, 5, 8, 11, 14, 17, 20, 23, 26, 29, 32, 35), Bet.Column(2).numbers)
        assertEquals(18, Bet.Even.numbers.size)
        assertFalse(0 in Bet.Even.numbers, "zero is neither even nor odd at this table")
        assertEquals((1..18).toSet(), Bet.Low.numbers)
    }

    @Test
    fun `every bet on the layout gives the house the same edge`() {
        // One chip on a bet, spun once on every pocket, loses exactly one chip.
        for (bet in everyBet) {
            val net = (0..36).sumOf { Roulette.settle(listOf(Wager(bet, 1)), it).net }
            assertEquals(-1, net, "$bet")
        }
    }

    @Test
    fun `bets that are not on the layout cannot be made`() {
        assertFailsWith<IllegalArgumentException> { Bet.Straight(37) }
        assertFailsWith<IllegalArgumentException> { Bet.Split(3, 4) }
        assertFailsWith<IllegalArgumentException> { Bet.Split(1, 5) }
        assertFailsWith<IllegalArgumentException> { Bet.Split(0, 4) }
        assertFailsWith<IllegalArgumentException> { Bet.Split(7, 7) }
        assertFailsWith<IllegalArgumentException> { Bet.Street(2) }
        assertFailsWith<IllegalArgumentException> { Bet.Corner(3) }
        assertFailsWith<IllegalArgumentException> { Bet.Corner(34) }
        assertFailsWith<IllegalArgumentException> { Bet.SixLine(34) }
        assertFailsWith<IllegalArgumentException> { Bet.Dozen(4) }
        assertFailsWith<IllegalArgumentException> { Bet.Column(0) }
    }

    @Test
    fun `a spin pays the winners, takes the losers, and adds it up`() {
        val wagers = listOf(Wager(Bet.Straight(17), 10), Wager(Bet.Black, 50), Wager(Bet.Dozen(1), 20), Wager(Bet.Odd, 30))

        val result = Roulette.settle(wagers, pocket = 17)

        assertEquals(17, result.pocket)
        assertEquals(PocketColor.BLACK, result.color)
        assertEquals(listOf(360L, 100L, 0L, 60L), result.wagers.map { it.returned })
        assertEquals(110, result.staked)
        assertEquals(410, result.net)
        assertEquals(listOf(true, true, false, true), result.wagers.map { it.won })
    }

    @Test
    fun `zero takes every outside bet`() {
        val outside = listOf(Bet.Red, Bet.Black, Bet.Even, Bet.Odd, Bet.Low, Bet.High, Bet.Dozen(1), Bet.Column(3))
        val result = Roulette.settle(outside.map { Wager(it, 10) }, pocket = 0)

        assertEquals(-80, result.net)
        assertEquals(PocketColor.GREEN, result.color)
        assertEquals(350, Roulette.settle(listOf(Wager(Bet.Straight(0), 10)), 0).net, "but it pays like any other number")
    }

    @Test
    fun `a spin needs a real pocket and real chips`() {
        assertFailsWith<IllegalArgumentException> { Roulette.settle(listOf(Wager(Bet.Red, 10)), 37) }
        assertFailsWith<IllegalArgumentException> { Roulette.settle(listOf(Wager(Bet.Red, 0)), 5) }
        assertEquals(0, Roulette.settle(emptyList(), 5).net)
    }
}

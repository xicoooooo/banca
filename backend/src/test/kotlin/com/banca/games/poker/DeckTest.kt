package com.banca.games.poker

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeckTest {

    @Test
    fun `a full deck is fifty two distinct cards`() {
        val cards = Deck.full()
        assertEquals(52, cards.size)
        assertEquals(52, cards.distinct().size)
        assertEquals(13, cards.count { it.suit == Suit.SPADES })
    }

    @Test
    fun `the same seed deals the same cards`() {
        val first = Deck.shuffled(Random(42)).deal(52)
        val second = Deck.shuffled(Random(42)).deal(52)
        assertEquals(first, second, "a seeded deck must be reproducible so hands can be replayed")
    }

    @Test
    fun `different seeds deal different cards`() {
        assertNotEquals(Deck.shuffled(Random(1)).deal(52), Deck.shuffled(Random(2)).deal(52))
    }

    @Test
    fun `a shuffled deck still holds every card exactly once`() {
        val dealt = Deck.shuffled(Random(7)).deal(52)
        assertEquals(Deck.full().toSet(), dealt.toSet())
    }

    @Test
    fun `dealing reduces what remains`() {
        val deck = Deck.shuffled(Random(3))
        assertEquals(52, deck.remaining)

        deck.deal()
        assertEquals(51, deck.remaining)

        deck.deal(10)
        assertEquals(41, deck.remaining)
    }

    @Test
    fun `a stacked deck deals in the given order`() {
        val deck = Deck.stacked(Card.allOf("As", "Kd", "2c"))
        assertEquals(Card.of("As"), deck.deal())
        assertEquals(Card.of("Kd"), deck.deal())
        assertEquals(Card.of("2c"), deck.deal())
    }

    @Test
    fun `cannot deal more cards than the deck holds`() {
        val deck = Deck.stacked(Card.allOf("As", "Kd"))
        assertFailsWith<IllegalArgumentException> { deck.deal(3) }
    }

    @Test
    fun `cannot deal from an empty deck`() {
        val deck = Deck.stacked(Card.allOf("As"))
        deck.deal()
        assertFailsWith<IllegalStateException> { deck.deal() }
    }

    @Test
    fun `a stacked deck rejects duplicates`() {
        assertFailsWith<IllegalArgumentException> { Deck.stacked(Card.allOf("As", "As")) }
    }

    @Test
    fun `cards round trip through their text form`() {
        val cards = Deck.full()
        assertTrue(cards.all { Card.of(it.toString()) == it })
    }
}

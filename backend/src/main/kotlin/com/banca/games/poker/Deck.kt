package com.banca.games.poker

import kotlin.random.Random

/**
 * The randomness is injected rather than taken from a global source, so a hand
 * can be replayed exactly in tests.
 */
class Deck private constructor(private val cards: ArrayDeque<Card>) {

    val remaining: Int get() = cards.size

    fun deal(): Card = cards.removeFirstOrNull() ?: error("The deck is empty")

    fun deal(count: Int): List<Card> {
        require(count <= remaining) { "Asked for $count cards, only $remaining left" }
        return List(count) { deal() }
    }

    companion object {
        fun full(): List<Card> =
            Suit.entries.flatMap { suit -> Rank.entries.map { rank -> Card(rank, suit) } }

        fun shuffled(random: Random): Deck = Deck(ArrayDeque(full().shuffled(random)))

        /** A deck that deals a known order, for tests that need a specific board. */
        fun stacked(cards: List<Card>): Deck {
            require(cards.distinct().size == cards.size) { "Stacked deck has duplicates" }
            return Deck(ArrayDeque(cards))
        }
    }
}

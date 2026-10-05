package com.banca.games.blackjack

import com.banca.games.cards.Card
import com.banca.games.cards.Rank

/** What a card counts for, with an ace as one. Whether it can be eleven is the hand's business. */
fun pointsOf(card: Card): Int = when (card.rank) {
    Rank.ACE -> 1
    Rank.TEN, Rank.JACK, Rank.QUEEN, Rank.KING -> 10
    else -> card.rank.value
}

/**
 * [soft] means an ace is being counted as eleven, so the hand cannot bust on
 * the next card.
 */
data class HandValue(val total: Int, val soft: Boolean) {
    val isBust: Boolean get() = total > 21
}

fun valueOf(cards: List<Card>): HandValue {
    val hard = cards.sumOf(::pointsOf)
    val soft = cards.any { it.rank == Rank.ACE } && hard + 10 <= 21
    return HandValue(total = if (soft) hard + 10 else hard, soft = soft)
}

/** Two cards making twenty-one. Only a hand as dealt can be one, never a split hand. */
fun isNatural(cards: List<Card>): Boolean = cards.size == 2 && valueOf(cards).total == 21

data class PlayerHand(
    val cards: List<Card>,
    val bet: Long,
    val doubled: Boolean = false,
    val stood: Boolean = false,
    val fromSplit: Boolean = false,
) {
    val value: HandValue get() = valueOf(cards)

    val isBust: Boolean get() = value.isBust

    val isBlackjack: Boolean get() = !fromSplit && isNatural(cards)

    /** Nothing more to decide: stood, doubled, bust, or on twenty-one. */
    val isFinished: Boolean get() = stood || doubled || value.total >= 21

    /** Two cards worth the same, so a king and a ten may be split like a pair. */
    val isPair: Boolean get() = cards.size == 2 && pointsOf(cards[0]) == pointsOf(cards[1])
}

package com.banca.games.poker

/**
 * The original evaluator, kept as the yardstick the fast one is checked
 * against. It scores seven cards by trying all twenty-one five-card
 * combinations and keeping the best: slow, but hard to get wrong.
 */
object ReferenceEvaluator {

    fun evaluate(cards: List<Card>): HandRank {
        require(cards.size in 5..7) { "A hand is 5 to 7 cards, got ${cards.size}" }
        require(cards.distinct().size == cards.size) { "Duplicate cards in $cards" }

        return if (cards.size == 5) {
            evaluateFive(cards)
        } else {
            fiveCardCombinations(cards).map(::evaluateFive).max()
        }
    }

    private fun evaluateFive(hand: List<Card>): HandRank {
        val ranks = hand.map { it.rank.value }.sortedDescending()
        val isFlush = hand.distinctBy { it.suit }.size == 1
        val straightHigh = straightHigh(ranks)

        // Rank values grouped by how often they appear, most frequent first and
        // highest first within the same frequency. For a full house that yields
        // [tripsRank, pairRank]; for two pair [highPair, lowPair, kicker].
        val byFrequency = ranks.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })
        val frequencies = byFrequency.map { it.value }
        val rankOrder = byFrequency.map { it.key }

        return when {
            isFlush && straightHigh != null -> HandRank(HandCategory.STRAIGHT_FLUSH, listOf(straightHigh))
            frequencies[0] == 4 -> HandRank(HandCategory.FOUR_OF_A_KIND, rankOrder)
            frequencies[0] == 3 && frequencies[1] == 2 -> HandRank(HandCategory.FULL_HOUSE, rankOrder)
            isFlush -> HandRank(HandCategory.FLUSH, ranks)
            straightHigh != null -> HandRank(HandCategory.STRAIGHT, listOf(straightHigh))
            frequencies[0] == 3 -> HandRank(HandCategory.THREE_OF_A_KIND, rankOrder)
            frequencies[0] == 2 && frequencies[1] == 2 -> HandRank(HandCategory.TWO_PAIR, rankOrder)
            frequencies[0] == 2 -> HandRank(HandCategory.PAIR, rankOrder)
            else -> HandRank(HandCategory.HIGH_CARD, ranks)
        }
    }

    /** The high card of the straight, or null. Aces play low in the wheel only. */
    private fun straightHigh(ranksDescending: List<Int>): Int? {
        val distinct = ranksDescending.distinct()
        if (distinct.size != 5) return null
        if (distinct.first() - distinct.last() == 4) return distinct.first()
        if (distinct == WHEEL) return 5
        return null
    }

    private fun fiveCardCombinations(cards: List<Card>): List<List<Card>> {
        val combinations = ArrayList<List<Card>>(21)
        val size = cards.size
        for (a in 0 until size - 4) {
            for (b in a + 1 until size - 3) {
                for (c in b + 1 until size - 2) {
                    for (d in c + 1 until size - 1) {
                        for (e in d + 1 until size) {
                            combinations += listOf(cards[a], cards[b], cards[c], cards[d], cards[e])
                        }
                    }
                }
            }
        }
        return combinations
    }

    private val WHEEL = listOf(14, 5, 4, 3, 2)
}

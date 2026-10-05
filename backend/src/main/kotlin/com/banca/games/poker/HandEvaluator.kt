package com.banca.games.poker


import com.banca.games.cards.Card
/**
 * Scores the best five-card poker hand in five to seven cards.
 *
 * It counts ranks and suits in one pass and reads the hand off those counts,
 * rather than trying every five-card combination. Equity simulation calls this
 * thousands of times per decision, on a fraction of a processor, so it avoids
 * the work and the garbage of the combinatorial approach. That approach lives
 * on in the tests as the reference this one is checked against.
 */
object HandEvaluator {

    private const val ACE = 14
    private const val WHEEL_MASK = (1 shl ACE) or (1 shl 5) or (1 shl 4) or (1 shl 3) or (1 shl 2)

    fun evaluate(cards: List<Card>): HandRank {
        require(cards.size in 5..7) { "A hand is 5 to 7 cards, got ${cards.size}" }

        val rankCount = IntArray(ACE + 1)
        val suitCount = IntArray(4)
        // One bit per rank held, overall and within each suit.
        val suitRanks = IntArray(4)
        var ranks = 0
        var seen = 0L

        for (card in cards) {
            val rank = card.rank.value
            val suit = card.suit.ordinal
            val bit = 1L shl (suit * 16 + rank)
            require(seen and bit == 0L) { "Duplicate cards in $cards" }
            seen = seen or bit

            rankCount[rank]++
            suitCount[suit]++
            suitRanks[suit] = suitRanks[suit] or (1 shl rank)
            ranks = ranks or (1 shl rank)
        }

        // Seven cards hold at most one suit with five or more.
        val flushSuit = suitCount.indexOfFirst { it >= 5 }
        if (flushSuit >= 0) {
            val high = straightHigh(suitRanks[flushSuit])
            if (high != 0) return HandRank(HandCategory.STRAIGHT_FLUSH, listOf(high))
        }

        var quads = 0
        var trips = 0
        var secondTrips = 0
        var pair = 0
        var secondPair = 0
        for (rank in ACE downTo 2) {
            when (rankCount[rank]) {
                4 -> quads = rank
                3 -> if (trips == 0) trips = rank else if (secondTrips == 0) secondTrips = rank
                2 -> if (pair == 0) pair = rank else if (secondPair == 0) secondPair = rank
            }
        }

        if (quads != 0) {
            return HandRank(HandCategory.FOUR_OF_A_KIND, listOf(quads) + highest(ranks, 1, quads))
        }
        // A second set of trips plays as the pair of a full house.
        val fullHousePair = maxOf(secondTrips, pair)
        if (trips != 0 && fullHousePair != 0) {
            return HandRank(HandCategory.FULL_HOUSE, listOf(trips, fullHousePair))
        }
        if (flushSuit >= 0) {
            return HandRank(HandCategory.FLUSH, highest(suitRanks[flushSuit], 5))
        }
        val straight = straightHigh(ranks)
        if (straight != 0) {
            return HandRank(HandCategory.STRAIGHT, listOf(straight))
        }
        if (trips != 0) {
            return HandRank(HandCategory.THREE_OF_A_KIND, listOf(trips) + highest(ranks, 2, trips))
        }
        if (secondPair != 0) {
            // A third pair can only ever supply the kicker.
            return HandRank(HandCategory.TWO_PAIR, listOf(pair, secondPair) + highest(ranks, 1, pair, secondPair))
        }
        if (pair != 0) {
            return HandRank(HandCategory.PAIR, listOf(pair) + highest(ranks, 3, pair))
        }
        return HandRank(HandCategory.HIGH_CARD, highest(ranks, 5))
    }

    /** The high card of the best straight in [ranks], or zero. Aces play low in the wheel only. */
    private fun straightHigh(ranks: Int): Int {
        for (high in ACE downTo 6) {
            val run = 0b11111 shl (high - 4)
            if (ranks and run == run) return high
        }
        return if (ranks and WHEEL_MASK == WHEEL_MASK) 5 else 0
    }

    /** The [count] highest ranks present in [ranks], leaving out [except]. */
    private fun highest(ranks: Int, count: Int, vararg except: Int): List<Int> {
        val found = ArrayList<Int>(count)
        for (rank in ACE downTo 2) {
            if (ranks and (1 shl rank) != 0 && rank !in except) {
                found += rank
                if (found.size == count) break
            }
        }
        return found
    }
}

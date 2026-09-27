package com.banca.games.poker

/** Declared weakest first, so the natural enum order is also the poker order. */
enum class HandCategory {
    HIGH_CARD,
    PAIR,
    TWO_PAIR,
    THREE_OF_A_KIND,
    STRAIGHT,
    FLUSH,
    FULL_HOUSE,
    FOUR_OF_A_KIND,
    STRAIGHT_FLUSH,
}

/**
 * [tiebreakers] holds rank values in the order they decide a tie, most
 * significant first: for two pair that is high pair, low pair, kicker. Two
 * hands of the same category always produce lists of the same length, and
 * equal lists mean a split pot.
 */
data class HandRank(
    val category: HandCategory,
    val tiebreakers: List<Int>,
) : Comparable<HandRank> {

    override fun compareTo(other: HandRank): Int {
        val byCategory = category.compareTo(other.category)
        if (byCategory != 0) return byCategory

        for (i in tiebreakers.indices) {
            val byRank = tiebreakers[i].compareTo(other.tiebreakers[i])
            if (byRank != 0) return byRank
        }
        return 0
    }
}

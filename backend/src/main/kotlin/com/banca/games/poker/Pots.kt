package com.banca.games.poker

/**
 * [eligibleSeats] are the seats that may win this pot: everyone who is still in
 * the hand and paid up to this pot's level. Chips from players who folded stay
 * in the pot but win nothing.
 */
data class Pot(val amount: Long, val eligibleSeats: Set<Int>)

object Pots {

    /**
     * Splits everything paid into a main pot and however many side pots the
     * all-ins require.
     *
     * Each distinct contribution level creates a layer: every player who reached
     * that level pays into it, and only those still contesting the hand can win
     * it. A player who is all-in for less simply stops appearing in the layers
     * above their contribution, which is exactly what a side pot means.
     */
    fun build(players: Collection<Player>): List<Pot> {
        val contributions = players.filter { it.contributed > 0 }.associate { it.seat to it.contributed }
        if (contributions.isEmpty()) return emptyList()

        val contesting = players.filter { it.isContesting }.map { it.seat }.toSet()
        val pots = mutableListOf<Pot>()
        var previousLevel = 0L

        for (level in contributions.values.distinct().sorted()) {
            val payers = contributions.filterValues { it >= level }.keys
            val amount = (level - previousLevel) * payers.size
            previousLevel = level
            if (amount == 0L) continue

            val winners = payers intersect contesting
            val previous = pots.lastOrNull()

            // Layers with the same eligible players are one pot, not several.
            // Chips nobody can win (every payer folded) ride along with the
            // layer below rather than vanishing.
            if (previous != null && (previous.eligibleSeats == winners || winners.isEmpty())) {
                pots[pots.lastIndex] = previous.copy(amount = previous.amount + amount)
            } else {
                pots += Pot(amount, winners)
            }
        }

        return pots
    }

    fun total(pots: List<Pot>): Long = pots.sumOf { it.amount }
}

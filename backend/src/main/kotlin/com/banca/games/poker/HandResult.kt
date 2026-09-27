package com.banca.games.poker

data class PotAward(
    val amount: Long,
    val winners: List<Int>,
    /** Seats that received an extra chip because the pot would not divide evenly. */
    val oddChipTo: List<Int>,
)

data class HandResult(
    val awards: List<PotAward>,
    /** Chips won per seat, before subtracting what they put in. */
    val winnings: Map<Int, Long>,
    /** The best hand each contesting player could make, empty when everyone folded. */
    val showdown: Map<Int, HandRank>,
) {
    val wentToShowdown: Boolean get() = showdown.isNotEmpty()
}

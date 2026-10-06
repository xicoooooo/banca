package com.banca.games.roulette

/**
 * What a layout of bets stands to do on one spin, counted over every pocket
 * the ball could land in. Nothing here is an estimate: with 37 pockets, each
 * as likely as the next, the whole of the future can simply be listed.
 */
data class Outlook(
    val staked: Long,
    /** How many of the 37 pockets leave the player ahead, exactly level, down but not out, and with nothing back. */
    val ahead: Int,
    val level: Int,
    val behind: Int,
    val nothing: Int,
    /** The most one spin could win, and the pockets on which it would. */
    val best: Long,
    val bestPockets: List<Int>,
    /** What the layout comes to on average, per spin. Always a loss of one part in 37 of what is staked. */
    val average: Double,
    /** Pairs of bets that between them cover every number but zero, so that one always pays for the other. */
    val cancelling: List<Pair<Bet, Bet>>,
) {
    companion object {
        private val OPPOSITES: List<Pair<Bet, Bet>> = listOf(Bet.Red to Bet.Black, Bet.Even to Bet.Odd, Bet.Low to Bet.High)

        fun of(wagers: List<Wager>): Outlook {
            val nets = (0 until Wheel.POCKETS).map { pocket -> Roulette.settle(wagers, pocket) }
            val staked = wagers.sumOf { it.amount }
            val best = nets.maxOf { it.net }
            val placed = wagers.map { it.bet }.toSet()

            return Outlook(
                staked = staked,
                ahead = nets.count { it.net > 0 },
                level = nets.count { it.net == 0L },
                behind = nets.count { it.net < 0 && it.net > -staked },
                nothing = nets.count { it.net == -staked },
                best = best,
                bestPockets = nets.filter { it.net == best }.map { it.pocket },
                average = nets.sumOf { it.net }.toDouble() / Wheel.POCKETS,
                cancelling = OPPOSITES.filter { (one, other) -> one in placed && other in placed },
            )
        }
    }
}

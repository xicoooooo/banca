package com.banca.players

/**
 * One player at one table: the only way a game touches that player's chips.
 * A table reads the balance from here and reports each finished round here,
 * and never keeps a balance of its own to disagree with the ledger.
 */
class PlayerSession(val player: Player, private val players: Players) {
    suspend fun balance(): Long = players.balance(player)

    suspend fun settle(round: FinishedRound): Long = players.settle(player, round)

    /** Brings a player who cannot cover [needed] back up, returning the new balance, or null if they could. */
    suspend fun topUpIfShort(needed: Long): Long? = players.topUpIfShort(player, needed)
}

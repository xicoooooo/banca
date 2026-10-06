package com.banca.players

/**
 * One player at one table: the only way a game touches that player's chips.
 * A table reads the balance from here and reports each finished round here,
 * and never keeps a balance of its own to disagree with the ledger.
 */
class PlayerSession(val player: Player, private val players: Players) {
    suspend fun balance(): Long = players.balance(player)

    suspend fun settle(round: FinishedRound): Long = players.settle(player, round)

    /** Sees that the player can cover [needed], staking them if the house will. */
    suspend fun fund(needed: Long): Funding = players.fund(player, needed)
}

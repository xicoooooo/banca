package com.banca.players

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One player at one table: the only way a game touches that player's chips.
 * A table reads the balance from here and reports each finished round here,
 * and never keeps a balance of its own to disagree with the ledger.
 */
open class PlayerSession(val player: Player, internal val players: Players) {
    open suspend fun balance(): Long = players.balance(player)

    open suspend fun settle(round: FinishedRound): Long = players.settle(player, round)

    /** Sees that the player can cover [needed], staking them if the house will. */
    open suspend fun fund(needed: Long): Funding = players.fund(player, needed)
}

/**
 * The chips of a table played for practice. Everyone who sits down is handed
 * the same stack, which is theirs for as long as the table lasts and is worth
 * nothing anywhere else. Nothing played with them reaches a player's own
 * chips, their record or the leagues.
 */
class PracticePurse(val stack: Long = 2_000) {
    private val held = ConcurrentHashMap<UUID, Long>()

    /** A player's place at the table, playing from this purse instead of their own chips. */
    fun sessionFor(real: PlayerSession): PlayerSession = object : PlayerSession(real.player, real.players) {
        private val id = real.player.id

        override suspend fun balance(): Long = held.getOrPut(id) { stack }

        override suspend fun settle(round: FinishedRound): Long {
            balance()
            return held.merge(id, round.net) { had, net -> had + net } ?: stack
        }

        // Practice chips cannot run out: a player who is short is handed a fresh stack.
        override suspend fun fund(needed: Long): Funding {
            val had = balance()
            if (had >= needed) return Funding.Ready(had)
            held[id] = stack
            return Funding.Staked(stack, stack - had)
        }
    }
}

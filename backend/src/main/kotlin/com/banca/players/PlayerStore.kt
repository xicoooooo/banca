package com.banca.players

import java.util.UUID

/**
 * Where players and their history are kept.
 *
 * The balance is never stored: it is the sum of a player's ledger, so it can
 * always be explained by the entries that made it. Anything that moves chips
 * does so by adding an entry, and a round is recorded together with the entry
 * that pays it, or not at all.
 */
interface PlayerStore {
    /** Creates a player known by [tokenHash], with their opening chips. */
    suspend fun create(name: String, tokenHash: String, openingChips: Long): Player

    suspend fun findByTokenHash(tokenHash: String): Player?

    suspend fun rename(id: UUID, name: String): Player

    suspend fun balance(id: UUID): Long

    /** Writes the round and its payment as one, and returns the balance after it. */
    suspend fun recordRound(id: UUID, round: FinishedRound): Long

    /** Adds chips for a reason that is not a round, and returns the balance after it. */
    suspend fun grant(id: UUID, amount: Long, reason: LedgerReason): Long

    /** The player's rounds, newest first. */
    suspend fun rounds(id: UUID, limit: Int): List<RoundRecord>

    /** The player's ledger, oldest first. */
    suspend fun ledger(id: UUID, limit: Int): List<LedgerEntry>
}

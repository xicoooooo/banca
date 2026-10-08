package com.banca.players

import java.time.Instant
import java.time.LocalDate
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

    /** Lets another device be this player, known there by [tokenHash]. */
    suspend fun addToken(id: UUID, tokenHash: String)

    /** Forgets a token, so the device holding it is no longer this player. */
    suspend fun removeToken(tokenHash: String)

    suspend fun findByAccount(accountId: String): Player?

    /** Saves the profile to an account. An account has one profile, and a profile one account. */
    suspend fun linkAccount(id: UUID, accountId: String): Player

    suspend fun rename(id: UUID, name: String): Player

    suspend fun balance(id: UUID): Long

    /** Writes the round and its payment as one, and returns the balance after it. */
    suspend fun recordRound(id: UUID, round: FinishedRound): Long

    /** Adds chips for a reason that is not a round, and returns the balance after it. */
    suspend fun grant(id: UUID, amount: Long, reason: LedgerReason): Long

    /**
     * Adds chips for [reason] unless the player has already had some for it
     * since [since], and returns the balance after it, or null if they had.
     * Two requests arriving together must not both be paid.
     */
    suspend fun grantUnlessSince(id: UUID, amount: Long, reason: LedgerReason, since: Instant): Long?

    /**
     * Adds chips for [reason] unless the player has already been paid for
     * exactly this, as [reference] names it, and returns the balance after it,
     * or null if they had. Two requests arriving together must not both be paid.
     */
    suspend fun grantOnce(id: UUID, amount: Long, reason: LedgerReason, reference: String): Long?

    /** What the player has been paid for under [reason] since [since], as each payment was named. */
    suspend fun referencesSince(id: UUID, reason: LedgerReason, since: Instant): List<String>

    /** When the player was last given chips for [reason], newest first. */
    suspend fun grantsOf(id: UUID, reason: LedgerReason, limit: Int): List<Instant>

    /**
     * How every signed-in player did at the tables from [from] up to [until],
     * leaving out private tables, which do not count towards the leagues:
     * what they won and how many rounds they played, at [game] or at all of
     * them. Players who did not play are there too, with noughts.
     */
    suspend fun standings(from: Instant, until: Instant, game: Game? = null): List<Standing>

    /** The latest week that has been settled, named by its Monday, or null if none has. */
    suspend fun lastSettledWeek(): LocalDate?

    /**
     * Settles the week beginning on [week], once. [decide] is given the week's
     * standings and says what it came to for each player; their new leagues,
     * their prizes and the record of it are then written together. Returns
     * false, having done nothing, if the week had already been settled.
     */
    suspend fun settleWeek(week: LocalDate, from: Instant, until: Instant, decide: (List<Standing>) -> List<LeagueResult>): Boolean

    /** What a settled week came to for one player, or null if they had no part in it. */
    suspend fun leagueResult(id: UUID, week: LocalDate): LeagueResult?

    /** The player's trophies, newest first: every week they finished in a paid place. */
    suspend fun trophies(id: UUID, limit: Int): List<Trophy>

    suspend fun findById(id: UUID): Player?

    /** The player's rounds, newest first. */
    suspend fun rounds(id: UUID, limit: Int): List<RoundRecord>

    /** The player's ledger, oldest first. */
    suspend fun ledger(id: UUID, limit: Int): List<LedgerEntry>
}

package com.banca.players

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import kotlin.random.Random

/**
 * The rules about players: who someone is, what they start with, and what
 * happens when they run out.
 *
 * There are no accounts yet. A browser is given a secret token the first time
 * it arrives and shows it again on each visit, which makes it a guest with a
 * profile of its own. The token is only ever stored as a hash.
 */
class Players(private val store: PlayerStore, private val clock: Clock = Clock.systemUTC()) {

    private val random = SecureRandom()

    suspend fun createGuest(): Pair<Player, String> {
        val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val player = store.create(name = "Guest ${Random.nextInt(1000, 10_000)}", tokenHash = hash(token), openingChips = OPENING_CHIPS)
        return player to token
    }

    suspend fun authenticate(token: String): Player? =
        if (token.isBlank()) null else store.findByTokenHash(hash(token))

    suspend fun rename(player: Player, name: String): Player {
        val tidy = name.trim().replace(Regex("\\s+"), " ")
        require(NAME.matches(tidy)) { "A name is 2 to 20 letters, digits, spaces or . ' - _" }
        return store.rename(player.id, tidy)
    }

    suspend fun balance(player: Player): Long = store.balance(player.id)

    /** Writes a finished round down and returns the balance after it. */
    suspend fun settle(player: Player, round: FinishedRound): Long = store.recordRound(player.id, round)

    /**
     * Chips cannot be bought, so nobody may be left unable to play. A player
     * with less than [needed] is brought back up to the opening amount.
     * Returns the new balance, or null when no top-up was due.
     */
    suspend fun topUpIfShort(player: Player, needed: Long): Long? {
        val balance = store.balance(player.id)
        if (balance >= needed) return null
        return store.grant(player.id, OPENING_CHIPS - balance, LedgerReason.BUST_TOP_UP)
    }

    suspend fun dashboard(player: Player): Dashboard = DashboardBuilder.build(
        player = player,
        balance = store.balance(player.id),
        rounds = store.rounds(player.id, HISTORY_LIMIT),
        ledger = store.ledger(player.id, HISTORY_LIMIT),
        now = clock.instant(),
    )

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val OPENING_CHIPS = 2_000L

        /** How far back the dashboard reads. Beyond this, totals describe recent play. */
        private const val HISTORY_LIMIT = 5_000

        private val NAME = Regex("^[\\p{L}\\p{N} ._'-]{2,20}$")
    }
}

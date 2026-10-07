package com.banca.players

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Players kept in memory, gone when the server stops. Used by the tests, and
 * by a server started without a database so the games can still be played.
 */
class InMemoryPlayerStore(private val clock: Clock = Clock.systemUTC()) : PlayerStore {

    private class Entry(var player: Player) {
        val ledger = mutableListOf<LedgerEntry>()
        val rounds = mutableListOf<RoundRecord>()
    }

    private val accounts = mutableMapOf<UUID, Entry>()
    private val tokens = mutableMapOf<String, UUID>()
    private val settled = mutableMapOf<LocalDate, Map<UUID, LeagueResult>>()
    private val lock = Mutex()

    private fun account(id: UUID) = accounts[id] ?: error("No player $id")

    override suspend fun create(name: String, tokenHash: String, openingChips: Long): Player = lock.withLock {
        val player = Player(UUID.randomUUID(), name, Instant.now(clock))
        val account = Entry(player)
        account.ledger += LedgerEntry(openingChips, LedgerReason.SIGNUP_GRANT, player.createdAt)
        accounts[player.id] = account
        tokens[tokenHash] = player.id
        player
    }

    override suspend fun findByTokenHash(tokenHash: String): Player? = lock.withLock {
        tokens[tokenHash]?.let { accounts[it] }?.player
    }

    override suspend fun addToken(id: UUID, tokenHash: String): Unit = lock.withLock {
        account(id)
        tokens[tokenHash] = id
    }

    override suspend fun removeToken(tokenHash: String): Unit = lock.withLock {
        tokens.remove(tokenHash)
    }

    override suspend fun findByAccount(accountId: String): Player? = lock.withLock {
        accounts.values.firstOrNull { it.player.accountId == accountId }?.player
    }

    override suspend fun linkAccount(id: UUID, accountId: String): Player = lock.withLock {
        val account = account(id)
        check(account.player.accountId == null) { "This profile already belongs to an account" }
        check(accounts.values.none { it.player.accountId == accountId }) { "This account already has a profile" }
        account.player = account.player.copy(accountId = accountId)
        account.player
    }

    override suspend fun rename(id: UUID, name: String): Player = lock.withLock {
        val account = account(id)
        account.player = account.player.copy(name = name)
        account.player
    }

    override suspend fun balance(id: UUID): Long = lock.withLock { account(id).ledger.sumOf { it.amount } }

    override suspend fun recordRound(id: UUID, round: FinishedRound): Long = lock.withLock {
        val account = account(id)
        val now = Instant.now(clock)
        account.rounds += RoundRecord(round.game, now, round.staked, round.net, round.outcome, round.detail)
        account.ledger += LedgerEntry(round.net, LedgerReason.ROUND, now)
        account.ledger.sumOf { it.amount }
    }

    override suspend fun grant(id: UUID, amount: Long, reason: LedgerReason): Long = lock.withLock {
        val account = account(id)
        account.ledger += LedgerEntry(amount, reason, Instant.now(clock))
        account.ledger.sumOf { it.amount }
    }

    override suspend fun grantUnlessSince(id: UUID, amount: Long, reason: LedgerReason, since: Instant): Long? = lock.withLock {
        val account = account(id)
        if (account.ledger.any { it.reason == reason && !it.at.isBefore(since) }) return@withLock null
        account.ledger += LedgerEntry(amount, reason, Instant.now(clock))
        account.ledger.sumOf { it.amount }
    }

    override suspend fun grantsOf(id: UUID, reason: LedgerReason, limit: Int): List<Instant> = lock.withLock {
        account(id).ledger.filter { it.reason == reason }.map { it.at }.asReversed().take(limit)
    }

    override suspend fun standings(from: Instant, until: Instant, game: Game?): List<Standing> = lock.withLock { standingsIn(from, until, game) }

    private fun standingsIn(from: Instant, until: Instant, game: Game?): List<Standing> =
        accounts.values.filter { it.player.accountId != null }.map { entry ->
            val played = entry.rounds.filter { !it.endedAt.isBefore(from) && it.endedAt.isBefore(until) && (game == null || it.game == game) }
            Standing(entry.player.id, entry.player.name, entry.player.leagueTier, net = played.sumOf { it.net }, rounds = played.size)
        }

    override suspend fun lastSettledWeek(): LocalDate? = lock.withLock { settled.keys.maxOrNull() }

    override suspend fun settleWeek(
        week: LocalDate,
        from: Instant,
        until: Instant,
        decide: (List<Standing>) -> List<LeagueResult>,
    ): Boolean = lock.withLock {
        if (week in settled) return@withLock false
        val results = decide(standingsIn(from, until, null))
        for (result in results) {
            val entry = accounts[result.playerId] ?: continue
            entry.player = entry.player.copy(leagueTier = result.nextTier)
            if (result.prize > 0) entry.ledger += LedgerEntry(result.prize, LedgerReason.LEAGUE_PRIZE, Instant.now(clock))
        }
        settled[week] = results.associateBy { it.playerId }
        true
    }

    override suspend fun leagueResult(id: UUID, week: LocalDate): LeagueResult? = lock.withLock { settled[week]?.get(id) }

    override suspend fun rounds(id: UUID, limit: Int): List<RoundRecord> = lock.withLock {
        account(id).rounds.asReversed().take(limit)
    }

    override suspend fun ledger(id: UUID, limit: Int): List<LedgerEntry> = lock.withLock {
        account(id).ledger.takeLast(limit)
    }
}

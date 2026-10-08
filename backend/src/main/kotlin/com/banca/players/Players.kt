package com.banca.players

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import kotlin.random.Random

/**
 * The rules about players: who someone is, what they start with, and what
 * happens when they run out.
 *
 * A browser is given a secret token the first time it arrives and shows it
 * again on each visit, which makes it a guest with a profile of its own. The
 * token is only ever stored as a hash. Signing in saves that profile to an
 * account, and from then on any device that signs in to the account is given
 * a token of its own for the same profile.
 */
class Players(private val store: PlayerStore, private val clock: Clock = Clock.systemUTC()) {

    private val random = SecureRandom()

    /** The leagues and lists of winners, drawn from the same record. */
    val leaderboards = Leaderboards(store, clock)

    private fun newToken(): String =
        ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    suspend fun createGuest(): Pair<Player, String> {
        val token = newToken()
        val player = store.create(name = "Guest ${Random.nextInt(1000, 10_000)}", tokenHash = hash(token), openingChips = OPENING_CHIPS)
        return player to token
    }

    /**
     * What signing in came to: the profile the device is now playing as, and a
     * new token for it when that is not the profile it arrived with.
     */
    class SignedIn(val player: Player, val token: String?)

    /**
     * Signs the device holding [token], currently playing as [current], in to [account].
     *
     * An account with no profile yet takes the one the device has, so nothing
     * a guest has won or played is lost by signing in. An account that already
     * has a profile brings it to this device, and the guest profile is left
     * behind: two bankrolls are never added together, or making guests would
     * be a way of making chips.
     */
    suspend fun signIn(current: Player, token: String, account: Account): SignedIn {
        val saved = store.findByAccount(account.id)
        if (saved?.id == current.id) return SignedIn(current, null)

        if (saved != null) {
            val fresh = newToken()
            store.addToken(saved.id, hash(fresh))
            // A guest left behind has no other way in, so its token is no use to anyone.
            if (current.accountId == null) store.removeToken(hash(token))
            return SignedIn(saved, fresh)
        }

        if (current.accountId == null) {
            var linked = store.linkAccount(current.id, account.id)
            // A guest who never chose a name takes their first name. No more
            // than that: a name here may one day be shown to other players.
            val known = account.name?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.take(20)
            if (GUEST_NAME.matches(linked.name) && known != null && NAME.matches(known)) {
                linked = store.rename(linked.id, known)
            }
            return SignedIn(linked, null)
        }

        // The device is playing as someone else's saved profile, which stays
        // theirs. This account starts one of its own.
        val fresh = newToken()
        val started = store.create(name = "Guest ${Random.nextInt(1000, 10_000)}", tokenHash = hash(fresh), openingChips = OPENING_CHIPS)
        return SignedIn(store.linkAccount(started.id, account.id), fresh)
    }

    /** Signs one device out. The profile stays with its account, to be signed in to again. */
    suspend fun signOut(token: String) = store.removeToken(hash(token))

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

    /** What the player can claim, and when. */
    suspend fun rewards(player: Player): RewardStatus = Rewards.status(
        dailyClaims = store.grantsOf(player.id, LedgerReason.DAILY_REWARD, DAILY_CLAIMS_READ),
        lastRescue = store.grantsOf(player.id, LedgerReason.BUST_TOP_UP, 1).firstOrNull(),
        now = clock.instant(),
    )

    /** Pays today's reward and returns what it was, or null if today's has been claimed. */
    suspend fun claimDaily(player: Player): Long? {
        val now = clock.instant()
        val reward = rewards(player).daily
        if (!reward.available) return null
        val today = Rewards.startOfDay(Rewards.dayOf(now))
        return store.grantUnlessSince(player.id, reward.amount, LedgerReason.DAILY_REWARD, since = today)?.let { reward.amount }
    }

    /**
     * Sees that a player can cover [needed] before a round. One who cannot is
     * staked by the house, a little and not often; one the house has staked
     * too recently has to wait, or claim their daily reward.
     */
    suspend fun fund(player: Player, needed: Long): Funding {
        val balance = store.balance(player.id)
        if (balance >= needed) return Funding.Ready(balance)

        val now = clock.instant()
        // A stake exactly the full wait ago no longer counts, which is when the
        // player was told the next one would come.
        val recently = now.minus(Rewards.RESCUE_EVERY).plusMillis(1)
        val staked = store.grantUnlessSince(player.id, Rewards.RESCUE, LedgerReason.BUST_TOP_UP, since = recently)
        if (staked != null) return Funding.Staked(staked, Rewards.RESCUE)

        val status = rewards(player)
        val waits = listOfNotNull(status.daily.nextAt, status.rescue.nextAt).map(Instant::parse)
        return Funding.Broke(
            balance = balance,
            dailyReady = status.daily.available,
            nextChipsAt = if (status.daily.available) now else waits.minOrNull() ?: now,
        )
    }

    suspend fun dashboard(player: Player): Dashboard {
        val now = clock.instant()
        val rounds = store.rounds(player.id, HISTORY_LIMIT)
        return DashboardBuilder.build(
            player = player,
            balance = store.balance(player.id),
            rounds = rounds,
            ledger = store.ledger(player.id, HISTORY_LIMIT),
            now = now,
            rewards = rewards(player),
            trophies = store.trophies(player.id, TROPHIES_SHOWN),
            missions = missionsFrom(player, now, rounds),
        )
    }

    /** Where the player stands with today's missions, from rounds already in hand. */
    private suspend fun missionsFrom(player: Player, now: Instant, rounds: List<RoundRecord>): MissionsStatus {
        val today = Rewards.startOfDay(Rewards.dayOf(now))
        val claimed = Missions.claimedSlots(Rewards.dayOf(now), store.referencesSince(player.id, LedgerReason.MISSION_REWARD, today))
        return Missions.status(player.id, now, rounds.filter { !it.endedAt.isBefore(today) }, claimed)
    }

    /** Where the player stands with today's missions. */
    suspend fun missions(player: Player): MissionsStatus = missionsFrom(player, clock.instant(), store.rounds(player.id, ROUNDS_IN_A_DAY))

    /**
     * Pays for a mission the player has done, or the bonus for doing all
     * three, and returns what it was worth. Null when there is nothing there
     * to claim: not done yet, already paid, or no such mission.
     */
    suspend fun claimMission(player: Player, slot: Int): Long? {
        val now = clock.instant()
        val worth = Missions.worth(missions(player), slot) ?: return null
        val paid = store.grantOnce(player.id, worth, LedgerReason.MISSION_REWARD, Missions.reference(Rewards.dayOf(now), slot))
        return worth.takeIf { paid != null }
    }

    /**
     * What anyone may see of the player with this id, or null if there is no
     * such player or they have not signed in. A guest has no public profile:
     * they are on no leaderboard for anyone to have found them by.
     */
    suspend fun publicProfile(id: java.util.UUID): PublicProfile? {
        val player = store.findById(id)?.takeIf { it.accountId != null } ?: return null
        val full = dashboard(player)
        return PublicProfile(
            name = full.player.name,
            memberSince = full.player.memberSince,
            level = full.player.level,
            title = full.player.title,
            league = full.league ?: Leagues.TIERS.first(),
            rounds = full.totals.rounds,
            trophies = full.trophies,
            achievements = full.achievements.count { it.earned },
            achievementsInAll = full.achievements.size,
        )
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val OPENING_CHIPS = 2_000L

        /** How far back the dashboard reads. Beyond this, totals describe recent play. */
        private const val HISTORY_LIMIT = 5_000

        /** More rounds than anyone plays in a day, which is as far back as missions look. */
        private const val ROUNDS_IN_A_DAY = 2_000

        private const val TROPHIES_SHOWN = 60

        /** Enough daily claims to count a streak of a year. */
        private const val DAILY_CLAIMS_READ = 370

        private val NAME = Regex("^[\\p{L}\\p{N} ._'-]{2,20}$")
        private val GUEST_NAME = Regex("^Guest \\d{4}$")
    }
}

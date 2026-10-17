package com.banca.players

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.UUID

/** One player's go at a challenge, as everyone who took it sees it. */
@Serializable
data class ChallengeStanding(
    val name: String,
    /** What they ended on, or null while they are still playing. */
    val chips: Long?,
    val roundsPlayed: Int,
    /** Where they stand among those who have finished, from 1. Null until they have. */
    val place: Int?,
    val you: Boolean,
    /** False for a go that ended early, with rounds left unplayed and chips left to play them with. Such a go is placed below every whole one. */
    val complete: Boolean = true,
)

/** A challenge as a player sees it: its rules, who set it, and how everyone who took it did. */
@Serializable
data class ChallengeView(
    val code: String,
    val by: String,
    val yours: Boolean,
    val rounds: Int,
    val stack: Long,
    val maxBet: Long,
    val createdAt: String,
    /** Whether this player has taken it, and so cannot again. */
    val taken: Boolean,
    val standings: List<ChallengeStanding>,
)

/**
 * Challenges between players: a short run of blackjack dealt from a shoe
 * shuffled from a seed, which everyone who takes it is dealt in the same order
 * for as long as they play the same way. Each player may take a challenge
 * once, and it is compared by what they end on.
 *
 * The chips are the challenge's own. Nothing reaches anyone's bankroll, their
 * record or the leagues: a challenge keeps only how each player finished.
 */
class Challenges(private val store: PlayerStore, private val clock: java.time.Clock = java.time.Clock.systemUTC()) {

    private val random = SecureRandom()

    /**
     * Sets a challenge in [player]'s name and returns its code. Challenges are
     * for players who have signed in: a guest costs nothing to make, and one
     * made to play a challenge would learn its shoe before the real go.
     */
    suspend fun set(player: Player): String {
        require(player.accountId != null) { "Sign in to set a challenge" }
        repeat(ATTEMPTS) {
            val code = buildString { repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
            if (store.challengeByCode(code) == null) {
                store.createChallenge(player.id, code, random.nextLong(), ROUNDS, STACK)
                return code
            }
        }
        error("Could not find a free code for a challenge")
    }

    suspend fun find(code: String): ChallengeRecord? = store.challengeByCode(code)

    /**
     * Whether [player] has had their go at [challenge]. A go left unfinished
     * with nobody playing it, as one cut off when the server restarted, is
     * closed here where it stands, and counts.
     */
    suspend fun hasTaken(challenge: ChallengeRecord, player: Player): Boolean {
        val mine = store.challengeEntries(challenge.id).firstOrNull { it.playerId == player.id } ?: return false
        if (!mine.finished) close(challenge, mine)
        return true
    }

    /** Begins [player]'s go at [challenge], which is when they put their first chips down. False if they have had one. */
    suspend fun enter(challenge: ChallengeRecord, player: Player): Boolean {
        require(player.accountId != null) { "Sign in to take a challenge" }
        return store.enterChallenge(challenge.id, player.id)
    }

    /** Writes down where [player]'s go stands after a round. */
    suspend fun played(challenge: ChallengeRecord, player: Player, roundsPlayed: Int, chips: Long) =
        store.recordChallengeRound(challenge.id, player.id, roundsPlayed, chips)

    suspend fun finish(challenge: ChallengeRecord, player: Player, roundsPlayed: Int, chips: Long) =
        store.finishChallenge(challenge.id, player.id, roundsPlayed, chips)

    /** The challenge with [code] as [viewer] sees it, or null if there is none. */
    suspend fun view(code: String, viewer: Player?): ChallengeView? {
        val challenge = store.challengeByCode(code) ?: return null
        return viewOf(challenge, viewer)
    }

    /** The challenges [player] has set or taken, newest first. */
    suspend fun of(player: Player): List<ChallengeView> {
        val challenges = store.challengesOf(player.id, LISTED)
        // Everyone's goes at all of them, read at once rather than a challenge at a time.
        val entries = store.challengeEntries(challenges.map { it.id })
        return challenges.map { viewOf(it, player, entries[it.id].orEmpty()) }
    }

    /** Ends a go nobody is playing any more, on what it last stood at. */
    private suspend fun close(challenge: ChallengeRecord, entry: ChallengeEntry) =
        store.finishChallenge(challenge.id, entry.playerId, entry.roundsPlayed, entry.chips ?: challenge.stack)

    /** A go that was played to its end: every round, or until there were not the chips for another. */
    private fun ChallengeEntry.isWhole(challenge: ChallengeRecord): Boolean = roundsPlayed >= challenge.rounds || (chips ?: 0) < MIN_BET

    private suspend fun viewOf(
        challenge: ChallengeRecord,
        viewer: Player?,
        known: List<ChallengeEntry>? = null,
    ): ChallengeView {
        // A go nobody has touched in a long while was cut off, and is closed where it stands.
        var entries = known ?: store.challengeEntries(challenge.id)
        val stale = entries.filter { !it.finished && it.startedAt.isBefore(clock.instant().minus(STALE_AFTER)) }
        if (stale.isNotEmpty()) {
            stale.forEach { close(challenge, it) }
            entries = store.challengeEntries(challenge.id)
        }

        // Whole goes first, best first; then goes cut short, which a player could otherwise end early to
        // keep a lead they had not played out; then whoever is still playing.
        val (whole, short) = entries.filter { it.finished }.partition { it.isWhole(challenge) }
        val ranked = whole.sortedByDescending { it.chips } + short.sortedByDescending { it.chips }
        fun placeOf(entry: ChallengeEntry): Int {
            val group = if (entry in whole) whole else short
            val above = group.count { (it.chips ?: 0) > (entry.chips ?: 0) }
            // Players on the same chips share a place.
            return above + 1 + if (entry in whole) 0 else whole.size
        }

        return ChallengeView(
            code = challenge.code,
            by = challenge.creatorName,
            yours = challenge.createdBy == viewer?.id,
            rounds = challenge.rounds,
            stack = challenge.stack,
            maxBet = MAX_BET,
            createdAt = challenge.createdAt.toString(),
            taken = entries.any { it.playerId == viewer?.id },
            standings = (ranked + entries.filter { !it.finished }).map { entry ->
                ChallengeStanding(
                    name = entry.name,
                    chips = entry.chips.takeIf { entry.finished },
                    roundsPlayed = entry.roundsPlayed,
                    place = if (entry.finished) placeOf(entry) else null,
                    you = entry.playerId == viewer?.id,
                    complete = !entry.finished || entry.isWhole(challenge),
                )
            },
        )
    }

    companion object {
        /** The rounds a challenge runs to, the chips it starts with, and the most that may be bet on one round. */
        const val ROUNDS = 10
        const val STACK = 1_000L
        const val MIN_BET = 10L
        const val MAX_BET = 100L

        private const val LISTED = 20

        /** How long a go may sit untouched before it is taken to have been cut off. */
        private val STALE_AFTER = java.time.Duration.ofHours(2)
        private const val ATTEMPTS = 20
        private const val CODE_LENGTH = 6

        /** The same letters as a table's code: none that could be read as another. */
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    }
}

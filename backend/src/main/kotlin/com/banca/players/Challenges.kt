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

/** Why a player could not sit down to a challenge. */
sealed interface Entry {
    data class Ready(val challenge: ChallengeRecord) : Entry
    data object NoSuchChallenge : Entry
    data object AlreadyTaken : Entry
}

/**
 * Challenges between players: a short run of blackjack dealt from a shoe
 * shuffled from a seed, which everyone who takes it is dealt in the same order
 * for as long as they play the same way. Each player may take a challenge
 * once, and it is compared by what they end on.
 *
 * The chips are the challenge's own. Nothing reaches anyone's bankroll, their
 * record or the leagues: a challenge keeps only how each player finished.
 */
class Challenges(private val store: PlayerStore) {

    private val random = SecureRandom()

    /** Sets a challenge in [player]'s name and returns its code. */
    suspend fun set(player: Player): String {
        repeat(ATTEMPTS) {
            val code = buildString { repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
            if (store.challengeByCode(code) == null) {
                store.createChallenge(player.id, code, random.nextLong(), ROUNDS, STACK)
                return code
            }
        }
        error("Could not find a free code for a challenge")
    }

    /** Sits [player] down to the challenge with [code], if there is one and they have not taken it. */
    suspend fun enter(code: String, player: Player): Entry {
        val challenge = store.challengeByCode(code) ?: return Entry.NoSuchChallenge
        return if (store.enterChallenge(challenge.id, player.id)) Entry.Ready(challenge) else Entry.AlreadyTaken
    }

    suspend fun finish(challenge: ChallengeRecord, player: Player, roundsPlayed: Int, chips: Long) =
        store.finishChallenge(challenge.id, player.id, roundsPlayed, chips)

    /** The challenge with [code] as [viewer] sees it, or null if there is none. */
    suspend fun view(code: String, viewer: Player?): ChallengeView? {
        val challenge = store.challengeByCode(code) ?: return null
        return viewOf(challenge, viewer)
    }

    /** The challenges [player] has set or taken, newest first. */
    suspend fun of(player: Player): List<ChallengeView> = store.challengesOf(player.id, LISTED).map { viewOf(it, player) }

    private suspend fun viewOf(challenge: ChallengeRecord, viewer: Player?): ChallengeView {
        val entries = store.challengeEntries(challenge.id)
        val finished = entries.filter { it.chips != null }.sortedByDescending { it.chips }
        return ChallengeView(
            code = challenge.code,
            by = challenge.creatorName,
            yours = challenge.createdBy == viewer?.id,
            rounds = challenge.rounds,
            stack = challenge.stack,
            maxBet = MAX_BET,
            createdAt = challenge.createdAt.toString(),
            taken = entries.any { it.playerId == viewer?.id },
            standings = (finished + entries.filter { it.chips == null }).map { entry ->
                ChallengeStanding(
                    name = entry.name,
                    chips = entry.chips,
                    roundsPlayed = entry.roundsPlayed,
                    // Players on the same chips share a place.
                    place = entry.chips?.let { chips -> finished.count { (it.chips ?: 0) > chips } + 1 },
                    you = entry.playerId == viewer?.id,
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
        private const val ATTEMPTS = 20
        private const val CODE_LENGTH = 6

        /** The same letters as a table's code: none that could be read as another. */
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    }
}

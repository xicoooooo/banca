package com.banca.players

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

/** Every game there is a table for. Stored by name, so new games only ever add to this. */
enum class Game {
    POKER,
    BLACKJACK,
    ROULETTE,
}

/** How a round went for one player, in terms any game can answer. */
enum class RoundOutcome {
    WIN,
    LOSS,
    PUSH,
}

enum class LedgerReason {
    SIGNUP_GRANT,
    ROUND,
    BUST_TOP_UP,
    DAILY_REWARD,
    LEAGUE_PRIZE,
    MISSION_REWARD,
}

/**
 * [accountId] is the account the profile is saved to, or null for a guest,
 * whose profile can only be reached from the device that made it.
 */
data class Player(
    val id: UUID,
    val name: String,
    val createdAt: Instant,
    val accountId: String? = null,
    /** The league the player is in, from 0. Only means anything for a player who has signed in. */
    val leagueTier: Int = 0,
)

/** How one player stands to another: friends, or a request one way or the other that has not been answered. */
enum class FriendState {
    FRIENDS,
    /** They have asked this player. */
    INCOMING,
    /** This player has asked them. */
    OUTGOING,
}

/** Another player, and how this one stands to them. */
data class FriendLink(val other: Player, val state: FriendState)

/** Someone the sign-in provider vouches for. */
data class Account(val id: String, val name: String?)

/**
 * What one round did to one player. The round itself, its table and its game,
 * could be shared with others; everything here is this player's alone.
 */
data class RoundRecord(
    val game: Game,
    val endedAt: Instant,
    /** Chips the player put at risk. */
    val staked: Long,
    /** What the round did to their balance. */
    val net: Long,
    val outcome: RoundOutcome,
    /** Whatever the game wants remembered about how the player played. */
    val detail: JsonObject,
    /** False for a round at a private table, which counts for everything but the leagues. */
    val ranked: Boolean = true,
)

/** How the table of a round is written down when it was a private one, opened by a player for their friends. */
const val PRIVATE_TABLE = "invite:"

/** A round that has just ended, ready to be written down. */
data class FinishedRound(
    val game: Game,
    val tableId: String,
    val staked: Long,
    val net: Long,
    val outcome: RoundOutcome,
    val detail: JsonObject,
) {
    /**
     * Whether the round counts towards the leagues. One played at a private
     * table does not: friends at a table of their own could hand chips to one
     * another, and a standing won that way would be worth nothing.
     */
    val ranked: Boolean get() = !tableId.startsWith(PRIVATE_TABLE)
}

/** [reference] is what the entry was for, where a reason alone does not say: which mission, on which day. */
data class LedgerEntry(val amount: Long, val reason: LedgerReason, val at: Instant, val reference: String? = null)

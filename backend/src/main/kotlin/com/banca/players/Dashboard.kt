package com.banca.players

import kotlinx.serialization.Serializable

/** Everything the player dashboard shows, worked out from the player's own record. */
@Serializable
data class Dashboard(
    val player: PlayerCard,
    val bankroll: Bankroll,
    val totals: Totals,
    val streaks: Streaks,
    val games: List<GameBreakdown>,
    val activity: List<DayActivity>,
    val achievements: List<Achievement>,
    val recent: List<RecentRound>,
    val rewards: RewardStatus,
    /** The league the player is in, or null for a guest, who is in none. */
    val league: String?,
    val trophies: List<TrophyView>,
)

/** A trophy as it is shown: "Bronze Champion", won in the week beginning on [week]. */
@Serializable
data class TrophyView(val league: String, val position: Int, val title: String, val week: String, val prize: Long)

/**
 * What anyone may see of a player who has signed in: who they are, how far
 * they have come, and what they have won. Nothing about their chips or how
 * they play.
 */
@Serializable
data class PublicProfile(
    val name: String,
    val memberSince: String,
    val level: Int,
    val title: String,
    val league: String,
    val rounds: Int,
    val trophies: List<TrophyView>,
    val achievements: Int,
    val achievementsInAll: Int,
)

@Serializable
data class PlayerCard(
    val name: String,
    /** True when the profile is saved to an account, false for a guest. */
    val signedIn: Boolean,
    val memberSince: String,
    val level: Int,
    val title: String,
    val xp: Long,
    /** The experience at which this level began and at which the next begins. */
    val levelStart: Long,
    val nextLevelAt: Long,
)

@Serializable
data class Bankroll(
    val balance: Long,
    /** Won or lost at the tables, all told. */
    val net: Long,
    /** Chips given rather than won: the opening grant and any top-ups. */
    val granted: Long,
    val peak: Long,
    val history: List<BankrollPoint>,
)

@Serializable
data class BankrollPoint(val at: String, val balance: Long)

/** Figures that need rounds to mean anything are null until there are some. */
@Serializable
data class Totals(
    val rounds: Int,
    val wins: Int,
    val losses: Int,
    val pushes: Int,
    val winRate: Double?,
    val biggestWin: Long?,
    val biggestLoss: Long?,
    val averageResult: Double?,
    val staked: Long,
    /** The largest poker pot the player has taken down. */
    val biggestPot: Long?,
)

@Serializable
data class Streaks(
    /** "win" or "loss", or null before any round is decided. Pushes neither extend nor end a streak. */
    val currentKind: String?,
    val current: Int,
    val bestWin: Int,
    val worstLoss: Int,
)

@Serializable
data class GameBreakdown(
    val game: String,
    val rounds: Int,
    val wins: Int,
    val losses: Int,
    val pushes: Int,
    val winRate: Double?,
    val net: Long,
    val biggestWin: Long?,
    /** How the player tends to play this game, each shown only once there is enough to go on. */
    val tendencies: List<Tendency>,
)

@Serializable
data class Tendency(val label: String, val value: String, val basis: String)

@Serializable
data class DayActivity(val date: String, val rounds: Int, val net: Long)

@Serializable
data class Achievement(
    val id: String,
    val name: String,
    val description: String,
    val earned: Boolean,
    val progress: Long,
    val target: Long,
)

@Serializable
data class RecentRound(val game: String, val at: String, val net: Long, val outcome: String, val summary: String)

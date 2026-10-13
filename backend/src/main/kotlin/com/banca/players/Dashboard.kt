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
    /** Today's missions, and how far along the player is with each. */
    val missions: MissionsStatus,
    /** The league the player is in, or null for a guest, who is in none. */
    val league: String?,
    val trophies: List<TrophyView>,
    /** How the player stands against Banca at poker, one against one, or null before they have played it. */
    val rivalry: Rivalry? = null,
    /** The titles there are to wear, and which of them the player has earned. */
    val titles: List<TitleView> = emptyList(),
    /** The rare things that have happened to the player at the tables, newest first. */
    val moments: List<MomentView> = emptyList(),
    /** Banca's report on the player's week, or null when there has not been enough play to report on. */
    val report: WeeklyReport? = null,
)

/**
 * The record between one player and Banca, from every hand of poker the two
 * played with nobody else in it. [net] is the player's side of it: above
 * nought and they are ahead.
 */
@Serializable
data class Rivalry(
    val hands: Int,
    val won: Int,
    val lost: Int,
    val even: Int,
    val net: Long,
    /** The biggest pot each has taken off the other. */
    val biggestPotWon: Long?,
    val biggestPotLost: Long?,
    val showdowns: Int,
    val showdownsWon: Int,
    /** The most hands the player has won in a row. */
    val bestRun: Int,
    /** What has come of Banca's bluffs, or null when no hand on record says whether it bluffed. */
    val bluffs: Bluffs?,
)

/**
 * Banca's bluffs against one player. [hands] is how many hands are known
 * about, which is fewer than all of them: older hands did not record it.
 * A bluff is [caught] when the player called it down and won, and it [worked]
 * when they folded to it. [biggest] is the largest pot taken that way.
 */
@Serializable
data class Bluffs(val hands: Int, val tried: Int, val caught: Int, val worked: Int, val biggest: Long?)

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
    /** The title they have chosen to wear, or null for none. */
    val wearing: String? = null,
)

@Serializable
data class PlayerCard(
    val name: String,
    /** The title they have chosen to wear beside their name, or null for none. Not the same as [title], which only names their level. */
    val wearing: String? = null,
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

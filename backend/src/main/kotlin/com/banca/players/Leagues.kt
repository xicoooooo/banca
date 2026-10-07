package com.banca.players

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** How one signed-in player did over a stretch of time, and the league they were in. */
data class Standing(val playerId: UUID, val name: String, val tier: Int, val net: Long, val rounds: Int)

enum class LeagueOutcome {
    PROMOTED,
    STAYED,
    DEMOTED,
}

/** What a finished week came to for one player. */
data class LeagueResult(
    val playerId: UUID,
    /** The tier they played the week in. */
    val tier: Int,
    /** Where they finished among those who played, from 1, or 0 for someone who did not play. */
    val position: Int,
    val net: Long,
    val rounds: Int,
    val outcome: LeagueOutcome,
    val prize: Long,
) {
    /** The tier they hold once the week is settled. */
    val nextTier: Int
        get() = when (outcome) {
            LeagueOutcome.PROMOTED -> tier + 1
            LeagueOutcome.DEMOTED -> tier - 1
            LeagueOutcome.STAYED -> tier
        }
}

/** A top-three finish that was paid, kept for good: which league, which place, and the week it was won in. */
data class Trophy(val week: LocalDate, val tier: Int, val position: Int, val prize: Long)

/** Where a player stands in their league as the week goes on. */
enum class Zone {
    /** On course to go up when the week ends. */
    PROMOTION,
    SAFE,
    /** On course to go down. */
    DEMOTION,
}

/**
 * The rules of the weekly leagues.
 *
 * Signed-in players are ranked, within their league, by what they won at the
 * tables that week. When the week ends the best go up a league, the worst and
 * the absent go down, and the top three are paid a prize. All of it is decided
 * here by pure functions of the week's standings, so a week settles the same
 * way whenever, and by whichever server, it comes to be settled.
 */
object Leagues {

    val TIERS = listOf("Bronze", "Silver", "Gold", "Platinum", "Emerald")

    /** How many go up from each league each week. */
    const val PROMOTED = 3

    /** How many go down from a league with enough players in it to spare them. */
    const val DEMOTED = 3

    /** Fewest players who must have played in a league for anyone who played to be sent down from it. */
    const val DEMOTION_NEEDS = 10

    /** Fewest rounds in the week to go up or be paid, so a single lucky hand is not a promotion. */
    const val MIN_ROUNDS = 10

    private val PRIZES = listOf(1_000L, 500L, 250L)

    /** The prize for finishing in [position] in a league: more the higher the league. */
    fun prizeFor(tier: Int, position: Int): Long = PRIZES.getOrNull(position - 1)?.let { it * (tier + 1) } ?: 0

    /** The Monday that begins the week [moment] falls in. Weeks are counted in UTC, the same for every player. */
    fun weekOf(moment: Instant): LocalDate =
        moment.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun startOf(week: LocalDate): Instant = week.atStartOfDay(ZoneOffset.UTC).toInstant()

    fun endOf(week: LocalDate): Instant = startOf(week.plusWeeks(1))

    /**
     * The players of one league in order: those who played, by what they won,
     * and then those who did not. Ties go to whoever played more, since the
     * same result from more rounds was the harder one to hold.
     */
    fun ranked(tier: List<Standing>): List<Standing> =
        tier.sortedWith(
            compareByDescending<Standing> { it.rounds > 0 }
                .thenByDescending { it.net }
                .thenByDescending { it.rounds }
                .thenBy { it.name }
                .thenBy { it.playerId },
        )

    /** Whether a player who finished the week like this goes up. */
    private fun earnsPromotion(standing: Standing, position: Int): Boolean =
        standing.tier < TIERS.lastIndex && position in 1..PROMOTED && standing.net > 0 && standing.rounds >= MIN_ROUNDS

    /** Whether they go down: by not playing at all, or by finishing last in a league with players to spare. */
    private fun facesDemotion(standing: Standing, position: Int, played: Int): Boolean = when {
        standing.tier == 0 -> false
        standing.rounds == 0 -> true
        else -> played >= DEMOTION_NEEDS && position > played - DEMOTED
    }

    /** Where each player of one league stands right now, in ranked order. */
    fun zones(tier: List<Standing>): List<Pair<Standing, Zone>> {
        val order = ranked(tier)
        val played = order.count { it.rounds > 0 }
        return order.mapIndexed { index, standing ->
            val position = if (standing.rounds > 0) index + 1 else 0
            standing to when {
                earnsPromotion(standing, position) -> Zone.PROMOTION
                facesDemotion(standing, position, played) -> Zone.DEMOTION
                else -> Zone.SAFE
            }
        }
    }

    /** Settles a week: for every signed-in player, where they finished, whether they move, and what they are paid. */
    fun settle(standings: List<Standing>): List<LeagueResult> =
        standings.groupBy { it.tier.coerceIn(0, TIERS.lastIndex) }.flatMap { (tier, players) ->
            zones(players.map { it.copy(tier = tier) }).mapIndexed { index, (standing, zone) ->
                val position = if (standing.rounds > 0) index + 1 else 0
                LeagueResult(
                    playerId = standing.playerId,
                    tier = tier,
                    position = position,
                    net = standing.net,
                    rounds = standing.rounds,
                    outcome = when (zone) {
                        Zone.PROMOTION -> LeagueOutcome.PROMOTED
                        Zone.DEMOTION -> LeagueOutcome.DEMOTED
                        Zone.SAFE -> LeagueOutcome.STAYED
                    },
                    prize = if (standing.net > 0 && standing.rounds >= MIN_ROUNDS) prizeFor(tier, position) else 0,
                )
            }
        }
}

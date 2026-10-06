package com.banca.players

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The chips a player can come by without winning them, and when. */
@Serializable
data class RewardStatus(val daily: DailyReward, val rescue: Rescue)

@Serializable
data class DailyReward(
    /** True when today's reward has not been claimed yet. */
    val available: Boolean,
    /** What the next claim is worth, and which day of the week of rewards it is, from 1. */
    val amount: Long,
    val day: Int,
    /** Days claimed in a row, counting today if claimed. Nought once a day has been missed. */
    val streak: Int,
    /** When the next claim opens, or null when one is open now. */
    val nextAt: String?,
    /** What each day of the week of rewards is worth, in order. */
    val ladder: List<Long>,
)

@Serializable
data class Rescue(
    val amount: Long,
    /** When the house will next stake a player who is out of chips, or null when it would now. */
    val nextAt: String?,
)

/**
 * The rules for chips given rather than won. Chips cannot be bought, so these
 * are the only ways back for a player who has lost theirs, and they are kept
 * small and slow enough that losing still costs something.
 *
 * Nothing here is stored. Both are worked out from the ledger, which already
 * says when each reward was paid.
 */
object Rewards {

    /** A week of daily rewards, growing each day claimed in a row, then starting over. */
    val LADDER = listOf(200L, 300L, 400L, 500L, 750L, 1_000L, 2_000L)

    /** What the house stakes a player who cannot cover the smallest bet. */
    const val RESCUE = 500L

    /** How long the house waits before staking the same player again. */
    val RESCUE_EVERY: Duration = Duration.ofHours(4)

    /** Days are counted in UTC, the same for every player. */
    fun dayOf(moment: Instant): LocalDate = moment.atZone(ZoneOffset.UTC).toLocalDate()

    fun startOfDay(day: LocalDate): Instant = day.atStartOfDay(ZoneOffset.UTC).toInstant()

    /** [dailyClaims] are the moments daily rewards were paid, newest first. */
    fun status(dailyClaims: List<Instant>, lastRescue: Instant?, now: Instant): RewardStatus {
        val today = dayOf(now)
        val days = dailyClaims.map(::dayOf).distinct()
        val claimedToday = days.firstOrNull() == today

        // A streak is alive if it reaches today or yesterday; a missed day ends it.
        var streak = 0
        if (days.isNotEmpty() && !days.first().isBefore(today.minusDays(1))) {
            var expected = days.first()
            for (day in days) {
                if (day != expected) break
                streak++
                expected = expected.minusDays(1)
            }
        }

        val day = streak % LADDER.size + 1
        val rescueAt = lastRescue?.plus(RESCUE_EVERY)?.takeIf { it.isAfter(now) }

        return RewardStatus(
            daily = DailyReward(
                available = !claimedToday,
                amount = LADDER[day - 1],
                day = day,
                streak = streak,
                nextAt = if (claimedToday) startOfDay(today.plusDays(1)).toString() else null,
                ladder = LADDER,
            ),
            rescue = Rescue(amount = RESCUE, nextAt = rescueAt?.toString()),
        )
    }
}

/** Whether a player can sit down to a round that needs a given number of chips. */
sealed interface Funding {
    val balance: Long

    /** They have enough. */
    data class Ready(override val balance: Long) : Funding

    /** They did not, and the house has staked them [amount]. */
    data class Staked(override val balance: Long, val amount: Long) : Funding

    /** They do not, and the house will not stake them again yet. */
    data class Broke(override val balance: Long, val dailyReady: Boolean, val nextChipsAt: Instant) : Funding
}

package com.banca.players

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

/**
 * Something to do at the tables today, and how to tell how far along a player
 * is with it from the rounds they have played. [target] is how much of it
 * there is to do, and [reward] the chips for doing it.
 */
class Mission(
    val id: String,
    val title: String,
    val detail: String,
    val target: Long,
    val reward: Long,
    val progress: (List<RoundRecord>) -> Long,
)

/** A mission as the player is shown it. [slot] is which of the day's three it is. */
@Serializable
data class MissionView(
    val slot: Int,
    val title: String,
    val detail: String,
    val progress: Long,
    val target: Long,
    val reward: Long,
    /** Done, and waiting to be claimed. */
    val ready: Boolean,
    val claimed: Boolean,
)

/** The day's missions, and the bonus for doing all of them. */
@Serializable
data class MissionsStatus(
    val missions: List<MissionView>,
    val bonus: MissionBonus,
    /** When today's missions give way to tomorrow's. */
    val resetsAt: String,
)

@Serializable
data class MissionBonus(val reward: Long, val ready: Boolean, val claimed: Boolean)

/**
 * Daily missions: three a day, different for each player and each day, with a
 * few chips for each and a little more for all three.
 *
 * Nothing about them is stored but the payment. Which three a player has
 * follows from who they are and what day it is, and how far along they are
 * follows from the rounds they have played since midnight, so a mission cannot
 * be out of step with what the player has actually done.
 */
object Missions {
    /** Missions and the bonus are numbered from nought; the bonus comes after the last mission. */
    const val COUNT = 3
    const val BONUS_SLOT = COUNT
    const val BONUS = 250L

    private fun played(game: Game): (List<RoundRecord>) -> Long = { rounds -> rounds.count { it.game == game }.toLong() }
    private fun won(game: Game): (List<RoundRecord>) -> Long = { rounds -> rounds.count { it.game == game && it.outcome == RoundOutcome.WIN }.toLong() }
    private fun RoundRecord.count(key: String): Long = detail[key]?.jsonPrimitive?.longOrNull ?: 0
    private fun RoundRecord.flag(key: String): Boolean = detail[key]?.jsonPrimitive?.booleanOrNull ?: false

    /** Easy: turn up and play. */
    private val WARM_UPS = listOf(
        Mission("play_poker", "Deal me in", "Play 5 hands of poker", 5, 75, played(Game.POKER)),
        Mission("play_blackjack", "Hit me", "Play 8 rounds of blackjack", 8, 75, played(Game.BLACKJACK)),
        Mission("play_roulette", "Round and round", "Play 6 spins of roulette", 6, 75, played(Game.ROULETTE)),
        Mission("play_any", "Regular", "Play 15 rounds, at any game", 15, 75) { it.size.toLong() },
        Mission("play_all", "The grand tour", "Play a round at all three games", 3, 75) { rounds -> rounds.map { it.game }.toSet().size.toLong() },
    )

    /** Harder: come out ahead. */
    private val WINS = listOf(
        Mission("win_poker", "Take the pot", "Win 3 hands of poker", 3, 100, won(Game.POKER)),
        Mission("win_blackjack", "Beat the dealer", "Win 4 rounds of blackjack", 4, 100, won(Game.BLACKJACK)),
        Mission("win_roulette", "Lucky number", "Come out ahead on 3 spins of roulette", 3, 100, won(Game.ROULETTE)),
        Mission("win_big", "A good night", "Win 300 chips or more in a single round", 1, 100) { rounds -> if (rounds.any { it.net >= 300 }) 1 else 0 },
        Mission("win_any", "On a roll", "Win 6 rounds, at any game", 6, 100) { rounds -> rounds.count { it.outcome == RoundOutcome.WIN }.toLong() },
    )

    /** Something particular, that shows off a part of the game. */
    private val FEATS = listOf(
        Mission("by_the_book", "By the book", "Make 6 blackjack decisions that were the best play", 6, 150) { rounds -> rounds.sumOf { it.count("byTheBook") } },
        Mission("ask_coach", "A second opinion", "Ask Banca for advice at blackjack twice", 2, 150) { rounds -> rounds.sumOf { it.count("advised") } },
        Mission("showdown", "Show them", "Win a poker hand at a showdown", 1, 150) { rounds ->
            if (rounds.any { it.game == Game.POKER && it.outcome == RoundOutcome.WIN && it.flag("showdown") }) 1 else 0
        },
        Mission("aggressor", "Take the lead", "Bet or raise 6 times at poker", 6, 150) { rounds ->
            rounds.filter { it.game == Game.POKER }.sumOf { it.count("bets") + it.count("raises") }
        },
        Mission("natural", "Twenty-one", "Be dealt a blackjack", 1, 150) { rounds -> if (rounds.any { it.flag("natural") }) 1 else 0 },
        Mission("straight_up", "On the nose", "Hit a single number at roulette", 1, 150) { rounds -> if (rounds.any { it.flag("straightHit") }) 1 else 0 },
    )

    /** Every mission there is, for anything that needs to look one up or check them all. */
    val ALL: List<Mission> = WARM_UPS + WINS + FEATS

    /**
     * The three missions [playerId] has on [day]: one to play, one to win and
     * one feat. The same player on the same day is always given the same three.
     */
    fun forDay(playerId: UUID, day: LocalDate): List<Mission> {
        val random = Random(playerId.mostSignificantBits xor playerId.leastSignificantBits xor (day.toEpochDay() * 0x9E3779B97F4A7C15uL.toLong()))
        return listOf(WARM_UPS.random(random), WINS.random(random), FEATS.random(random))
    }

    /** How a payment for a mission is marked in the ledger, so that it is made once. */
    fun reference(day: LocalDate, slot: Int): String = "$day:$slot"

    /** Which of the day's missions the given ledger references say have been paid for. */
    fun claimedSlots(day: LocalDate, references: Collection<String>): Set<Int> =
        references.mapNotNull { reference -> reference.substringAfter("$day:", "").toIntOrNull() }.toSet()

    /**
     * Where a player stands with today's missions.
     *
     * @param roundsToday the rounds they have played since the day began
     * @param claimed the slots already paid for, the bonus among them
     */
    fun status(playerId: UUID, now: Instant, roundsToday: List<RoundRecord>, claimed: Set<Int>): MissionsStatus {
        val day = Rewards.dayOf(now)
        val views = forDay(playerId, day).mapIndexed { slot, mission ->
            val progress = mission.progress(roundsToday).coerceIn(0, mission.target)
            val paid = slot in claimed
            MissionView(slot, mission.title, mission.detail, progress, mission.target, mission.reward, ready = progress >= mission.target && !paid, claimed = paid)
        }
        val allDone = views.all { it.progress >= it.target }
        val bonusPaid = BONUS_SLOT in claimed
        return MissionsStatus(
            missions = views,
            bonus = MissionBonus(BONUS, ready = allDone && !bonusPaid, claimed = bonusPaid),
            resetsAt = Rewards.startOfDay(day.plusDays(1)).toString(),
        )
    }

    /** What claiming [slot] is worth to a player who stands as [status] says, or null if there is nothing there to claim. */
    fun worth(status: MissionsStatus, slot: Int): Long? = when (slot) {
        BONUS_SLOT -> BONUS.takeIf { status.bonus.ready }
        else -> status.missions.getOrNull(slot)?.takeIf { it.ready }?.reward
    }
}

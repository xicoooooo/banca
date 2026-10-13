package com.banca.players

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** One thing the report says: about a [game], or about all of them when that is null. */
@Serializable
data class ReportNote(val game: String?, val title: String, val text: String)

/**
 * The one thing to work on. [go] is where in the app to do it, as the address
 * a screen is reached at ("poker", "blackjack", "blackjack/drill"), or null
 * when there is nowhere in particular to send the player.
 */
@Serializable
data class ReportFocus(val title: String, val text: String, val go: String?)

/**
 * A week of a player's play, read back to them. [week] is the Monday it began
 * on, and [current] whether it is the week still being played.
 */
@Serializable
data class WeeklyReport(
    val week: String,
    val current: Boolean,
    val rounds: Int,
    val net: Long,
    val notes: List<ReportNote>,
    val focus: ReportFocus?,
)

/**
 * Banca's report on a player's week.
 *
 * It is read off the rounds as they were written down, by rules set out
 * here, and no model is asked for it: the figures are the player's own, and
 * what is said about them is what a coach would say on seeing them. It is the
 * week in play once there is enough of it to say something about, and the
 * week before until then.
 */
object CoachReport {

    /** Fewest rounds in a week before there is a report on it. */
    const val ENOUGH_ROUNDS = 10

    /** Fewest rounds of one game in a week before anything is said about it. */
    private const val ENOUGH_OF_A_GAME = 5

    /** Fewest decisions or actions before a share of them is worth quoting. */
    private const val ENOUGH_TO_QUOTE = 10

    /** The wheel's take: one pocket in thirty-seven. */
    private const val HOUSE_EDGE = 1.0 / 37

    /**
     * @param rounds newest first
     */
    fun of(rounds: List<RoundRecord>, now: Instant): WeeklyReport? {
        val thisWeek = Leagues.weekOf(now)
        val byWeek = rounds.groupBy { Leagues.weekOf(it.endedAt) }
        val week = listOf(thisWeek, thisWeek.minusWeeks(1)).firstOrNull { (byWeek[it]?.size ?: 0) >= ENOUGH_ROUNDS } ?: return null
        return report(week, current = week == thisWeek, played = byWeek.getValue(week), before = byWeek[week.minusWeeks(1)].orEmpty())
    }

    private fun report(week: LocalDate, current: Boolean, played: List<RoundRecord>, before: List<RoundRecord>): WeeklyReport {
        val blackjack = Blackjack(played.filter { it.game == Game.BLACKJACK })
        val poker = Poker(played.filter { it.game == Game.POKER })
        val roulette = Roulette(played.filter { it.game == Game.ROULETTE })
        val blackjackBefore = Blackjack(before.filter { it.game == Game.BLACKJACK })

        return WeeklyReport(
            week = week.toString(),
            current = current,
            rounds = played.size,
            net = played.sumOf { it.net },
            notes = listOfNotNull(poker.note(), blackjack.note(blackjackBefore), roulette.note()),
            focus = focus(played.size, poker, blackjack, roulette),
        )
    }

    /** The first of these that applies, the costliest habits first. */
    private fun focus(rounds: Int, poker: Poker, blackjack: Blackjack, roulette: Roulette): ReportFocus? = when {
        blackjack.insured >= 2 -> ReportFocus(
            "Leave insurance alone",
            "You took insurance ${times(blackjack.insured)}. It pays only when the dealer has a ten underneath, which is 4 times in 13, " +
                "and loses about 4 chips of every 100 staked on the hand. Declining is always the better play.",
            go = "blackjack",
        )
        blackjack.decisions >= ENOUGH_TO_QUOTE && blackjack.accuracy < 0.85 -> ReportFocus(
            "Tighten up at blackjack",
            "${percent(blackjack.accuracy)} of your blackjack decisions were the best play. A drill is ten of the hard hands, " +
                "with the right play and the reason straight after each.",
            go = "blackjack/drill",
        )
        poker.calls >= ENOUGH_TO_QUOTE - 1 && poker.calls >= 3 * poker.pushes -> ReportFocus(
            "Bet more, call less",
            "At Hold'em you called ${times(poker.calls)} and bet or raised ${times(poker.pushes)}. A call can only win at a showdown. " +
                "A bet can win there too, and it can also win when the other player folds.",
            go = "poker",
        )
        poker.hands >= 15 && poker.foldShare > 0.65 -> ReportFocus(
            "Give up fewer hands",
            "You folded ${percent(poker.foldShare)} of your Hold'em hands. Banca bluffs, and says so once the hand is over: " +
                "open its reasoning after a fold and see how often you were ahead.",
            go = "poker",
        )
        poker.hands >= 15 && poker.foldShare < 0.2 -> ReportFocus(
            "Let more hands go",
            "You folded only ${percent(poker.foldShare)} of your Hold'em hands. Not every hand is worth chips, " +
                "and the ones given up early are the cheapest ones to lose.",
            go = "poker",
        )
        roulette.spins > rounds * 0.6 && roulette.net < 0 -> ReportFocus(
            "Try a game where the play matters",
            "Most of your week was at the wheel, where no layout changes the odds. At blackjack the decisions are yours, " +
                "and played by the book the house keeps far less.",
            go = "blackjack",
        )
        blackjack.decisions >= ENOUGH_TO_QUOTE && blackjack.accuracy >= 0.95 -> ReportFocus(
            "Nothing to correct at blackjack",
            "${percent(blackjack.accuracy)} of your decisions were the best play. That is as well as the game can be played; the rest is the cards.",
            go = null,
        )
        else -> null
    }

    private class Blackjack(val rounds: List<RoundRecord>) {
        val decisions = rounds.sumOf { it.detail.count("decisions") }
        val byTheBook = rounds.sumOf { it.detail.count("byTheBook") }
        val accuracy = if (decisions > 0) byTheBook.toDouble() / decisions else 0.0
        val givenUp = rounds.sumOf { it.detail["givenUp"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }
        val insured = rounds.count { it.detail.flag("insured") }
        val net = rounds.sumOf { it.net }

        fun note(before: Blackjack): ReportNote? {
            if (rounds.size < ENOUGH_OF_A_GAME) return null
            if (decisions < ENOUGH_TO_QUOTE) {
                return ReportNote("blackjack", "Blackjack · ${signed(net)} over ${rounds.size} rounds", "Too few decisions this week to say how you are playing them.")
            }
            val compared = if (before.decisions < ENOUGH_TO_QUOTE) "" else {
                val was = (before.accuracy * 100).roundToInt()
                val now = (accuracy * 100).roundToInt()
                when {
                    now > was -> ", up from $was% the week before"
                    now < was -> ", down from $was% the week before"
                    else -> ", the same as the week before"
                }
            }
            val cost = if (byTheBook == decisions) " Nothing was given up." else " The others gave up about ${chips(givenUp.roundToLong())} chips between them."
            return ReportNote(
                "blackjack",
                "Blackjack · ${percent(accuracy)} by the book",
                "$byTheBook of your $decisions decisions were the best play$compared.$cost You came out at ${signed(net)} over ${rounds.size} rounds.",
            )
        }
    }

    private class Poker(val rounds: List<RoundRecord>) {
        val hands = rounds.size
        val folds = rounds.count { it.detail.flag("folded") }
        val foldShare = if (hands > 0) folds.toDouble() / hands else 0.0
        val showdowns = rounds.filter { it.detail.flag("showdown") }
        val calls = rounds.sumOf { it.detail.count("calls") }
        val pushes = rounds.sumOf { it.detail.count("bets") + it.detail.count("raises") }
        val net = rounds.sumOf { it.net }

        fun note(): ReportNote? {
            if (hands < ENOUGH_OF_A_GAME) return null
            val shown = when (showdowns.size) {
                0 -> "saw no showdown"
                else -> "won ${showdowns.count { it.outcome == RoundOutcome.WIN }} of ${showdowns.size} ${if (showdowns.size == 1) "showdown" else "showdowns"}"
            }
            val caught = rounds.count { it.detail.flag("bancaBluffed") && it.detail.flag("showdown") && it.outcome == RoundOutcome.WIN }
            val bluffs = if (caught > 0) " You caught Banca bluffing ${times(caught)}." else ""
            return ReportNote(
                "poker",
                "Hold'em · ${signed(net)} over $hands hands",
                "You folded $folds of them and $shown. You called ${times(calls)} and bet or raised ${times(pushes)}.$bluffs",
            )
        }
    }

    private class Roulette(val rounds: List<RoundRecord>) {
        val spins = rounds.size
        val staked = rounds.sumOf { it.staked }
        val net = rounds.sumOf { it.net }

        fun note(): ReportNote? {
            if (spins < ENOUGH_OF_A_GAME) return null
            val expected = (staked * HOUSE_EDGE).roundToLong()
            return ReportNote(
                "roulette",
                "Roulette · ${signed(net)} over $spins spins",
                "The wheel keeps one pocket in 37 whatever the layout. On the ${chips(staked)} you staked that comes to about ${chips(expected)}, " +
                    "and the rest of the difference is luck, this week ${if (net >= -expected) "on your side" else "against you"}.",
            )
        }
    }

    private fun percent(share: Double): String = "${(share * 100).roundToInt()}%"

    private fun chips(amount: Long): String = "%,d".format(Locale.US, amount)

    private fun signed(amount: Long): String = if (amount == 0L) "even" else "${if (amount > 0) "+" else "−"}${chips(abs(amount))}"

    private fun times(count: Int): String = when (count) {
        0 -> "never"
        1 -> "once"
        2 -> "twice"
        else -> "$count times"
    }
}

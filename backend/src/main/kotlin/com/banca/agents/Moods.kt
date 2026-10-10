package com.banca.agents

import com.banca.games.poker.Action
import kotlin.random.Random

/**
 * A way Banca plays poker for a while. Each is the same player reading the
 * same numbers and drawing different lines through them: how strong a hand
 * must be to bet or raise, how much a bettor is given credit for, and how
 * often to bet with nothing.
 *
 * [told] is what the table is told once a hand is over, when it can no longer
 * give anything away. [rules] is what the model is told to do with the figures.
 */
class Mood(
    val name: String,
    val told: String,
    val bluffs: Double,
    val rules: String,
    /** The same lines as [rules], as numbers, for playing by them when no model can be asked. */
    val lines: Lines,
)

/**
 * Where a mood draws its lines through the figures.
 *
 * [discount] is how much equity is marked down against a bet. Checked to,
 * the hand is bet for half the pot from [betFrom], two thirds from [betMore]
 * and the whole pot from [betPot]. Facing a bet it is raised when what is left
 * of its equity passes [raiseFrom], and called when it beats the price of
 * calling by [callBy], which may be less than nought. [raisePot] raises by the
 * pot instead of half of it, and [traps] plays the strongest hands slowly
 * until the river.
 */
class Lines(
    val discount: Double,
    val betFrom: Double,
    val betMore: Double,
    val betPot: Double,
    val raiseFrom: Double,
    val callBy: Double,
    val raisePot: Boolean = false,
    val traps: Boolean = false,
)

object Moods {
    val STEADY = Mood(
        name = "steady",
        told = "Playing it straight: betting good hands, folding bad ones, and bluffing now and then.",
        bluffs = 0.18,
        rules = """
            An opponent who bets or raises usually holds better than random, so when you face a bet, treat your equity as about 0.10 lower than the tool says.

            When you can check (nothing to call):
            - Equity above 0.65: bet two thirds of the pot, or the whole pot with equity above 0.80.
            - Equity 0.50 to 0.65: bet half the pot.
            - Equity below 0.50: check.
            - Before the flop, with equity above 0.55, raise rather than just check.

            When you face a bet:
            - Adjusted equity above 0.70: raise, to the half-pot or pot amount.
            - Adjusted equity above the pot odds: call.
            - Otherwise fold. Do not call just because the bet is small.
        """,
        lines = Lines(discount = 0.10, betFrom = 0.50, betMore = 0.65, betPot = 0.80, raiseFrom = 0.70, callBy = 0.0),
    )

    val PATIENT = Mood(
        name = "patient",
        told = "In a patient mood: waiting for strong hands, giving bets a lot of respect, and hardly bluffing.",
        bluffs = 0.04,
        rules = """
            You are playing tight. An opponent who bets or raises is given a lot of credit: when you face a bet, treat your equity as about 0.14 lower than the tool says.

            When you can check (nothing to call):
            - Equity above 0.72: bet two thirds of the pot.
            - Anything less: check. You are in no hurry.
            - Before the flop, raise only with equity above 0.62.

            When you face a bet:
            - Adjusted equity above 0.78: raise to the half-pot amount.
            - Adjusted equity at least 0.05 above the pot odds: call.
            - Otherwise fold, and do not mind folding often.
        """,
        lines = Lines(discount = 0.14, betFrom = 0.72, betMore = 0.72, betPot = 2.0, raiseFrom = 0.78, callBy = 0.05),
    )

    val PRESSING = Mood(
        name = "pressing",
        told = "In a pressing mood: betting and raising with more hands than usual, and making it cost to stay in.",
        bluffs = 0.32,
        rules = """
            You are playing aggressively and putting opponents to decisions. When you face a bet, treat your equity as only about 0.06 lower than the tool says.

            When you can check (nothing to call):
            - Equity above 0.75: bet the whole pot.
            - Equity 0.55 to 0.75: bet two thirds of the pot.
            - Equity 0.45 to 0.55: bet half the pot.
            - Below 0.45: check.
            - Before the flop, raise with equity above 0.50.

            When you face a bet:
            - Adjusted equity above 0.60: raise, to the pot amount.
            - Adjusted equity within 0.03 of the pot odds or better: call.
            - Otherwise fold.
        """,
        lines = Lines(discount = 0.06, betFrom = 0.45, betMore = 0.55, betPot = 0.75, raiseFrom = 0.60, callBy = -0.03, raisePot = true),
    )

    val SLY = Mood(
        name = "sly",
        told = "In a sly mood: hiding strong hands behind checks and calls, and betting big when it looked weak.",
        bluffs = 0.26,
        rules = """
            You are playing deceptively, so that your bets say little about your cards. When you face a bet, treat your equity as about 0.10 lower than the tool says.

            When you can check (nothing to call):
            - Equity above 0.80 before the river: check, to let an opponent bet into you. On the river, bet the whole pot.
            - Equity 0.60 to 0.80: bet half the pot.
            - Equity 0.45 to 0.60: check.
            - Below 0.45: check.

            When you face a bet:
            - Adjusted equity above 0.80 before the river: just call, and keep them betting. On the river, raise to the pot amount.
            - Adjusted equity above 0.65: raise to the half-pot amount.
            - Adjusted equity above the pot odds: call.
            - Otherwise fold.
        """,
        lines = Lines(discount = 0.10, betFrom = 0.60, betMore = 2.0, betPot = 2.0, raiseFrom = 0.65, callBy = 0.0, traps = true),
    )

    /** Steady most often, so that the others are departures from something. */
    private val WEIGHTED = listOf(STEADY to 4, PRESSING to 3, PATIENT to 2, SLY to 2)

    val ALL: List<Mood> = WEIGHTED.map { it.first }

    /** Draws a mood other than [not], by weight. */
    fun draw(random: Random, not: Mood? = null): Mood {
        val from = WEIGHTED.filter { it.first !== not }
        var at = random.nextInt(from.sumOf { it.second })
        for ((mood, weight) in from) {
            if (at < weight) return mood
            at -= weight
        }
        return from.last().first
    }
}

/**
 * Banca's mood at one table, which holds for a few hands and then changes. A
 * player who has worked out how Banca is playing has a little while to use
 * it, and no way of knowing when it will stop being true.
 */
class MoodSwings(private val random: Random, private val stay: IntRange = 2..5) {
    private var mood = Moods.draw(random)
    private var hand = -1
    private var handsLeft = random.nextInt(stay.first, stay.last + 1)

    /** The mood as it stands, without a hand being counted against it. */
    val now: Mood get() = mood

    /** The mood for the hand numbered [handNumber], and whether this is the first it has been asked about that hand. */
    fun forHand(handNumber: Int): Pair<Mood, Boolean> {
        if (handNumber == hand) return mood to false
        // The first hand at a table is played in the mood the table began with.
        if (hand != -1 && --handsLeft <= 0) {
            mood = Moods.draw(random, not = mood)
            handsLeft = random.nextInt(stay.first, stay.last + 1)
        }
        hand = handNumber
        return mood to true
    }
}

/**
 * Banca playing by its lines alone, with no model to ask.
 *
 * A free model has only so much to give in a minute, and a table must not
 * stall or go limp when that runs out. So every mood can also be played
 * straight from its numbers. It is the same player making the same kind of
 * decision from the same figures, with nobody to put it into words.
 */
object ByTheLines {
    private const val TRAP_FROM = 0.80

    /**
     * What [mood] does with the hand [tools] describes. [bluffing] is whether
     * this is a turn the dice said to bluff on, and [river] whether there are
     * no more cards to come.
     */
    fun play(tools: PokerTools, legal: com.banca.sessions.LegalView, mood: Mood, bluffing: Boolean, river: Boolean): Action {
        val lines = mood.lines
        val equity = tools.equity
        val half = tools.sized(0.5)

        if (legal.canCheck) {
            val bet = when {
                // A trap is sprung on the river, and laid before it.
                lines.traps && equity > TRAP_FROM -> if (river) tools.sized(1.0) else null
                equity >= lines.betPot -> tools.sized(1.0)
                equity >= lines.betMore -> tools.sized(2.0 / 3)
                equity >= lines.betFrom -> half
                bluffing -> half
                else -> null
            }
            return bet ?: Action.Check
        }

        val left = equity - lines.discount
        val price = tools.priceOfCalling
        val raise = if (legal.canRaise) tools.sized(if (lines.raisePot) 1.0 else 0.5) else null
        return when {
            lines.traps && left > TRAP_FROM -> if (river && raise != null) tools.sized(1.0) ?: Action.Call else Action.Call
            left > lines.raiseFrom && raise != null -> raise
            left >= price + lines.callBy -> Action.Call
            // A bluff is a raise into a bet that is not too big to push back at.
            bluffing && raise != null && price <= 1.0 / 3 -> tools.sized(0.5) ?: Action.Fold
            else -> Action.Fold
        }
    }
}

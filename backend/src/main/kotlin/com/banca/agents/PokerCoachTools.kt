package com.banca.agents

import com.banca.sessions.TableView
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The numbers behind a piece of poker advice, as the player is shown them.
 * [equity] is how often their hand wins against [opponents] random hands,
 * [againstABet] the same marked down for facing someone who has bet, which is
 * null when nobody has, and [potOdds] how often a call must win to pay.
 */
@Serializable
data class PokerFigures(
    val equity: Double,
    val againstABet: Double?,
    val opponents: Int,
    val potOdds: Double,
    val pot: Long,
    val callCost: Long,
)

/**
 * What the coach tells a poker player. [amount] is the total to have in front
 * of them, for a bet or a raise. [source] is "banca" when the model put it
 * into words and "book" when the rule of thumb had to speak for itself.
 */
@Serializable
data class PokerAdvice(val action: String, val amount: Long?, val reason: String, val figures: PokerFigures, val source: String)

/**
 * The tools a poker coach may use for one decision: the same four the
 * opponent reads the table through, built from the asking player's own view,
 * and one to give its advice with.
 *
 * Poker has no play that is simply right, so advice is not checked against a
 * best answer as it is at blackjack. It is checked against the figures: it
 * must be open to the player, and it must not be a play the numbers plainly
 * speak against, such as folding for nothing or paying far more than the hand
 * is worth. Anything inside those bounds is the coach's to choose.
 */
class PokerCoachTools(private val view: TableView, random: Random = Random.Default, equityIterations: Int = 3_000) {

    val table = PokerTools(view, random, equityIterations)

    private val legal = view.legal ?: error("There is no decision to advise on")
    private val me = view.players.single { it.seat == view.yourSeat }

    private val equity: Double get() = table.equity
    private val potOdds: Double = PokerMath.potOdds(view.pot, legal.callCost)

    /** A bettor's hands run better than random ones, so equity counts for less against a bet. */
    private val againstABet: Double get() = equity - BETTOR_DISCOUNT

    val figures: PokerFigures
        get() = PokerFigures(
            equity = equity.rounded(),
            againstABet = againstABet.coerceAtLeast(0.0).rounded().takeIf { legal.callCost > 0 },
            opponents = table.opponents,
            potOdds = potOdds.rounded(),
            pot = view.pot,
            callCost = legal.callCost,
        )

    var advice: PokerAdvice? = null
        private set

    /** Why [action] cannot be advised, said so a model can try again, or null when it can. */
    fun objection(action: String): String? = when (action) {
        "fold" -> when {
            legal.canCheck -> "checking is free, so folding only throws the hand away. Advise check or bet."
            againstABet - potOdds > CLEAR_MARGIN ->
                "the hand wins about ${percent(equity)}% of the time and a call needs only ${percent(potOdds)}%. That is too good to fold."
            else -> null
        }
        "check" -> if (legal.canCheck) null else "there is a bet to call, so checking is not open. Advise fold, call or raise."
        "call" -> when {
            !legal.canCall -> "there is nothing to call."
            potOdds - againstABet > CLEAR_MARGIN ->
                "the hand wins about ${percent(equity)}% of the time against random hands and a call needs ${percent(potOdds)}%. That is too dear."
            else -> null
        }
        "bet" -> when {
            !legal.canBet -> "a bet is not open. ${if (legal.canRaise) "Advise raise, call or fold." else "Advise check."}"
            equity < BET_NEEDS -> "the hand wins only about ${percent(equity)}% of the time, so a bet would be a bluff. Advise check."
            else -> null
        }
        "raise" -> when {
            !legal.canRaise -> "a raise is not open. ${if (legal.canBet) "Advise bet or check." else "Advise call or fold."}"
            againstABet < RAISE_NEEDS -> "the hand wins only about ${percent(equity)}% of the time, which is not enough to raise. Advise call or fold."
            else -> null
        }
        else -> "unknown action '$action'. Use fold, check, call, bet or raise."
    }

    /** Records the advice, or explains why it cannot stand so the model can try again. */
    fun advise(arguments: JsonObject?): String {
        val name = arguments?.get("action")?.jsonPrimitive?.contentOrNull?.lowercase()?.trim()
            ?: return "Error: give an action, one of fold, check, call, bet, raise."
        objection(name)?.let { return "Error: $it" }

        val reason = arguments["reason"]?.jsonPrimitive?.contentOrNull?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (reason.isEmpty()) return "Error: give a reason, one sentence the player can follow."

        val wanted = arguments["amount"]?.jsonPrimitive?.longOrNull
        advice = PokerAdvice(name, amountFor(name, wanted), reason.take(MAX_REASON), figures, source = "banca")
        return "Accepted."
    }

    /** A size is kept inside what the table allows, and given one if the coach left it out. */
    private fun amountFor(action: String, wanted: Long?): Long? = when (action) {
        "bet" -> (wanted ?: (view.pot * 2 / 3)).coerceIn(legal.minBet, legal.maxTo)
        "raise" -> (wanted ?: (me.committed + legal.callCost + (view.pot + legal.callCost) / 2)).coerceIn(legal.minRaiseTo, legal.maxTo)
        else -> null
    }

    /** The rule of thumb for these numbers, with a reason made from them. */
    fun bookAdvice(): PokerAdvice {
        val wins = percent(equity)
        val needs = percent(potOdds)
        val against = if (table.opponents == 1) "one random hand" else "${table.opponents} random hands"

        val (action, amount, reason) = when {
            legal.canCheck && legal.canBet && equity > 0.65 -> Triple(
                "bet", view.pot * 2 / 3,
                "You win about $wins% of the time against $against, so it pays to build the pot while you are ahead.",
            )
            legal.canCheck && legal.canBet && equity >= 0.50 -> Triple(
                "bet", view.pot / 2,
                "You win about $wins% of the time against $against: ahead more often than not, so a smaller bet is worth making.",
            )
            legal.canCheck -> Triple(
                "check", null,
                "You win about $wins% of the time against $against, which is not enough to bet on. Checking costs nothing.",
            )
            legal.canRaise && againstABet > 0.70 -> Triple(
                "raise", me.committed + legal.callCost + (view.pot + legal.callCost) / 2,
                "You win about $wins% of the time against $against. That is strong enough to raise rather than only call.",
            )
            legal.canCall && againstABet > potOdds -> Triple(
                "call", null,
                "Calling costs ${legal.callCost} to win a pot of ${view.pot}, so it pays if you win $needs% of the time. Your $wins% covers that, even allowing for a bettor holding better than average.",
            )
            else -> Triple(
                "fold", null,
                "Calling costs ${legal.callCost} to win a pot of ${view.pot}, so it must win $needs% of the time. Your hand wins about $wins% against random hands, and someone betting usually has better.",
            )
        }
        return PokerAdvice(action, amountFor(action, amount), reason, figures, source = "book")
    }

    /** The opponent's reading tools, and in place of its way to act, a way to advise. */
    fun server(): Server = table.server(
        finishingTool = GIVE_ADVICE,
        finishingDescription = "Gives the player your advice. Call it exactly once, after you have looked at the figures.",
        finishingProperties = buildJsonObject {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") { listOf("fold", "check", "call", "bet", "raise").forEach { add(JsonPrimitive(it)) } }
            }
            putJsonObject("amount") {
                put("type", "integer")
                put("description", "Only for bet or raise: the total for the player to have in front of them this street.")
            }
            putJsonObject("reason") {
                put("type", "string")
                put("description", "One sentence for the player, in plain words, using a figure from the tools.")
            }
        },
        finishingRequired = listOf("action", "reason"),
        finish = ::advise,
    )

    private fun Double.rounded(): Double = round(this * 1000) / 1000

    private fun percent(chance: Double): Int = (chance * 100).roundToInt()

    companion object {
        const val GIVE_ADVICE = "give_advice"

        /** How much equity against random hands is marked down when someone has bet. */
        const val BETTOR_DISCOUNT = 0.10

        /** How far the figures must point one way before the other is refused. */
        const val CLEAR_MARGIN = 0.10

        /** Below this a bet is a bluff, which a coach does not advise. */
        const val BET_NEEDS = 0.40

        /** What a hand must be worth against a bet before a raise is advised. */
        const val RAISE_NEEDS = 0.55

        private const val MAX_REASON = 260
    }
}

package com.banca.agents

import com.banca.games.blackjack.ActionValue
import com.banca.games.blackjack.BlackjackAction
import com.banca.games.blackjack.Decision
import com.banca.games.blackjack.Strategy
import com.banca.games.blackjack.pointsOf
import com.banca.games.cards.Card
import com.banca.sessions.BlackjackView
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/** What one action is worth, as the player is shown it: per chip of the original bet. */
@Serializable
data class AdviceValue(val action: String, val value: Double)

/**
 * What the coach tells the player. [values] is every action they could take
 * with what it is worth, the best first, so the advice can be checked against
 * the figures it came from. [source] is "banca" when the model put it into
 * words and "book" when the arithmetic had to speak for itself.
 */
@Serializable
data class Advice(val action: String, val reason: String, val values: List<AdviceValue>, val source: String)

/**
 * The blackjack tools a coach may use for one decision.
 *
 * Everything here is computed from a [BlackjackView], the same view the
 * player's browser gets, so the coach knows nothing the player could not: not
 * the dealer's hole card, and not what is left in the shoe.
 *
 * The coach's advice is only accepted if it is legal and as good as any other
 * play, so a model may choose how to explain a decision but cannot talk a
 * player into a worse one.
 */
class BlackjackTools(private val view: BlackjackView, private val strategy: Strategy) {

    private val insurance = view.phase == "insurance"

    private val hand = view.hands.getOrNull(view.activeHand ?: 0)
        ?: error("There is no hand to advise on")

    private val dealerShows: Card = Card.of(view.dealer?.cards?.firstOrNull() ?: error("The dealer has no card showing"))

    private val cards = hand.cards.map(Card::of)

    /** Every action open to the player with what it is worth, the best first. */
    val values: List<ActionValue> = if (insurance) {
        strategy.insurance().filter { it.action != BlackjackAction.Insure || view.legal.insurance }
    } else {
        check(view.phase == "player") { "There is no decision to advise on" }
        strategy.evaluate(
            Decision(
                total = hand.total,
                soft = hand.soft,
                pairOf = cards.takeIf { it.size == 2 && pointsOf(it[0]) == pointsOf(it[1]) }?.let { pointsOf(it[0]) },
                dealerUp = pointsOf(dealerShows),
                canDouble = view.legal.double,
                canSplit = view.legal.split,
            ),
        )
    }

    val best: ActionValue get() = values.first()

    var advice: Advice? = null
        private set

    /** Whether [action] is as good as anything else on offer. Plays within a whisker of each other both count. */
    fun isSound(action: BlackjackAction): Boolean =
        values.firstOrNull { it.action == action }?.let { best.value - it.value <= CLOSE_ENOUGH } ?: false

    fun tableState(): JsonObject = buildJsonObject {
        put("deciding", if (insurance) "whether to take insurance" else "how to play the hand")
        putJsonArray("your_cards") { hand.cards.forEach { add(JsonPrimitive(it)) } }
        put("your_total", hand.total)
        put("soft", hand.soft)
        put("dealer_shows", dealerShows.toString())
        put("your_bet", hand.bet)
        put("your_chips", view.stack)
        if (view.hands.size > 1) put("hand", "${(view.activeHand ?: 0) + 1} of ${view.hands.size} after splitting")
        if (insurance) put("insurance_costs", view.insuranceCost)
    }

    fun actionValues(): JsonObject = buildJsonObject {
        putJsonObject("expected_value") { values.forEach { put(nameOf(it.action), it.value.rounded()) } }
        put("best", nameOf(best.action))
        put("note", "What each play wins or loses on average, per chip of your bet. Higher is better; below zero loses over time.")
    }

    fun odds(): JsonObject = buildJsonObject {
        val dealer = strategy.dealerOutcomes(pointsOf(dealerShows))
        if (!insurance) put("you_bust_if_you_hit", strategy.bustChance(hand.total, hand.soft).rounded())
        put("dealer_busts", dealer.bust.rounded())
        putJsonObject("dealer_finishes_on") { dealer.totals.forEach { (total, chance) -> put(total.toString(), chance.rounded()) } }
        if (insurance) put("dealer_has_blackjack", (4.0 / 13).rounded())
    }

    /**
     * Records the advice, or explains why it cannot stand so the model can try
     * again. Advice that is not open to the player, or that the figures say is
     * worse than another play, is refused.
     */
    fun advise(arguments: JsonObject?): String {
        val name = arguments?.get("action")?.jsonPrimitive?.contentOrNull?.lowercase()?.trim()?.replace(' ', '_')
            ?: return "Error: give an action, one of ${values.joinToString(", ") { nameOf(it.action) }}."
        val action = values.firstOrNull { nameOf(it.action) == name }?.action
            ?: return "Error: '$name' is not open to the player. Choose from ${values.joinToString(", ") { nameOf(it.action) }}."

        if (!isSound(action)) {
            return "Error: $name is worth ${values.first { it.action == action }.value.rounded()} and " +
                "${nameOf(best.action)} is worth ${best.value.rounded()}. Advise the play with the highest value."
        }

        val reason = arguments["reason"]?.jsonPrimitive?.contentOrNull?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (reason.isEmpty()) return "Error: give a reason, one sentence the player can follow."

        advice = advice(action, reason.take(MAX_REASON), source = "banca")
        return "Accepted."
    }

    /** The best play with a reason made from the figures, for when no model is there to give one. */
    fun bookAdvice(): Advice = advice(best.action, bookReason(), source = "book")

    private fun advice(action: BlackjackAction, reason: String, source: String) = Advice(
        action = nameOf(action),
        reason = reason,
        values = values.map { AdviceValue(nameOf(it.action), it.value.rounded()) },
        source = source,
    )

    private fun bookReason(): String {
        val dealer = strategy.dealerOutcomes(pointsOf(dealerShows))
        val dealerBusts = percent(dealer.bust)
        val youBust = percent(strategy.bustChance(hand.total, hand.soft))
        val shows = spoken(dealerShows)
        val runnerUp = values.getOrNull(1)
        val margin = runnerUp?.let { "${nameOf(best.action).ing()} is worth ${chips(best.value)} per 100 staked, ${nameOf(it.action).ing()} ${chips(it.value)}" }

        return when (best.action) {
            BlackjackAction.DeclineInsurance ->
                "Insurance pays only if the dealer has a ten underneath, which is 4 times in 13. Taking it loses about 4 chips for every 100 staked."
            BlackjackAction.Insure -> "Insurance is the only choice open."
            BlackjackAction.Stand ->
                if (youBust == 0) "Another card cannot help enough: $margin." else "You would bust $youBust% of the time by hitting, and the dealer busts $dealerBusts% from $shows. $margin."
            BlackjackAction.Hit ->
                if (youBust == 0) "You cannot bust on the next card, and ${hand.total} rarely wins as it stands against $shows. $margin."
                else "A dealer showing $shows busts only $dealerBusts% of the time, so ${hand.total} loses too often as it stands. $margin."
            BlackjackAction.Double ->
                "You are ahead here, so it pays to have more on the table: one card for twice the stake. $margin."
            BlackjackAction.Split ->
                "Two hands starting from ${spoken(cards.first())} each do better than ${hand.total} played as one. $margin."
        }.replaceFirstChar(Char::uppercase)
    }

    fun server(): Server {
        val server = Server(
            Implementation(name = "banca-blackjack", version = "0.1.0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
        )
        val noArguments = ToolSchema(properties = buildJsonObject { })

        fun reply(text: String) = CallToolResult(content = listOf(TextContent(text = text)))

        server.addTool(
            name = GET_TABLE_STATE,
            description = "The player's cards and total, the dealer's face-up card, and the bet. Only what the player can see.",
            inputSchema = noArguments,
        ) { reply(tableState().toString()) }

        server.addTool(
            name = GET_ACTION_VALUES,
            description = "What each play open to the player is worth on average, per chip bet, and which is best.",
            inputSchema = noArguments,
        ) { reply(actionValues().toString()) }

        server.addTool(
            name = GET_ODDS,
            description = "The chance the player busts by hitting, the chance the dealer busts, and where the dealer finishes. From 0 to 1.",
            inputSchema = noArguments,
        ) { reply(odds().toString()) }

        server.addTool(
            name = GIVE_ADVICE,
            description = "Gives the player your advice. Call it exactly once, after you have looked at the values.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("action") {
                        put("type", "string")
                        putJsonArray("enum") { values.forEach { add(JsonPrimitive(nameOf(it.action))) } }
                    }
                    putJsonObject("reason") {
                        put("type", "string")
                        put("description", "One sentence for the player, in plain words, using a figure from the tools.")
                    }
                },
                required = listOf("action", "reason"),
            ),
        ) { request -> reply(advise(request.arguments)) }

        return server
    }

    private fun Double.rounded(): Double = round(this * 1000) / 1000

    private fun percent(chance: Double): Int = (chance * 100).roundToInt()

    /** A value per chip said as chips won or lost on a hundred staked: "+18", "−54". */
    private fun chips(value: Double): String {
        val amount = (value * 100).roundToInt()
        return if (amount < 0) "−${abs(amount)}" else "+$amount"
    }

    private fun String.ing(): String = when (this) {
        "hit" -> "hitting"
        "stand" -> "standing"
        "double" -> "doubling"
        "split" -> "splitting"
        else -> this
    }

    private fun spoken(card: Card): String = when (val points = pointsOf(card)) {
        1 -> "an ace"
        8 -> "an 8"
        10 -> "a ten"
        else -> "a $points"
    }

    companion object {
        const val GET_TABLE_STATE = "get_table_state"
        const val GET_ACTION_VALUES = "get_action_values"
        const val GET_ODDS = "get_odds"
        const val GIVE_ADVICE = "give_advice"

        /** Two plays this close in value are both right. */
        const val CLOSE_ENOUGH = 0.005

        private const val MAX_REASON = 240

        fun nameOf(action: BlackjackAction): String = when (action) {
            BlackjackAction.Hit -> "hit"
            BlackjackAction.Stand -> "stand"
            BlackjackAction.Double -> "double"
            BlackjackAction.Split -> "split"
            BlackjackAction.Insure -> "insure"
            BlackjackAction.DeclineInsurance -> "decline_insurance"
        }
    }
}

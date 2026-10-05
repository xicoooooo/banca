package com.banca.agents

import com.banca.games.poker.Action
import com.banca.games.poker.Card
import com.banca.sessions.TableView
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
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
import kotlin.random.Random

/**
 * The poker tools one agent may use for one decision.
 *
 * Everything here is computed from a [TableView], the same per-seat view a
 * browser gets, so no tool can reveal a card the seat is not entitled to see.
 * The decision is only recorded here; the engine still validates it like any
 * other player's.
 */
class PokerTools(
    private val view: TableView,
    private val random: Random = Random.Default,
    // Enough for an estimate good to about two points either way, and cheap
    // enough for the fraction of a processor that free hosting provides.
    private val equityIterations: Int = 800,
) {
    private val legal = view.legal ?: error("Tools are only built for the seat that is to act")
    private val me = view.players.single { it.seat == view.yourSeat }

    var decision: Action? = null
        private set

    fun gameState(): JsonObject = buildJsonObject {
        put("street", view.street)
        putJsonArray("your_cards") { me.cards.orEmpty().forEach { add(JsonPrimitive(it)) } }
        putJsonArray("board") { view.board.forEach { add(JsonPrimitive(it)) } }
        put("pot", view.pot)
        put("your_stack", me.stack)
        put("your_bet_this_street", me.committed)
        put("you_are_the_button", view.buttonSeat == me.seat)
        put("big_blind", view.bigBlind)
        putJsonArray("opponents") {
            view.players.filter { it.seat != me.seat }.forEach { other ->
                add(
                    buildJsonObject {
                        put("name", other.name)
                        put("stack", other.stack)
                        put("bet_this_street", other.committed)
                        put("status", other.status)
                    },
                )
            }
        }
    }

    fun legalActions(): JsonObject = buildJsonObject {
        putJsonArray("actions") {
            add(JsonPrimitive("fold"))
            if (legal.canCheck) add(JsonPrimitive("check"))
            if (legal.canCall) add(JsonPrimitive("call"))
            if (legal.canBet) add(JsonPrimitive("bet"))
            if (legal.canRaise) add(JsonPrimitive("raise"))
        }
        if (legal.canCall) put("call_cost", legal.callCost)
        if (legal.canBet) putJsonObject("bet_amount") {
            put("min", legal.minBet)
            put("max", legal.maxTo)
        }
        if (legal.canRaise) putJsonObject("raise_to_amount") {
            put("min", legal.minRaiseTo)
            put("max", legal.maxTo)
        }
        put("note", "Amounts are the total to have in front of you this street. The max is all-in.")
    }

    fun handEquity(): JsonObject {
        val opponents = view.players.count { it.seat != me.seat && it.status != "folded" }
        val equity = PokerMath.equity(
            hole = me.cards.orEmpty().map(Card::of),
            board = view.board.map(Card::of),
            opponents = opponents,
            iterations = equityIterations,
            random = random,
        )
        return buildJsonObject {
            put("equity", equity.rounded())
            put("against", "$opponents random hand${if (opponents == 1) "" else "s"}")
        }
    }

    fun potOdds(): JsonObject = buildJsonObject {
        put("pot", view.pot)
        put("call_cost", legal.callCost)
        put("pot_odds", PokerMath.potOdds(view.pot, legal.callCost).rounded())
        if (legal.callCost == 0L) put("note", "Nothing to call, checking is free.")
    }

    /**
     * Records the decision, or explains why it cannot stand so the model can
     * try again. Small models confuse bet with raise and overshoot sizes, so
     * those are corrected rather than refused; anything else illegal is an error.
     */
    fun submit(arguments: JsonObject?): String {
        val name = arguments?.get("action")?.jsonPrimitive?.contentOrNull?.lowercase()?.trim()
            ?: return "Error: give an action, one of fold, check, call, bet, raise."
        val amount = arguments["amount"]?.jsonPrimitive?.longOrNull

        val action: Action = when (name) {
            "fold" -> Action.Fold
            "check" -> if (legal.canCheck) Action.Check else return "Error: you cannot check, there is a bet to call."
            "call" -> when {
                legal.canCall -> Action.Call
                legal.canCheck -> Action.Check
                else -> return "Error: there is nothing to call."
            }
            "bet", "raise", "all_in", "all-in" -> {
                val wanted = if (name.startsWith("all")) legal.maxTo else amount
                    ?: return "Error: a $name needs an amount."
                when {
                    legal.canBet -> Action.Bet(wanted.coerceIn(legal.minBet, legal.maxTo))
                    legal.canRaise -> Action.Raise(wanted.coerceIn(legal.minRaiseTo, legal.maxTo))
                    else -> return "Error: you cannot bet or raise now. Call or fold."
                }
            }
            else -> return "Error: unknown action '$name'. Use fold, check, call, bet or raise."
        }

        decision = action
        return "Accepted: ${describe(action)}."
    }

    fun server(): Server {
        val server = Server(
            Implementation(name = "banca-poker", version = "0.1.0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
        )
        val noArguments = ToolSchema(properties = buildJsonObject { })

        fun reply(text: String) = CallToolResult(content = listOf(TextContent(text = text)))

        server.addTool(
            name = GET_GAME_STATE,
            description = "Your cards, the board, the pot, stacks and bets. Only what you can see at the table.",
            inputSchema = noArguments,
        ) { reply(gameState().toString()) }

        server.addTool(
            name = GET_LEGAL_ACTIONS,
            description = "The actions you may take now and the allowed amounts.",
            inputSchema = noArguments,
        ) { reply(legalActions().toString()) }

        server.addTool(
            name = GET_HAND_EQUITY,
            description = "Estimates how often your hand wins at showdown against random hands, from 0 to 1.",
            inputSchema = noArguments,
        ) { reply(handEquity().toString()) }

        server.addTool(
            name = GET_POT_ODDS,
            description = "The equity a call needs to break even, from 0 to 1.",
            inputSchema = noArguments,
        ) { reply(potOdds().toString()) }

        server.addTool(
            name = SUBMIT_ACTION,
            description = "Commits your decision for this turn. Call it exactly once, after you have decided.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("action") {
                        put("type", "string")
                        putJsonArray("enum") {
                            listOf("fold", "check", "call", "bet", "raise").forEach { add(JsonPrimitive(it)) }
                        }
                    }
                    putJsonObject("amount") {
                        put("type", "integer")
                        put("description", "Only for bet or raise: the total to have in front of you this street.")
                    }
                },
                required = listOf("action"),
            ),
        ) { request -> reply(submit(request.arguments)) }

        return server
    }

    private fun Double.rounded(): Double = round(this * 1000) / 1000

    companion object {
        const val GET_GAME_STATE = "get_game_state"
        const val GET_LEGAL_ACTIONS = "get_legal_actions"
        const val GET_HAND_EQUITY = "get_hand_equity"
        const val GET_POT_ODDS = "get_pot_odds"
        const val SUBMIT_ACTION = "submit_action"

        fun describe(action: Action): String = when (action) {
            is Action.Fold -> "fold"
            is Action.Check -> "check"
            is Action.Call -> "call"
            is Action.Bet -> "bet ${action.amount}"
            is Action.Raise -> "raise to ${action.to}"
        }
    }
}

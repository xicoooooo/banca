package com.banca.agents

import com.banca.games.roulette.Bet
import com.banca.games.roulette.Outlook
import com.banca.games.roulette.Wager
import com.banca.games.roulette.Wheel
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

/** The figures behind a read, as the player is shown them. Chances are shares of the wheel, from 0 to 1. */
@Serializable
data class LayoutFigures(
    val staked: Long,
    val ahead: Double,
    val level: Double,
    val behind: Double,
    val nothing: Double,
    val best: Long,
    val bestPockets: List<Int>,
    /** What the layout comes to on average, per spin. Never above nought. */
    val average: Double,
)

/**
 * What Banca makes of a layout. [figures] are worked out on the server and are
 * the substance; [text] is how it is put. [source] is "banca" when the model
 * chose the words and "book" when the figures had to speak for themselves.
 */
@Serializable
data class LayoutRead(val text: String, val figures: LayoutFigures, val source: String)

/**
 * The roulette tools an analyst may use to read one layout.
 *
 * There is no better or worse bet at roulette: the house keeps the same share
 * of every one. So unlike the blackjack coach, the analyst has no play to
 * recommend, and these tools give it none. What they give it is the truth
 * about the layout in front of the player: how often it wins, what it can
 * win, and what it costs.
 *
 * They are built from the bets and nothing else. The analyst is never shown
 * where the ball has landed before, so it has nothing from which to suggest
 * that a number is due.
 */
class RouletteTools(private val wagers: List<Wager>, private val chips: Long) {

    val outlook = Outlook.of(wagers)

    val figures = LayoutFigures(
        staked = outlook.staked,
        ahead = share(outlook.ahead),
        level = share(outlook.level),
        behind = share(outlook.behind),
        nothing = share(outlook.nothing),
        best = outlook.best,
        bestPockets = outlook.bestPockets,
        average = outlook.average.rounded(2),
    )

    var read: LayoutRead? = null
        private set

    fun layout(): JsonObject = buildJsonObject {
        putJsonArray("bets") {
            wagers.forEach { wager ->
                add(
                    buildJsonObject {
                        put("on", describe(wager.bet))
                        put("chips", wager.amount)
                        put("covers", "${wager.bet.numbers.size} of 37 numbers")
                        put("pays", "${wager.bet.pays} to 1")
                    },
                )
            }
        }
        put("total_staked", outlook.staked)
        put("your_chips", chips)
        if (outlook.cancelling.isNotEmpty()) {
            putJsonArray("bets_that_cancel_out") {
                outlook.cancelling.forEach { (one, other) -> add(JsonPrimitive("${describe(one)} and ${describe(other)}")) }
            }
        }
    }

    fun chances(): JsonObject = buildJsonObject {
        put("come_out_ahead", figures.ahead)
        if (outlook.level > 0) put("break_even", figures.level)
        if (outlook.behind > 0) put("get_some_chips_back_but_lose_overall", figures.behind)
        put("lose_everything_staked", figures.nothing)
        put("note", "Shares of the wheel's 37 pockets, from 0 to 1. Every pocket is as likely as any other on every spin.")
    }

    fun cost(): JsonObject = buildJsonObject {
        put("best_spin", outlook.best)
        putJsonArray("best_spin_lands_on") { outlook.bestPockets.take(6).forEach { add(JsonPrimitive(it)) } }
        put("worst_spin", -outlook.staked)
        put("average_per_spin", figures.average)
        put("average_over_100_spins", (outlook.average * 100).roundToInt())
        put("house_edge", (1.0 / Wheel.POCKETS).rounded(3))
        put("note", "The house edge is the same on every bet. No choice of bets, and no system, changes it.")
    }

    /** Records the read, or says why it cannot stand so the model can try again. */
    fun giveRead(arguments: JsonObject?): String {
        val text = arguments?.get("read")?.jsonPrimitive?.contentOrNull?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (text.isEmpty()) return "Error: give your read, one or two sentences the player can follow."
        if (text.length > MAX_READ) return "Error: that is too long. Two short sentences at most."

        read = LayoutRead(text, figures, source = "banca")
        return "Accepted."
    }

    /** A read made from the figures alone, for when no model is there to give one. */
    fun bookRead(): LayoutRead {
        val ahead = percent(figures.ahead)
        val nothing = percent(figures.nothing)
        val cost = abs(outlook.average).let { if (it >= 10) it.roundToInt().toString() else it.rounded(1).toString() }

        val cancels = outlook.cancelling.firstOrNull()?.let { (one, other) ->
            " ${describe(one).replaceFirstChar(Char::uppercase)} and ${describe(other)} cancel each other out: one always pays for the other, except on zero, which takes both."
        }.orEmpty()

        val text = "You come out ahead on $ahead% of spins and lose everything on $nothing%. " +
            "On average this layout costs $cost chips a spin, the same share the house takes from any bet.$cancels"
        return LayoutRead(text, figures, source = "book")
    }

    fun server(): Server {
        val server = Server(
            Implementation(name = "banca-roulette", version = "0.1.0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
        )
        val noArguments = ToolSchema(properties = buildJsonObject { })

        fun reply(text: String) = CallToolResult(content = listOf(TextContent(text = text)))

        server.addTool(
            name = GET_LAYOUT,
            description = "The bets the player has put down, what each covers and pays, and bets that cancel each other out.",
            inputSchema = noArguments,
        ) { reply(layout().toString()) }

        server.addTool(
            name = GET_CHANCES,
            description = "How likely the layout is to come out ahead, break even, or lose everything, on one spin.",
            inputSchema = noArguments,
        ) { reply(chances().toString()) }

        server.addTool(
            name = GET_COST,
            description = "The best and worst a spin can do, and what the layout costs on average.",
            inputSchema = noArguments,
        ) { reply(cost().toString()) }

        server.addTool(
            name = GIVE_READ,
            description = "Gives the player your read of their layout. Call it exactly once, after you have looked at the figures.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("read") {
                        put("type", "string")
                        put("description", "One or two short sentences for the player, in plain words, using figures from the tools.")
                    }
                },
                required = listOf("read"),
            ),
        ) { request -> reply(giveRead(request.arguments)) }

        return server
    }

    private fun share(pockets: Int): Double = (pockets.toDouble() / Wheel.POCKETS).rounded(3)

    private fun percent(share: Double): Int = (share * 100).roundToInt()

    private fun Double.rounded(places: Int): Double {
        var scale = 1.0
        repeat(places) { scale *= 10 }
        return round(this * scale) / scale
    }

    companion object {
        const val GET_LAYOUT = "get_layout"
        const val GET_CHANCES = "get_chances"
        const val GET_COST = "get_cost"
        const val GIVE_READ = "give_read"

        private const val MAX_READ = 320

        /** A bet as it would be called at the table. */
        fun describe(bet: Bet): String = when (bet) {
            is Bet.Straight -> if (bet.number == 0) "zero" else "the number ${bet.number}"
            is Bet.Split -> "the split ${bet.first}/${bet.second}"
            is Bet.Street -> "the street ${bet.first}–${bet.first + 2}"
            is Bet.Corner -> "the corner at ${bet.first}"
            is Bet.SixLine -> "the six line ${bet.first}–${bet.first + 5}"
            is Bet.Dozen -> "the dozen ${(bet.which - 1) * 12 + 1}–${bet.which * 12}"
            is Bet.Column -> "column ${bet.which}"
            Bet.Red -> "red"
            Bet.Black -> "black"
            Bet.Even -> "even"
            Bet.Odd -> "odd"
            Bet.Low -> "1–18"
            Bet.High -> "19–36"
        }
    }
}

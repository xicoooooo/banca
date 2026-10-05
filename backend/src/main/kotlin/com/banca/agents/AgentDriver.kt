package com.banca.agents

import com.banca.games.poker.Action
import com.banca.sessions.SeatDriver
import com.banca.sessions.TableView
import com.banca.sessions.TraceEvent
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AgentConfig(
    /** How many times the model may be asked before the turn is taken from it. */
    val maxModelCalls: Int = 6,
    val timeout: Duration = 60.seconds,
    val random: Random = Random.Default,
)

/**
 * Plays a seat by letting a language model call poker tools over MCP until it
 * submits an action.
 *
 * The model is never trusted to finish: every turn has a budget of model calls
 * and a time limit, and running out of either, or any failure at all, ends in
 * the safest legal action instead of a stalled table.
 */
class AgentDriver(
    private val model: ModelProvider,
    private val config: AgentConfig = AgentConfig(),
) : SeatDriver {

    private val log = LoggerFactory.getLogger(AgentDriver::class.java)

    override suspend fun decide(view: TableView, trace: suspend (TraceEvent) -> Unit): Action {
        val tools = PokerTools(view, config.random)
        val timing = Timing()

        val problem = try {
            withTimeout(config.timeout) { converse(tools, trace, timing) }
            if (tools.decision == null) "it did not reach a decision" else null
        } catch (timeout: TimeoutCancellationException) {
            "it ran out of time"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.warn("Agent turn failed", failure)
            "the model could not be reached"
        }

        log.info(
            "Agent turn took {} ms: {} ms connecting tools, {} ms in {} model calls, {} ms in {} tool calls",
            timing.sinceStart(), timing.setup, timing.model, timing.modelCalls, timing.tools, timing.toolCalls,
        )

        tools.decision?.let { decided ->
            trace(TraceEvent(TraceEvent.DECISION, "Decided to ${PokerTools.describe(decided)}"))
            return decided
        }

        val safe = if (view.legal?.canCheck == true) Action.Check else Action.Fold
        trace(
            TraceEvent(
                kind = TraceEvent.FALLBACK,
                label = "Took the safe option and chose to ${PokerTools.describe(safe)}",
                detail = "The agent's turn was ended because $problem.",
            ),
        )
        return safe
    }

    /** Where a turn's time went, for the log. Hosting on little CPU makes this worth watching. */
    private class Timing {
        private val start = System.nanoTime()
        var setup = 0L
        var model = 0L
        var modelCalls = 0
        var tools = 0L
        var toolCalls = 0

        fun sinceStart(): Long = (System.nanoTime() - start) / 1_000_000

        inline fun <T> timed(record: (Long) -> Unit, block: () -> T): T {
            val began = System.nanoTime()
            try {
                return block()
            } finally {
                record((System.nanoTime() - began) / 1_000_000)
            }
        }
    }

    private suspend fun converse(tools: PokerTools, trace: suspend (TraceEvent) -> Unit, timing: Timing) {
        // The link's message pumps live on their own job so they can be stopped
        // however the conversation ends.
        val link = CoroutineScope(currentCoroutineContext() + Job())
        val server = tools.server()
        val client = Client(Implementation(name = "banca-agent", version = "0.1.0"))

        try {
            val listed = timing.timed({ timing.setup = it }) {
                val (serverEnd, clientEnd) = LinkedTransport.pair(link)
                server.createSession(serverEnd)
                client.connect(clientEnd)
                client.listTools().tools
            }

            val specs = listed.map { tool ->
                ToolSpec(
                    name = tool.name,
                    description = tool.description.orEmpty(),
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", tool.inputSchema.properties ?: buildJsonObject { })
                    },
                )
            }

            val messages = mutableListOf<ChatMessage>(
                ChatMessage.System(SYSTEM_PROMPT),
                ChatMessage.User("It is your turn. Use the tools, then submit your action."),
            )

            repeat(config.maxModelCalls) {
                val answer = timing.timed({ timing.model += it; timing.modelCalls++ }) { model.chat(messages, specs) }
                // Small models sometimes write a tool call out as text instead
                // of making it. Reading it back costs nothing and saves the turn.
                val written = if (answer.toolCalls.isEmpty()) writtenToolCalls(answer.text, specs) else emptyList()
                val reply = if (written.isEmpty()) answer else ModelReply(text = "", toolCalls = written)
                messages += ChatMessage.Assistant(reply.text, reply.toolCalls)

                if (reply.text.isNotBlank()) {
                    trace(TraceEvent(TraceEvent.THOUGHT, "Thought it over", reply.text))
                }
                if (reply.toolCalls.isEmpty()) {
                    messages += ChatMessage.User("Call submit_action now to commit your decision.")
                }

                for (call in reply.toolCalls) {
                    val result = timing.timed({ timing.tools += it; timing.toolCalls++ }) {
                        client.callTool(
                            CallToolRequest(CallToolRequestParams(name = call.name, arguments = call.arguments)),
                        )
                    }
                    val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                    messages += ChatMessage.ToolResult(call.name, text)

                    if (call.name != PokerTools.SUBMIT_ACTION) {
                        trace(TraceEvent(TraceEvent.TOOL, labelFor(call.name), "${call.name} → $text"))
                    }
                    if (tools.decision != null) return
                }
            }
        } finally {
            link.cancel()
        }
    }

    /**
     * Finds tool calls spelled out in prose, such as
     * `submit_action {"action": "call"}`: a known tool name followed by its
     * arguments as a JSON object, or by nothing for a tool that takes none.
     */
    private fun writtenToolCalls(text: String, specs: List<ToolSpec>): List<ToolCall> =
        specs.mapNotNull { spec ->
            val at = text.indexOf(spec.name)
            if (at < 0) return@mapNotNull null

            val after = text.substring(at + spec.name.length).trimStart(' ', ':', '(', '`', '\n')
            val arguments = when {
                after.startsWith("{") -> firstJsonObject(after)
                spec.name == PokerTools.SUBMIT_ACTION -> positionalSubmission(after)
                else -> null
            }
            // submit_action is meaningless without arguments; the others take none.
            if (arguments == null && spec.name == PokerTools.SUBMIT_ACTION) return@mapNotNull null

            at to ToolCall(spec.name, arguments ?: JsonObject(emptyMap()))
        }.sortedBy { (position, _) -> position }.map { (_, call) -> call }

    /** Reads the function-call spelling, `submit_action("raise", 300)`. */
    private fun positionalSubmission(afterName: String): JsonObject? {
        val call = afterName.lineSequence().first().substringBefore(')')
        val action = Regex("\\b(fold|check|call|bet|raise)\\b", RegexOption.IGNORE_CASE).find(call) ?: return null
        val amount = Regex("\\d+").find(call, action.range.last + 1)?.value?.toLongOrNull()

        return buildJsonObject {
            put("action", action.value.lowercase())
            if (amount != null) put("amount", amount)
        }
    }

    private fun firstJsonObject(text: String): JsonObject? {
        var depth = 0
        for ((index, char) in text.withIndex()) {
            when (char) {
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    return runCatching { Json.parseToJsonElement(text.substring(0, index + 1)) as? JsonObject }.getOrNull()
                }
            }
        }
        return null
    }

    private fun labelFor(tool: String): String = when (tool) {
        PokerTools.GET_GAME_STATE -> "Looked at the table"
        PokerTools.GET_LEGAL_ACTIONS -> "Checked what it may do"
        PokerTools.GET_HAND_EQUITY -> "Estimated its hand equity"
        PokerTools.GET_POT_ODDS -> "Worked out the pot odds"
        else -> "Used $tool"
    }

    private companion object {
        val SYSTEM_PROMPT = """
            You are Banca, playing heads-up no-limit Texas Hold'em for play chips. You play a tight-aggressive game: you bet and raise your good hands, and you give up your bad ones.

            Every turn, in this order:
            1. Call get_game_state, get_hand_equity, get_pot_odds and get_legal_actions together, in one step.
            2. Call submit_action exactly once. For a bet or raise, use one of the amounts get_legal_actions offers.

            Your equity is measured against a random hand. An opponent who bets or raises usually holds better than random, so when you face a bet, treat your equity as about 0.10 lower than the tool says.

            When you can check (nothing to call):
            - Equity above 0.65: bet two thirds of the pot, or the whole pot with equity above 0.80.
            - Equity 0.50 to 0.65: bet half the pot.
            - Equity below 0.50: check. About one time in five, bet half the pot as a bluff instead.
            - Before the flop, with equity above 0.55, raise rather than just check.

            When you face a bet:
            - Adjusted equity above 0.70: raise, to the half-pot or pot amount.
            - Adjusted equity above the pot odds: call.
            - Otherwise fold. Do not call just because the bet is small.

            Never fold when you can check. Checking and calling every hand is losing poker: when the numbers say bet or raise, do it.

            Do not explain at length. If you write anything, keep it to one short sentence. Always finish by calling submit_action as a real tool call, never by writing it out as text.
        """.trimIndent()
    }
}

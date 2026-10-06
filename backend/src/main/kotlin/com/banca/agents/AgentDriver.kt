package com.banca.agents

import com.banca.games.poker.Action
import com.banca.sessions.SeatDriver
import com.banca.sessions.TableView
import com.banca.sessions.TraceEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
    private val conversation = ToolConversation(model, config.maxModelCalls)

    override suspend fun decide(view: TableView, trace: suspend (TraceEvent) -> Unit): Action {
        val tools = PokerTools(view, config.random)
        val timing = Timing()
        val task = ToolTask(
            server = tools.server(),
            systemPrompt = SYSTEM_PROMPT,
            opening = "It is your turn. Use the tools, then submit your action.",
            finishingTool = PokerTools.SUBMIT_ACTION,
            reminder = "Call submit_action now to commit your decision.",
            isFinished = { tools.decision != null },
            labelFor = ::labelFor,
            readWrittenFinish = ::positionalSubmission,
        )

        val problem = try {
            withTimeout(config.timeout) { conversation.run(task, trace, timing) }
            if (tools.decision == null) "it did not reach a decision" else null
        } catch (timeout: TimeoutCancellationException) {
            "it ran out of time"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.warn("Agent turn failed", failure)
            "the model could not be reached"
        }

        log.info("Agent turn took {}", timing)

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

    private fun labelFor(tool: String): String = when (tool) {
        PokerTools.GET_GAME_STATE -> "Looked at the table"
        PokerTools.GET_LEGAL_ACTIONS -> "Checked what it may do"
        PokerTools.GET_HAND_EQUITY -> "Estimated its hand equity"
        PokerTools.GET_POT_ODDS -> "Worked out the pot odds"
        else -> "Used $tool"
    }

    private companion object {
        val SYSTEM_PROMPT = """
            You are Banca, playing no-limit Texas Hold'em for play chips, against one opponent or several. You play a tight-aggressive game: you bet and raise your good hands, and you give up your bad ones.

            Every turn, in this order:
            1. Call get_game_state, get_hand_equity, get_pot_odds and get_legal_actions together, in one step.
            2. Call submit_action exactly once. For a bet or raise, use one of the amounts get_legal_actions offers.

            Your equity is measured against random hands, one for each opponent still in. An opponent who bets or raises usually holds better than random, so when you face a bet, treat your equity as about 0.10 lower than the tool says.

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

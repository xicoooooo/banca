package com.banca.agents

import com.banca.games.blackjack.Rules
import com.banca.games.blackjack.Strategy
import com.banca.sessions.BlackjackView
import com.banca.sessions.TraceEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Whatever advises a player on the decision in front of them. */
fun interface BlackjackAdvisor {
    suspend fun advise(view: BlackjackView, trace: suspend (TraceEvent) -> Unit): Advice
}

/**
 * Advice from the arithmetic alone: the best play and a reason made from the
 * figures. Used where there is no model, and as what a coach falls back on.
 */
class BookAdvisor(rules: Rules = Rules()) : BlackjackAdvisor {
    private val strategy = Strategy(rules)

    override suspend fun advise(view: BlackjackView, trace: suspend (TraceEvent) -> Unit): Advice {
        val tools = BlackjackTools(view, strategy)
        trace(TraceEvent(TraceEvent.TOOL, BlackjackCoach.labelFor(BlackjackTools.GET_ACTION_VALUES), tools.actionValues().toString()))
        trace(TraceEvent(TraceEvent.TOOL, BlackjackCoach.labelFor(BlackjackTools.GET_ODDS), tools.odds().toString()))
        return tools.bookAdvice().also { trace(TraceEvent(TraceEvent.DECISION, "Advises you to ${BlackjackCoach.spoken(it.action)}")) }
    }
}

class CoachConfig(
    /** How many times the model may be asked before the book answers instead. */
    val maxModelCalls: Int = 4,
    /** A player is waiting to act, so the coach gets less time than an opponent does. */
    val timeout: Duration = 25.seconds,
)

/**
 * Coaches a blackjack player by letting a language model look at the hand
 * through tools over MCP and say what it would do, and why.
 *
 * The model chooses the words, not the play: its advice is only accepted if
 * the figures agree it is the best one. And it is never trusted to finish. If
 * it runs out of turns or time, or fails in any way, the player still gets the
 * right play, with a reason made from the figures.
 */
class BlackjackCoach(
    model: ModelProvider,
    rules: Rules = Rules(),
    private val config: CoachConfig = CoachConfig(),
) : BlackjackAdvisor {

    private val log = LoggerFactory.getLogger(BlackjackCoach::class.java)
    private val strategy = Strategy(rules)
    private val conversation = ToolConversation(model, config.maxModelCalls)

    override suspend fun advise(view: BlackjackView, trace: suspend (TraceEvent) -> Unit): Advice {
        val tools = BlackjackTools(view, strategy)
        val timing = Timing()
        val task = ToolTask(
            server = tools.server(),
            systemPrompt = SYSTEM_PROMPT,
            opening = "The player has asked what you would do. Use the tools, then give your advice.",
            finishingTool = BlackjackTools.GIVE_ADVICE,
            reminder = "Call give_advice now with the best action and one sentence of reason.",
            isFinished = { tools.advice != null },
            labelFor = ::labelFor,
        )

        val problem = try {
            withTimeout(config.timeout) { conversation.run(task, trace, timing) }
            if (tools.advice == null) "it did not settle on advice" else null
        } catch (timeout: TimeoutCancellationException) {
            "it ran out of time"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.warn("Coach failed", failure)
            "the model could not be reached"
        }

        log.info("Coaching took {}", timing)

        tools.advice?.let { advice ->
            trace(TraceEvent(TraceEvent.DECISION, "Advises you to ${spoken(advice.action)}"))
            return advice
        }

        val book = tools.bookAdvice()
        trace(
            TraceEvent(
                kind = TraceEvent.FALLBACK,
                label = "Advises you to ${spoken(book.action)}, from the figures",
                detail = "Banca could not put this one into its own words because $problem, so the reason comes straight from the arithmetic.",
            ),
        )
        return book
    }

    companion object {
        fun labelFor(tool: String): String = when (tool) {
            BlackjackTools.GET_TABLE_STATE -> "Looked at your hand"
            BlackjackTools.GET_ACTION_VALUES -> "Worked out what each play is worth"
            BlackjackTools.GET_ODDS -> "Checked the odds of busting"
            else -> "Used $tool"
        }

        fun spoken(action: String): String = when (action) {
            "decline_insurance" -> "decline insurance"
            "insure" -> "take insurance"
            else -> action
        }

        private val SYSTEM_PROMPT = """
            You are Banca, a blackjack coach sitting beside a player. They have asked what you would do with the hand in front of them.

            Every time, in this order:
            1. Call get_table_state, get_action_values and get_odds together, in one step.
            2. Call give_advice exactly once, with the action get_action_values says is best and one sentence of reason.

            The reason is for the player, so:
            - Speak to them as "you", in plain words. No jargon such as "expected value" or "EV".
            - Use one or two figures from the tools, as percentages: "the dealer busts 42% of the time from a 6".
            - Say why this play beats the obvious alternative. Keep it under 30 words.
            - Never mention tools, and never guess at the dealer's hidden card.

            Always finish by calling give_advice as a real tool call, never by writing it out as text.
        """.trimIndent()
    }
}

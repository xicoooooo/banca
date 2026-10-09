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
 * It does not always play the same way. It has moods, each a different reading
 * of the same figures, and keeps to one for a few hands before changing, so
 * that it cannot simply be worked out. Which mood it was in is told with the
 * rest of its reasoning, once the hand is over.
 *
 * The model is never trusted to finish: every turn has a budget of model calls
 * and a time limit, and running out of either, or any failure at all, ends in
 * the hand being played by the mood's own lines instead of a stalled table.
 */
class AgentDriver(
    private val model: ModelProvider,
    private val config: AgentConfig = AgentConfig(),
) : SeatDriver {

    private val log = LoggerFactory.getLogger(AgentDriver::class.java)
    private val conversation = ToolConversation(model, config.maxModelCalls)

    /** How Banca is playing at this table for the time being. Each table has its own. */
    private val moods = MoodSwings(config.random)

    override suspend fun decide(view: TableView, trace: suspend (TraceEvent) -> Unit): Action {
        val tools = PokerTools(view, config.random)
        val timing = Timing()
        val (mood, firstOfHand) = moods.forHand(view.handNumber)
        // Said once a hand, and kept back with the rest of its thinking until the hand is over.
        if (firstOfHand) trace(TraceEvent(TraceEvent.THOUGHT, MOOD_STEP, mood.told))
        val bluffing = config.random.nextDouble() < mood.bluffs
        var looked = false
        val trace: suspend (TraceEvent) -> Unit = { event ->
            if (event.kind == TraceEvent.TOOL) looked = true
            trace(event)
        }
        val task = ToolTask(
            server = tools.server(),
            systemPrompt = systemPrompt(mood),
            // Until it has looked at the table it needs to be told only to look, which costs far fewer words.
            briefing = BRIEFING,
            lookTogether = setOf(PokerTools.GET_GAME_STATE, PokerTools.GET_HAND_EQUITY, PokerTools.GET_POT_ODDS, PokerTools.GET_LEGAL_ACTIONS),
            opening = if (bluffing) BLUFF else NO_BLUFF,
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
        } catch (busy: RateLimited) {
            // The everyday case on a free tier, and not worth a stack trace.
            log.info("Every model is busy for {}", busy.retryAfter)
            "its free allowance for the minute was used up"
        } catch (failure: Exception) {
            log.warn("Agent turn failed", failure)
            "the model could not be reached"
        }

        log.info("Agent turn took {}", timing)

        tools.decision?.let { decided ->
            trace(TraceEvent(TraceEvent.DECISION, "Decided to ${PokerTools.describe(decided)}"))
            return decided
        }

        // With no model to ask, the hand is played by the mood's own lines: the same figures and the
        // same decision, with nobody to put it into words. A table never goes limp because a free
        // model has run out for the minute.
        val legal = view.legal
        val played = legal?.let { runCatching { ByTheLines.play(tools, it, mood, bluffing, river = view.board.size == 5) }.getOrNull() }
            ?: if (legal?.canCheck == true) Action.Check else Action.Fold
        if (!looked && legal != null) {
            trace(TraceEvent(TraceEvent.TOOL, labelFor(PokerTools.GET_HAND_EQUITY), "${PokerTools.GET_HAND_EQUITY} → ${tools.handEquity()}"))
            trace(TraceEvent(TraceEvent.TOOL, labelFor(PokerTools.GET_POT_ODDS), "${PokerTools.GET_POT_ODDS} → ${tools.potOdds()}"))
        }
        trace(
            TraceEvent(
                kind = TraceEvent.FALLBACK,
                label = "Played by its rule of thumb and chose to ${PokerTools.describe(played)}",
                detail = "Banca's model could not be asked this turn because $problem, so it played by the lines of the mood it was in.",
            ),
        )
        return played
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

    companion object {
        /** What a hand's first step is called. It says nothing of the mood itself while the hand is live. */
        const val MOOD_STEP = "Settled on how to play"

        /** What Banca is told every hand, with how it is to play this one set into the middle. */
        fun systemPrompt(mood: Mood): String = """
            You are Banca, playing no-limit Texas Hold'em for play chips, against one opponent or several.

            Every turn, in this order:
            1. Call get_game_state, get_hand_equity, get_pot_odds and get_legal_actions together, in one step.
            2. Call submit_action exactly once. For a bet or raise, use one of the amounts get_legal_actions offers.

            Your equity is measured against random hands, one for each opponent still in.

        """.trimIndent() + "\n" + mood.rules.trimIndent() + "\n\n" + """
            Never fold when you can check. Whether to bluff this turn is decided for you, and you are told at the start of the turn: follow it.

            Do not explain at length. If you write anything, keep it to one short sentence. Always finish by calling submit_action as a real tool call, never by writing it out as text.
        """.trimIndent()

        // A model asked to do something one time in five does it every time or never. So the
        // dice are thrown here, and the model is told how they fell.
        /** All the model needs to be told before it has seen anything: to go and look. */
        const val BRIEFING = "You are Banca, playing no-limit Texas Hold'em. It is your turn. Call get_game_state, get_hand_equity, get_pot_odds and get_legal_actions together, in one step. Do not submit an action yet."

        const val BLUFF = "It is your turn. This turn you are bluffing: if your equity is below 0.50, bet half the pot if you can bet, or raise to the half-pot amount if the bet you face is no more than half the pot. With a stronger hand, play as usual. Use the tools, then submit your action."
        const val NO_BLUFF = "It is your turn. No bluffing this turn: with a weak hand, check if you can and fold if you cannot. Use the tools, then submit your action."
    }
}

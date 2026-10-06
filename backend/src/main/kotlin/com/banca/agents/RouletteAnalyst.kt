package com.banca.agents

import com.banca.games.roulette.Wager
import com.banca.sessions.TraceEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

/** Whatever tells a player what their layout stands to do. */
fun interface RouletteAdvisor {
    suspend fun read(wagers: List<Wager>, chips: Long, trace: suspend (TraceEvent) -> Unit): LayoutRead
}

/** A read from the figures alone. Used where there is no model, and as what an analyst falls back on. */
class BookAnalyst : RouletteAdvisor {
    override suspend fun read(wagers: List<Wager>, chips: Long, trace: suspend (TraceEvent) -> Unit): LayoutRead {
        val tools = RouletteTools(wagers, chips)
        trace(TraceEvent(TraceEvent.TOOL, RouletteAnalyst.labelFor(RouletteTools.GET_CHANCES), tools.chances().toString()))
        trace(TraceEvent(TraceEvent.TOOL, RouletteAnalyst.labelFor(RouletteTools.GET_COST), tools.cost().toString()))
        return tools.bookRead().also { trace(TraceEvent(TraceEvent.DECISION, "Gave its read")) }
    }
}

/**
 * Reads a roulette layout for the player by letting a language model look at
 * it through tools over MCP.
 *
 * Roulette cannot be beaten, and Banca does not pretend otherwise. It has no
 * play to recommend, so what the model is asked for is an honest account: how
 * often the layout wins, what it can win, and what it costs. The figures come
 * from the tools and are shown to the player beside whatever the model says,
 * and the model is never shown past spins, so it has nothing to build a
 * superstition on. If it fails or stalls, the figures speak for themselves.
 */
class RouletteAnalyst(model: ModelProvider, private val config: CoachConfig = CoachConfig()) : RouletteAdvisor {

    private val log = LoggerFactory.getLogger(RouletteAnalyst::class.java)
    private val conversation = ToolConversation(model, config.maxModelCalls)

    override suspend fun read(wagers: List<Wager>, chips: Long, trace: suspend (TraceEvent) -> Unit): LayoutRead {
        val tools = RouletteTools(wagers, chips)
        val timing = Timing()
        val task = ToolTask(
            server = tools.server(),
            systemPrompt = SYSTEM_PROMPT,
            opening = "The player has asked what you make of the bets they have put down. Use the tools, then give your read.",
            finishingTool = RouletteTools.GIVE_READ,
            reminder = "Call give_read now with one or two short sentences.",
            isFinished = { tools.read != null },
            labelFor = ::labelFor,
        )

        val problem = try {
            withTimeout(config.timeout) { conversation.run(task, trace, timing) }
            if (tools.read == null) "it did not settle on a read" else null
        } catch (timeout: TimeoutCancellationException) {
            "it ran out of time"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.warn("Analyst failed", failure)
            "the model could not be reached"
        }

        log.info("Reading the layout took {}", timing)

        tools.read?.let { read ->
            trace(TraceEvent(TraceEvent.DECISION, "Gave its read"))
            return read
        }

        trace(
            TraceEvent(
                kind = TraceEvent.FALLBACK,
                label = "Gave its read, from the figures",
                detail = "Banca could not put this one into its own words because $problem, so the read comes straight from the arithmetic.",
            ),
        )
        return tools.bookRead()
    }

    companion object {
        fun labelFor(tool: String): String = when (tool) {
            RouletteTools.GET_LAYOUT -> "Looked at your bets"
            RouletteTools.GET_CHANCES -> "Worked out your chances"
            RouletteTools.GET_COST -> "Worked out what it costs"
            else -> "Used $tool"
        }

        private val SYSTEM_PROMPT = """
            You are Banca, sitting beside a player at a roulette table. Before spinning, they have asked what you make of the bets they have put down.

            Every time, in this order:
            1. Call get_layout, get_chances and get_cost together, in one step.
            2. Call give_read exactly once, with one or two short sentences. Under 40 words.

            Roulette cannot be beaten, and you are honest about it. Your read says what this layout is likely to do and what it costs:
            - Give the chance of coming out ahead and the chance of losing everything, as percentages.
            - Say what it costs on average per spin, in chips.
            - If get_layout lists bets that cancel out, point that out plainly.

            Speak to the player as "you", in plain words, without jargon such as "expected value".
            Never say a number or colour is due, hot, cold or lucky. Never suggest a betting system or a different bet as a way to win. Never encourage betting more.
            Never mention tools.

            Always finish by calling give_read as a real tool call, never by writing it out as text.
        """.trimIndent()
    }
}

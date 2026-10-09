package com.banca.agents

import com.banca.sessions.TableView
import com.banca.sessions.TraceEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.random.Random

/** Whatever advises a poker player on the decision in front of them. */
fun interface PokerAdvisor {
    suspend fun advise(view: TableView, trace: suspend (TraceEvent) -> Unit): PokerAdvice
}

/**
 * Advice from the figures alone: the play the rule of thumb gives, with a
 * reason made from the numbers. Used where there is no model, and as what a
 * coach falls back on.
 */
class BookPokerAdvisor(private val random: Random = Random.Default) : PokerAdvisor {
    override suspend fun advise(view: TableView, trace: suspend (TraceEvent) -> Unit): PokerAdvice {
        val tools = PokerCoachTools(view, random)
        trace(TraceEvent(TraceEvent.TOOL, PokerCoach.labelFor(PokerTools.GET_HAND_EQUITY), tools.table.handEquity().toString()))
        trace(TraceEvent(TraceEvent.TOOL, PokerCoach.labelFor(PokerTools.GET_POT_ODDS), tools.table.potOdds().toString()))
        return tools.bookAdvice().also { trace(TraceEvent(TraceEvent.DECISION, "Advises you to ${PokerCoach.spoken(it)}")) }
    }
}

/**
 * Coaches a poker player by letting a language model look at their hand
 * through tools over MCP and say what it would do, and why.
 *
 * This is a second agent, apart from the Banca that plays a seat. It is built
 * afresh for each question from the asking player's own view of the table and
 * from nothing else, so it knows no card they do not, and the two share no
 * memory: what the opponent was thinking never reaches the coach.
 *
 * Poker has no single right play, so the coach is held to a looser standard
 * than at blackjack: its advice must be open to the player and must not go
 * against the figures. Within that it may choose. If it fails or stalls, the
 * player gets the rule of thumb instead.
 */
class PokerCoach(
    model: ModelProvider,
    private val config: CoachConfig = CoachConfig(),
    private val random: Random = Random.Default,
) : PokerAdvisor {

    private val log = LoggerFactory.getLogger(PokerCoach::class.java)
    private val conversation = ToolConversation(model, config.maxModelCalls)

    override suspend fun advise(view: TableView, trace: suspend (TraceEvent) -> Unit): PokerAdvice {
        val tools = PokerCoachTools(view, random)
        val timing = Timing()
        val task = ToolTask(
            server = tools.server(),
            systemPrompt = SYSTEM_PROMPT,
            opening = "The player has asked what you would do. Use the tools, then give your advice.",
            briefing = "You are Banca, a poker coach. The player has asked what you would do. Call get_game_state, get_hand_equity, get_pot_odds and get_legal_actions together, in one step. Do not give advice yet.",
            lookTogether = setOf(PokerTools.GET_GAME_STATE, PokerTools.GET_HAND_EQUITY, PokerTools.GET_POT_ODDS, PokerTools.GET_LEGAL_ACTIONS),
            finishingTool = PokerCoachTools.GIVE_ADVICE,
            reminder = "Call give_advice now with an action and one sentence of reason.",
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
            log.warn("Poker coach failed", failure)
            "the model could not be reached"
        }

        log.info("Poker coaching took {}", timing)

        tools.advice?.let { advice ->
            trace(TraceEvent(TraceEvent.DECISION, "Advises you to ${spoken(advice)}"))
            return advice
        }

        val book = tools.bookAdvice()
        trace(
            TraceEvent(
                kind = TraceEvent.FALLBACK,
                label = "Advises you to ${spoken(book)}, from the figures",
                detail = "Banca could not put this one into its own words because $problem, so the advice is the rule of thumb for these numbers.",
            ),
        )
        return book
    }

    companion object {
        fun labelFor(tool: String): String = when (tool) {
            PokerTools.GET_GAME_STATE -> "Looked at your hand"
            PokerTools.GET_LEGAL_ACTIONS -> "Checked what you may do"
            PokerTools.GET_HAND_EQUITY -> "Estimated how often you win"
            PokerTools.GET_POT_ODDS -> "Worked out the price of calling"
            else -> "Used $tool"
        }

        fun spoken(advice: PokerAdvice): String = when (advice.action) {
            "bet" -> "bet ${advice.amount}"
            "raise" -> "raise to ${advice.amount}"
            else -> advice.action
        }

        private val SYSTEM_PROMPT = """
            You are Banca, a poker coach sitting beside a player at a no-limit Texas Hold'em table played for play chips. They have asked what you would do with the hand in front of them. You see only what they see.

            Every time, in this order:
            1. Call get_game_state, get_hand_equity, get_pot_odds and get_legal_actions together, in one step.
            2. Call give_advice exactly once, with an action and one sentence of reason. For a bet or raise, use one of the amounts get_legal_actions offers.

            Equity is how often the hand wins against random hands, one for each opponent still in. An opponent who bets or raises usually holds better than random, so when the player faces a bet, treat their equity as about 0.10 lower than the tool says.

            When the player can check (nothing to call):
            - Equity above 0.65: bet two thirds of the pot.
            - Equity 0.50 to 0.65: bet half the pot.
            - Below 0.50: check.

            When the player faces a bet:
            - Adjusted equity above 0.70: raise.
            - Adjusted equity above the pot odds: call.
            - Otherwise fold.

            Never advise folding when checking is free. Do not advise bluffs.

            The reason is for the player, so:
            - Speak to them as "you", in plain words. No jargon such as "equity", "EV" or "pot odds": say "you win about 62% of the time" and "you need to win 25% of the time for the call to pay".
            - Use one or two figures from the tools, as percentages or chips. Keep it under 35 words.
            - If you quote the lowered figure for a hand facing a bet, say so: "about 58% against someone who is betting".
            - Never claim to know what an opponent holds, and never mention tools.

            Always finish by calling give_advice as a real tool call, never by writing it out as text.
        """.trimIndent()
    }
}

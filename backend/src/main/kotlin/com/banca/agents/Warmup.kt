package com.banca.agents

import com.banca.sessions.LegalView
import com.banca.sessions.PlayerView
import com.banca.sessions.TableView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Plays practice turns at startup so the first visitor does not pay for a cold
 * server.
 *
 * A freshly started JVM runs everything slowly until it has loaded and compiled
 * the code a turn uses, and on a fraction of a processor that made the first
 * few turns take ten seconds or more. Rehearsing against a scripted model warms
 * the tools, the protocol and the simulation for free; one real turn then opens
 * the connection to the model.
 */
object Warmup {

    private val log = LoggerFactory.getLogger(Warmup::class.java)

    suspend fun run(model: ModelProvider, rehearsals: Int = 40) {
        val started = System.nanoTime()

        val rehearsal = AgentDriver(ScriptedModel)
        repeat(rehearsals) { rehearsal.decide(PRACTICE_SPOT) { } }

        // Failure here is harmless: the fallback covers a real turn the same way.
        AgentDriver(model).decide(PRACTICE_SPOT) { }

        log.info("Warm-up finished in {} ms", (System.nanoTime() - started) / 1_000_000)
    }

    /** Looks things up, then calls: the shape of an ordinary turn. */
    private object ScriptedModel : ModelProvider {
        private val nothing = JsonObject(emptyMap())

        override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
            val alreadyLooked = messages.any { it is ChatMessage.ToolResult }
            return if (alreadyLooked) {
                ModelReply("", listOf(ToolCall(PokerTools.SUBMIT_ACTION, buildJsonObject { put("action", "call") })))
            } else {
                ModelReply(
                    "",
                    listOf(
                        ToolCall(PokerTools.GET_GAME_STATE, nothing),
                        ToolCall(PokerTools.GET_LEGAL_ACTIONS, nothing),
                        ToolCall(PokerTools.GET_HAND_EQUITY, nothing),
                        ToolCall(PokerTools.GET_POT_ODDS, nothing),
                    ),
                )
            }
        }
    }

    /** A flop decision facing a bet, belonging to no table. */
    private val PRACTICE_SPOT = TableView(
        handNumber = 0,
        street = "flop",
        board = listOf("Ks", "7d", "2c"),
        pot = 300,
        buttonSeat = 0,
        smallBlind = 10,
        bigBlind = 20,
        yourSeat = 1,
        actorSeat = 1,
        players = listOf(
            PlayerView(seat = 0, name = "You", stack = 1800, committed = 100, status = "active", cards = null),
            PlayerView(seat = 1, name = "Banca", stack = 1900, committed = 0, status = "active", cards = listOf("Ah", "Qd")),
        ),
        legal = LegalView(
            canFold = true,
            canCheck = false,
            canCall = true,
            callCost = 100,
            canBet = false,
            minBet = 20,
            canRaise = true,
            minRaiseTo = 200,
            maxTo = 1900,
        ),
        result = null,
    )
}

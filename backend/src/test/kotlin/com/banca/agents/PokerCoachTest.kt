package com.banca.agents

import com.banca.sessions.TableView
import com.banca.sessions.TraceEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class PokerCoachTest {

    // Aces on a dry board are a big favourite; seven-deuce that missed is not.
    private val acesFacingABet = facingABet()
    private val nothingFacingABet = facingABet(cards = listOf("7h", "2d"), board = listOf("Ks", "Qd", "9c"))
    private val acesCheckedTo = checkedTo()
    private val nothingCheckedTo = checkedTo().let { view ->
        view.copy(board = listOf("Ks", "Qd", "9c"), players = view.players.map { if (it.seat == 1) it.copy(cards = listOf("7h", "2d")) else it })
    }

    private fun tools(view: TableView) = PokerCoachTools(view, Random(5))

    private class ScriptedModel(private vararg val replies: ModelReply) : ModelProvider {
        val conversations = mutableListOf<List<ChatMessage>>()
        val offeredTools = mutableListOf<List<String>>()

        override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
            conversations += messages.toList()
            offeredTools += tools.map { it.name }
            return replies.getOrElse(conversations.size - 1) { replies.last() }
        }
    }

    private val looks = ModelReply(
        "",
        listOf("get_game_state", "get_hand_equity", "get_pot_odds", "get_legal_actions").map { ToolCall(it, JsonObject(emptyMap())) },
    )

    private fun advises(action: String, amount: Long? = null, reason: String = "You win often enough here.") = ModelReply(
        "",
        listOf(
            ToolCall(
                "give_advice",
                buildJsonObject {
                    put("action", action)
                    if (amount != null) put("amount", amount)
                    put("reason", reason)
                },
            ),
        ),
    )

    private fun coach(model: ModelProvider, view: TableView, config: CoachConfig = CoachConfig()): Pair<PokerAdvice, List<TraceEvent>> =
        runBlocking {
            val trace = mutableListOf<TraceEvent>()
            PokerCoach(model, config, Random(5)).advise(view) { trace += it } to trace
        }

    // ------------------------------------------------------------- the tools

    @Test
    fun `the coach is told only what the asking player can see`() {
        val state = tools(acesFacingABet).table.gameState().toString()

        assertTrue("Ah" in state && "Ad" in state, "their own cards")
        assertFalse("\"cards\"" in state, "and nobody else's")
        assertEquals(listOf("stack", "bet_this_street", "status", "name").sorted(), opponentKeys(state).sorted())
    }

    private fun opponentKeys(state: String): List<String> =
        Regex("\"opponents\":\\[\\{(.*?)\\}").find(state)!!.groupValues[1].split(',').map { it.substringBefore(':').trim('"') }

    @Test
    fun `the figures the player is shown are the ones the coach was given`() {
        val tools = tools(acesFacingABet)

        assertTrue("\"equity\":${tools.figures.equity}" in tools.table.handEquity().toString())
        assertEquals(tools.figures.equity, tools.figures.equity, "the estimate is made once, not afresh each time it is quoted")
        assertEquals(0.25, tools.figures.potOdds)
        assertEquals(tools.figures.equity - 0.10, tools.figures.againstABet!!, 0.0011, "marked down for facing a bet")
        assertNull(tools(acesCheckedTo).figures.againstABet, "and not when nobody has bet")
        assertEquals(1, tools.figures.opponents)
        assertEquals(300, tools.figures.pot)
        assertEquals(100, tools.figures.callCost)
    }

    @Test
    fun `folding is never advised when checking is free`() {
        assertNotNull(tools(nothingCheckedTo).objection("fold"))
        assertNull(tools(nothingCheckedTo).objection("check"))
    }

    @Test
    fun `a hand far too good to fold may not be folded, nor one far too weak be called`() {
        assertNotNull(tools(acesFacingABet).objection("fold"))
        assertNull(tools(acesFacingABet).objection("call"))
        assertNull(tools(acesFacingABet).objection("raise"))

        val weak = tools(nothingFacingABet)
        assertNull(weak.objection("fold"))
        assertNotNull(weak.objection("raise"), "a coach does not advise bluffs")
    }

    @Test
    fun `plays that are not open are refused, saying what is`() {
        assertTrue("fold, call or raise" in tools(acesFacingABet).objection("check")!!)
        assertNotNull(tools(acesFacingABet).objection("bet"))
        assertNotNull(tools(acesCheckedTo).objection("raise"))
        assertNotNull(tools(acesCheckedTo).objection("call"))
        assertNotNull(tools(nothingCheckedTo).objection("bet"), "betting with nothing is a bluff")
        assertNotNull(tools(acesCheckedTo).objection("shove"))
    }

    @Test
    fun `a size is kept within what the table allows, and supplied when left out`() {
        val oversized = tools(acesFacingABet)
        assertEquals("Accepted.", oversized.advise(buildJsonObject { put("action", "raise"); put("amount", 99_999); put("reason", "Strong.") }))
        assertEquals(1900, oversized.advice!!.amount)

        val unsized = tools(acesCheckedTo)
        assertEquals("Accepted.", unsized.advise(buildJsonObject { put("action", "bet"); put("reason", "Strong.") }))
        assertEquals(133, unsized.advice!!.amount, "two thirds of the pot")

        val call = tools(acesFacingABet)
        call.advise(buildJsonObject { put("action", "call"); put("amount", 100); put("reason", "Fine.") })
        assertNull(call.advice!!.amount, "a call has no size to choose")
    }

    @Test
    fun `advice needs a reason`() {
        val tools = tools(acesFacingABet)
        assertTrue(tools.advise(buildJsonObject { put("action", "call") }).startsWith("Error"))
        assertTrue(tools.advise(null).startsWith("Error"))
        assertNull(tools.advice)
    }

    // --------------------------------------------------------------- the book

    @Test
    fun `the rule of thumb bets a strong hand, checks a weak one, and folds what cannot pay`() {
        assertEquals("raise", tools(acesFacingABet).bookAdvice().action)
        assertEquals("bet", tools(acesCheckedTo).bookAdvice().action)
        assertEquals("check", tools(nothingCheckedTo).bookAdvice().action)
        assertEquals("fold", tools(nothingFacingABet).bookAdvice().action)
    }

    @Test
    fun `the rule of thumb never gives advice the figures would refuse`() {
        val hands = listOf(listOf("Ah", "Ad"), listOf("7h", "2d"), listOf("Jh", "Td"), listOf("Ks", "3s"), listOf("9c", "9d"), listOf("Qh", "Jh"))
        val boards = listOf(listOf("Kc", "7d", "2c"), listOf("Th", "9h", "4s"), emptyList())

        for (cards in hands) for (board in boards.filter { board -> board.none { it in cards } }) {
            for (view in listOf(facingABet(cards, board), checkedTo().let { it.copy(board = board, players = it.players.map { p -> if (p.seat == 1) p.copy(cards = cards) else p }) })) {
                val tools = PokerCoachTools(view, Random(9))
                val book = tools.bookAdvice()
                assertNull(tools.objection(book.action), "$cards on $board: ${book.action}")
                assertEquals("book", book.source)
                assertTrue(book.reason.endsWith(".") && "%" in book.reason)
                assertEquals(book.action in setOf("bet", "raise"), book.amount != null)
            }
        }
    }

    @Test
    fun `without a model the book shows its working and advises`() = runBlocking {
        val trace = mutableListOf<TraceEvent>()
        val advice = BookPokerAdvisor(Random(5)).advise(nothingFacingABet) { trace += it }

        assertEquals("fold", advice.action)
        assertEquals(listOf("Estimated how often you win", "Worked out the price of calling", "Advises you to fold"), trace.map { it.label })
    }

    // -------------------------------------------------------------- the coach

    @Test
    fun `the coach looks at the hand through its tools and then advises`() {
        val model = ScriptedModel(looks, advises("raise", 350, "You win about 85% of the time, so make them pay to see another card."))

        val (advice, trace) = coach(model, acesFacingABet)

        assertEquals("raise", advice.action)
        assertEquals(350, advice.amount)
        assertEquals("banca", advice.source)
        assertEquals(
            listOf("Looked at your hand", "Estimated how often you win", "Worked out the price of calling", "Checked what you may do", "Advises you to raise to 350"),
            trace.map { it.label },
        )
        assertEquals(listOf("get_game_state", "get_legal_actions", "get_hand_equity", "get_pot_odds", "give_advice"), model.offeredTools.first())
        assertFalse("submit_action" in model.offeredTools.first(), "a coach can advise but has no way to act")
        assertTrue(trace.dropLast(1).all { it.detail != null }, "nothing is held back: the coach saw only the player's own view")
    }

    @Test
    fun `the coach may choose between plays the figures allow`() {
        val (advice, _) = coach(ScriptedModel(advises("call", reason = "Let them keep betting into you.")), acesFacingABet)

        assertEquals("call", advice.action, "the book would raise, and calling is defensible too")
        assertEquals("banca", advice.source)
    }

    @Test
    fun `advice against the figures is sent back before the player hears it`() {
        val model = ScriptedModel(advises("fold"), advises("check", reason = "It costs nothing to see the next card."))

        val (advice, _) = coach(model, nothingCheckedTo)

        assertEquals("check", advice.action)
        assertTrue(model.conversations[1].any { it is ChatMessage.ToolResult && it.content.startsWith("Error") })
    }

    @Test
    fun `a coach that never settles, fails or is too slow leaves the player with the rule of thumb`() {
        val dithering = ScriptedModel(ModelReply("A tricky spot.", emptyList()))
        val broken = object : ModelProvider {
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply = error("no connection")
        }
        val slow = object : ModelProvider {
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
                delay(5_000)
                return looks
            }
        }

        for (model in listOf(dithering, broken, slow)) {
            val (advice, trace) = coach(model, nothingFacingABet, CoachConfig(maxModelCalls = 2, timeout = 200.milliseconds))
            assertEquals("fold", advice.action)
            assertEquals("book", advice.source)
            assertEquals(TraceEvent.FALLBACK, trace.last().kind)
        }
    }
}

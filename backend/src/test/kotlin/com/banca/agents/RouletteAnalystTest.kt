package com.banca.agents

import com.banca.games.roulette.Bet
import com.banca.games.roulette.Wager
import com.banca.sessions.TraceEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class RouletteAnalystTest {

    private val layout = listOf(Wager(Bet.Straight(17), 10), Wager(Bet.Red, 50), Wager(Bet.Dozen(2), 50))
    private val redAndBlack = listOf(Wager(Bet.Red, 50), Wager(Bet.Black, 50))

    private class ScriptedModel(private vararg val replies: ModelReply) : ModelProvider {
        val conversations = mutableListOf<List<ChatMessage>>()
        val offeredTools = mutableListOf<List<String>>()

        override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
            conversations += messages.toList()
            offeredTools += tools.map { it.name }
            return replies.getOrElse(conversations.size - 1) { replies.last() }
        }
    }

    private val looks = ModelReply("", listOf("get_layout", "get_chances", "get_cost").map { ToolCall(it, JsonObject(emptyMap())) })

    private fun reads(text: String) = ModelReply("", listOf(ToolCall("give_read", buildJsonObject { put("read", text) })))

    private fun analyse(model: ModelProvider, wagers: List<Wager> = layout, config: CoachConfig = CoachConfig()): Pair<LayoutRead, List<TraceEvent>> =
        runBlocking {
            val trace = mutableListOf<TraceEvent>()
            RouletteAnalyst(model, config).read(wagers, chips = 1_000) { trace += it } to trace
        }

    // ------------------------------------------------------------- the tools

    @Test
    fun `the layout is described as it would be at the table`() {
        val described = RouletteTools(layout, 1_000).layout()
        val bets = described.getValue("bets").jsonArray.map { it.jsonObject }

        assertEquals(listOf("the number 17", "red", "the dozen 13–24"), bets.map { it.getValue("on").jsonPrimitive.content })
        assertEquals("35 to 1", bets[0].getValue("pays").jsonPrimitive.content)
        assertEquals("12 of 37 numbers", bets[2].getValue("covers").jsonPrimitive.content)
        assertEquals(110, described.getValue("total_staked").jsonPrimitive.long)
        assertFalse("bets_that_cancel_out" in described)
    }

    @Test
    fun `the analyst is told nothing about where the ball has been`() {
        val tools = RouletteTools(layout, 1_000)
        val everything = tools.layout().toString() + tools.chances() + tools.cost()

        assertFalse("history" in everything || "last" in everything || "previous" in everything)
    }

    @Test
    fun `the chances are shares of the wheel that sum to one`() {
        val chances = RouletteTools(layout, 1_000).chances()
        val shares = chances.filterKeys { it != "note" }.values.sumOf { it.jsonPrimitive.double }

        assertTrue(shares in 0.998..1.002, "$shares")
        assertTrue(chances.getValue("come_out_ahead").jsonPrimitive.double > 0)
    }

    @Test
    fun `the cost is the same share of whatever is staked`() {
        val cost = RouletteTools(layout, 1_000).cost()

        assertEquals(-2.97, cost.getValue("average_per_spin").jsonPrimitive.double)
        assertEquals(-297, cost.getValue("average_over_100_spins").jsonPrimitive.int)
        assertEquals(0.027, cost.getValue("house_edge").jsonPrimitive.double)
        assertEquals(-110, cost.getValue("worst_spin").jsonPrimitive.long)
        assertTrue(cost.getValue("best_spin").jsonPrimitive.long > 0)
    }

    @Test
    fun `bets that cancel out are pointed out`() {
        val described = RouletteTools(redAndBlack, 1_000).layout()
        assertEquals(listOf("red and black"), described.getValue("bets_that_cancel_out").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a read must be something, and short`() {
        val tools = RouletteTools(layout, 1_000)

        assertTrue(tools.giveRead(null).startsWith("Error"))
        assertTrue(tools.giveRead(buildJsonObject { put("read", "   ") }).startsWith("Error"))
        assertTrue(tools.giveRead(buildJsonObject { put("read", "word ".repeat(100)) }).startsWith("Error"))
        assertNull(tools.read)

        assertEquals("Accepted.", tools.giveRead(buildJsonObject { put("read", "  You win  about a third of the time. ") }))
        assertEquals("You win about a third of the time.", assertNotNull(tools.read).text)
    }

    // --------------------------------------------------------------- the book

    @Test
    fun `the figures can speak for themselves`() = runBlocking {
        val trace = mutableListOf<TraceEvent>()
        val read = BookAnalyst().read(layout, 1_000) { trace += it }

        assertEquals("book", read.source)
        assertEquals(110, read.figures.staked)
        assertTrue("costs 3.0 chips a spin" in read.text, read.text)
        assertTrue("%" in read.text)
        assertEquals(TraceEvent.DECISION, trace.last().kind)
        assertEquals(1.0, read.figures.ahead + read.figures.level + read.figures.behind + read.figures.nothing, 0.002)
    }

    @Test
    fun `the book says so when two bets only pay for each other`() = runBlocking {
        val read = BookAnalyst().read(redAndBlack, 1_000) { }

        assertTrue("Red and black cancel each other out" in read.text, read.text)
        assertEquals(0.0, read.figures.ahead)
    }

    // ------------------------------------------------------------ the analyst

    @Test
    fun `the analyst looks at the layout through its tools and then gives its read`() {
        val model = ScriptedModel(looks, reads("You come out ahead about a third of the time, and it costs you 3 chips a spin."))

        val (read, trace) = analyse(model)

        assertEquals("banca", read.source)
        assertEquals("You come out ahead about a third of the time, and it costs you 3 chips a spin.", read.text)
        assertEquals(110, read.figures.staked, "the figures come with it, whatever was said")
        assertEquals(
            listOf("Looked at your bets", "Worked out your chances", "Worked out what it costs", "Gave its read"),
            trace.map { it.label },
        )
        assertEquals(listOf("get_layout", "get_chances", "get_cost", "give_read"), model.offeredTools.first())
        assertTrue(model.conversations[1].any { it is ChatMessage.ToolResult && "house_edge" in it.content })
    }

    @Test
    fun `an analyst that never settles leaves the player with the figures`() {
        val model = ScriptedModel(ModelReply("Roulette is a fascinating game.", emptyList()))

        val (read, trace) = analyse(model, config = CoachConfig(maxModelCalls = 2))

        assertEquals("book", read.source)
        assertEquals(TraceEvent.FALLBACK, trace.last().kind)
        assertEquals(2, model.conversations.size)
    }

    @Test
    fun `a model that fails or is too slow still leaves the player with the figures`() {
        val broken = object : ModelProvider {
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply = error("no connection")
        }
        assertEquals("book", analyse(broken).first.source)

        val slow = object : ModelProvider {
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
                delay(5_000)
                return reads("Too late.")
            }
        }
        val (read, trace) = analyse(slow, config = CoachConfig(timeout = 100.milliseconds))
        assertEquals("book", read.source)
        assertTrue("ran out of time" in trace.last().detail.orEmpty())
    }
}

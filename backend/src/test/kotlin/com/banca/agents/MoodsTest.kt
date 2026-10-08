package com.banca.agents

import com.banca.games.poker.Action
import com.banca.sessions.TraceEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Banca's moods at the poker table: what they are, how they change, and how much of them is let on. */
class MoodsTest {

    @Test
    fun `every mood can be drawn, and steady is the commonest`() {
        val random = Random(7)
        val drawn = List(2_000) { Moods.draw(random) }.groupingBy { it.name }.eachCount()

        assertEquals(Moods.ALL.map { it.name }.toSet(), drawn.keys)
        assertEquals("steady", drawn.maxBy { it.value }.key)
    }

    @Test
    fun `a change of mood is a change to a different one`() {
        val random = Random(3)
        repeat(200) {
            val from = Moods.ALL.random(random)
            assertNotEquals(from.name, Moods.draw(random, not = from).name)
        }
    }

    @Test
    fun `the moods are truly different, from one that hardly bluffs to one that often does`() {
        assertTrue(Moods.PATIENT.bluffs < Moods.STEADY.bluffs)
        assertTrue(Moods.STEADY.bluffs < Moods.PRESSING.bluffs)
        assertEquals(Moods.ALL.size, Moods.ALL.map { it.rules.trimIndent() }.toSet().size)
        assertEquals(Moods.ALL.size, Moods.ALL.map { it.told }.toSet().size)
    }

    @Test
    fun `a mood holds for a few hands and then gives way to another`() {
        val swings = MoodSwings(Random(11), stay = 2..5)
        val byHand = (1..200).map { hand -> swings.forHand(hand).first.name }

        // Runs of the same mood, hand after hand.
        val runs = mutableListOf(1)
        for (i in 1 until byHand.size) if (byHand[i] == byHand[i - 1]) runs[runs.lastIndex]++ else runs += 1
        val settled = runs.drop(1).dropLast(1)

        assertTrue(settled.all { it in 2..5 }, "each mood lasts between two and five hands: $settled")
        assertTrue(byHand.toSet().size >= 3, "and over an evening most of them turn up")
    }

    @Test
    fun `within a hand the mood does not move, and only its first decision is the first`() {
        val swings = MoodSwings(Random(5))

        val (mood, first) = swings.forHand(8)
        assertTrue(first)
        repeat(4) {
            val (again, firstAgain) = swings.forHand(8)
            assertEquals(mood.name, again.name)
            assertFalse(firstAgain)
        }
    }

    // ------------------------------------------------------------ at the table

    private class Recording : ModelProvider {
        val system = mutableListOf<String>()
        val openings = mutableListOf<String>()

        override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
            system += messages.filterIsInstance<ChatMessage.System>().joinToString { it.text }
            openings += messages.filterIsInstance<ChatMessage.User>().first().text
            return ModelReply("", listOf(ToolCall("submit_action", buildJsonObject { put("action", "call") })))
        }
    }

    @Test
    fun `the mood is said once a hand, in words that give nothing away until the hand is over`() = runBlocking {
        val model = Recording()
        val driver = AgentDriver(model, AgentConfig(random = Random(2)))
        val trace = mutableListOf<TraceEvent>()

        driver.decide(facingABet()) { trace += it }
        driver.decide(facingABet()) { trace += it }

        val said = trace.filter { it.label == AgentDriver.MOOD_STEP }
        assertEquals(1, said.size, "two decisions in one hand, one mood")
        assertTrue(Moods.ALL.any { it.told == said.single().detail }, "what it was is in the detail, which is kept back while the hand is live")
        assertNull(said.single().withoutDetail().detail)
        assertTrue(Moods.ALL.none { it.name in AgentDriver.MOOD_STEP.lowercase() }, "and the step is called the same whatever the mood")
    }

    @Test
    fun `the model is told how to play in that mood, and whether this is a turn to bluff`() = runBlocking {
        val model = Recording()
        val driver = AgentDriver(model, AgentConfig(random = Random(4)))

        // Enough hands for the mood to change and for both kinds of turn to come up.
        for (hand in 1..60) assertEquals(Action.Call, driver.decide(facingABet().copy(handNumber = hand)) {})

        assertTrue(model.system.toSet().size >= 3, "different moods are different instructions")
        assertTrue(model.system.all { prompt -> Moods.ALL.any { it.rules.trimIndent() in prompt } })
        assertEquals(setOf(AgentDriver.BLUFF, AgentDriver.NO_BLUFF), model.openings.toSet(), "the dice are thrown for it")
        val bluffed = model.openings.count { it == AgentDriver.BLUFF }
        assertTrue(bluffed in 3..30, "sometimes, and not mostly: $bluffed of 60")
    }
}

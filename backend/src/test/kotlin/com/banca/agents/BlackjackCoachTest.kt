package com.banca.agents

import com.banca.games.blackjack.BlackjackAction
import com.banca.games.blackjack.Strategy
import com.banca.sessions.BlackjackHandView
import com.banca.sessions.BlackjackLegalView
import com.banca.sessions.BlackjackView
import com.banca.sessions.DealerView
import com.banca.sessions.TraceEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** A player's decision as their browser sees it: these cards against that dealer card. */
fun blackjackDecision(
    cards: List<String>,
    total: Int,
    dealerShows: String,
    soft: Boolean = false,
    canDouble: Boolean = cards.size == 2,
    canSplit: Boolean = false,
    phase: String = "player",
) = BlackjackView(
    roundNumber = 3,
    phase = phase,
    stack = 900,
    minBet = 10,
    maxBet = 500,
    lastBet = 100,
    dealer = DealerView(cards = listOf(dealerShows, null), total = 0, soft = false),
    hands = listOf(BlackjackHandView(cards, bet = 100, total = total, soft = soft, status = "playing", outcome = null, returned = null)),
    activeHand = if (phase == "player") 0 else null,
    legal = BlackjackLegalView(
        bet = false,
        hit = phase == "player",
        stand = phase == "player",
        double = phase == "player" && canDouble,
        split = phase == "player" && canSplit,
        insurance = phase == "insurance",
    ),
    insuranceCost = if (phase == "insurance") 50 else 0,
    result = null,
)

class BlackjackCoachTest {

    private val sixteenAgainstTen = blackjackDecision(listOf("Ts", "6d"), total = 16, dealerShows = "Kh")
    private val twelveAgainstSix = blackjackDecision(listOf("Ts", "2d", "7c").take(2), total = 12, dealerShows = "6h")

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
        listOf("get_table_state", "get_action_values", "get_odds").map { ToolCall(it, JsonObject(emptyMap())) },
    )

    private fun advises(action: String, reason: String = "The dealer busts often enough from here.") = ModelReply(
        "",
        listOf(ToolCall("give_advice", buildJsonObject { put("action", action); put("reason", reason) })),
    )

    private fun coach(model: ModelProvider, view: BlackjackView, config: CoachConfig = CoachConfig()): Pair<Advice, List<TraceEvent>> =
        runBlocking {
            val trace = mutableListOf<TraceEvent>()
            BlackjackCoach(model, config = config).advise(view) { trace += it } to trace
        }

    // ------------------------------------------------------------- the tools

    @Test
    fun `the tools tell the coach only what the player can see`() {
        val state = BlackjackTools(sixteenAgainstTen, Strategy()).tableState()

        assertEquals(listOf("Ts", "6d"), state.getValue("your_cards").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Kh", state.getValue("dealer_shows").jsonPrimitive.content)
        assertFalse(state.toString().contains("null"), "the hole card is not there, not even as a blank")
        assertFalse(state.getValue("soft").jsonPrimitive.boolean)
    }

    @Test
    fun `every open play is valued and the best is named`() {
        val values = BlackjackTools(sixteenAgainstTen, Strategy()).actionValues()
        val each = values.getValue("expected_value").jsonObject

        assertEquals(setOf("hit", "stand", "double"), each.keys)
        assertEquals("hit", values.getValue("best").jsonPrimitive.content)
        // The closest call in the game: the two are a hair apart.
        assertTrue(each.getValue("hit").jsonPrimitive.double >= each.getValue("stand").jsonPrimitive.double)
        assertTrue(each.getValue("hit").jsonPrimitive.double > each.getValue("double").jsonPrimitive.double)
        assertTrue(each.getValue("hit").jsonPrimitive.double < 0, "sixteen against a ten loses whatever is done")
    }

    @Test
    fun `the odds are those of the hand in front of the player`() {
        val odds = BlackjackTools(sixteenAgainstTen, Strategy()).odds()

        assertEquals(0.615, odds.getValue("you_bust_if_you_hit").jsonPrimitive.double)
        assertTrue(odds.getValue("dealer_busts").jsonPrimitive.double in 0.2..0.25)
        assertEquals(setOf("17", "18", "19", "20", "21"), odds.getValue("dealer_finishes_on").jsonObject.keys)
    }

    @Test
    fun `advice that is not the best play is refused with the figures`() {
        val tools = BlackjackTools(twelveAgainstSix, Strategy())

        val answer = tools.advise(buildJsonObject { put("action", "hit"); put("reason", "Twelve is low.") })

        assertTrue(answer.startsWith("Error"))
        assertTrue("stand" in answer, "it is told which play the figures prefer")
        assertNull(tools.advice)
    }

    @Test
    fun `advice needs to be open to the player and to come with a reason`() {
        val tools = BlackjackTools(twelveAgainstSix, Strategy())

        assertTrue(tools.advise(buildJsonObject { put("action", "split"); put("reason", "Why not.") }).startsWith("Error"))
        assertTrue(tools.advise(buildJsonObject { put("action", "stand"); put("reason", "  ") }).startsWith("Error"))
        assertTrue(tools.advise(null).startsWith("Error"))
        assertNull(tools.advice)

        assertEquals("Accepted.", tools.advise(buildJsonObject { put("action", "Stand"); put("reason", "The dealer is weak.") }))
        assertEquals("stand", assertNotNull(tools.advice).action)
    }

    @Test
    fun `insurance is advised against, with only those two choices on the table`() {
        val offer = blackjackDecision(listOf("Ts", "9d"), total = 19, dealerShows = "Ah", phase = "insurance")
        val tools = BlackjackTools(offer, Strategy())

        assertEquals(listOf(BlackjackAction.DeclineInsurance, BlackjackAction.Insure), tools.values.map { it.action })
        assertTrue(tools.advise(buildJsonObject { put("action", "insure"); put("reason", "To be safe.") }).startsWith("Error"))

        val book = tools.bookAdvice()
        assertEquals("decline_insurance", book.action)
        assertTrue("4 times in 13" in book.reason)
    }

    @Test
    fun `tools are not built when there is nothing to decide`() {
        val settled = sixteenAgainstTen.copy(phase = "settled", activeHand = null)
        assertFailsWith<IllegalStateException> { BlackjackTools(settled, Strategy()) }
    }

    // --------------------------------------------------------------- the book

    @Test
    fun `the book gives the best play and a reason made from the figures`() = runBlocking {
        val trace = mutableListOf<TraceEvent>()
        val advice = BookAdvisor().advise(twelveAgainstSix) { trace += it }

        assertEquals("stand", advice.action)
        assertEquals("book", advice.source)
        assertEquals("stand", advice.values.first().action, "the values come with it, best first")
        assertTrue("42%" in advice.reason, "it says how often the dealer busts: ${advice.reason}")
        assertTrue("31%" in advice.reason, "and how often the player would: ${advice.reason}")
        assertEquals(TraceEvent.DECISION, trace.last().kind)
    }

    @Test
    fun `the book has something to say for every kind of play`() = runBlocking {
        val hands = listOf(
            sixteenAgainstTen to "hit",
            blackjackDecision(listOf("6s", "5d"), total = 11, dealerShows = "6h") to "double",
            blackjackDecision(listOf("8s", "8d"), total = 16, dealerShows = "Th", canSplit = true) to "split",
            blackjackDecision(listOf("As", "7d"), total = 18, dealerShows = "9h", soft = true) to "hit",
            blackjackDecision(listOf("Ts", "Kd"), total = 20, dealerShows = "6h", canSplit = true) to "stand",
        )
        for ((view, expected) in hands) {
            val advice = BookAdvisor().advise(view) { }
            assertEquals(expected, advice.action)
            assertTrue(advice.reason.length > 30 && advice.reason.first().isUpperCase(), advice.reason)
        }
    }

    // -------------------------------------------------------------- the coach

    @Test
    fun `the coach looks at the hand through its tools and then advises`() {
        val model = ScriptedModel(looks, advises("hit", "You bust 62% of the time, but standing on 16 loses more."))

        val (advice, trace) = coach(model, sixteenAgainstTen)

        assertEquals("hit", advice.action)
        assertEquals("banca", advice.source)
        assertEquals("You bust 62% of the time, but standing on 16 loses more.", advice.reason)
        assertEquals(
            listOf("Looked at your hand", "Worked out what each play is worth", "Checked the odds of busting", "Advises you to hit"),
            trace.map { it.label },
        )
        assertEquals(listOf("get_table_state", "get_action_values", "get_odds", "give_advice"), model.offeredTools.first())
        assertTrue(model.conversations[1].any { it is ChatMessage.ToolResult && "expected_value" in it.content }, "results go back to the model")
    }

    @Test
    fun `a coach that advises the wrong play is corrected before the player hears it`() {
        val model = ScriptedModel(advises("hit"), advises("stand", "The dealer busts 42% of the time from a 6."))

        val (advice, _) = coach(model, twelveAgainstSix)

        assertEquals("stand", advice.action)
        assertEquals("banca", advice.source)
        assertTrue(model.conversations[1].any { it is ChatMessage.ToolResult && it.content.startsWith("Error") })
    }

    @Test
    fun `a coach that never settles leaves the player with the book's advice`() {
        val model = ScriptedModel(ModelReply("Hmm, a tricky one.", emptyList()))

        val (advice, trace) = coach(model, twelveAgainstSix, CoachConfig(maxModelCalls = 3))

        assertEquals("stand", advice.action)
        assertEquals("book", advice.source)
        assertEquals(3, model.conversations.size)
        assertEquals(TraceEvent.FALLBACK, trace.last().kind)
    }

    @Test
    fun `a model that fails or is too slow still leaves the player with the right play`() {
        val broken = object : ModelProvider {
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply = error("no connection")
        }
        assertEquals("hit", coach(broken, sixteenAgainstTen).first.action)

        val slow = object : ModelProvider {
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
                delay(5_000)
                return advises("hit")
            }
        }
        val (advice, trace) = coach(slow, sixteenAgainstTen, CoachConfig(timeout = 100.milliseconds))
        assertEquals("book", advice.source)
        assertTrue("ran out of time" in trace.last().detail.orEmpty())
    }

    @Test
    fun `advice written out as text is still taken`() {
        val model = ScriptedModel(ModelReply("""give_advice {"action": "hit", "reason": "Sixteen loses too often against a ten."}""", emptyList()))

        val (advice, _) = coach(model, sixteenAgainstTen)

        assertEquals("hit", advice.action)
        assertEquals("banca", advice.source)
    }
}

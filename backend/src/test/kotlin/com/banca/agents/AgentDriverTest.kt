package com.banca.agents

import com.banca.games.poker.Action
import com.banca.sessions.TraceEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class AgentDriverTest {

    /** A model that says exactly what the test tells it to, one reply per call. */
    private class ScriptedModel(private vararg val replies: ModelReply) : ModelProvider {
        val conversations = mutableListOf<List<ChatMessage>>()
        val offeredTools = mutableListOf<List<String>>()

        override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
            conversations += messages.toList()
            offeredTools += tools.map { it.name }
            return replies.getOrElse(conversations.size - 1) { replies.last() }
        }
    }

    private fun calls(vararg names: String) =
        ModelReply("", names.map { ToolCall(it, JsonObject(emptyMap())) })

    private fun submits(action: String, amount: Long? = null) = ModelReply(
        "",
        listOf(
            ToolCall(
                "submit_action",
                buildJsonObject {
                    put("action", action)
                    if (amount != null) put("amount", amount)
                },
            ),
        ),
    )

    private fun play(
        model: ModelProvider,
        config: AgentConfig = AgentConfig(),
        view: com.banca.sessions.TableView = facingABet(),
    ): Pair<Action, List<TraceEvent>> = runBlocking {
        val trace = mutableListOf<TraceEvent>()
        // The step that says what mood it is in comes first every hand, and has tests of its own in MoodsTest.
        AgentDriver(model, config).decide(view) { trace += it } to trace.filter { it.label != AgentDriver.MOOD_STEP }
    }

    @Test
    fun `the agent uses its tools and then plays what it submitted`() {
        val model = ScriptedModel(calls("get_game_state", "get_hand_equity", "get_pot_odds"), submits("raise", 400))

        val (action, trace) = play(model)

        assertEquals(Action.Raise(400), action)
        assertEquals(
            listOf("Looked at the table", "Estimated its hand equity", "Worked out the pot odds", "Checked what it may do", "Decided to raise to 400"),
            trace.map { it.label },
        )
    }

    @Test
    fun `the model is offered the tools the server lists`() {
        val model = ScriptedModel(submits("fold"))
        play(model)

        assertEquals(
            setOf("get_game_state", "get_legal_actions", "get_hand_equity", "get_pot_odds", "submit_action"),
            model.offeredTools.first().toSet(),
        )
    }

    @Test
    fun `tool results are fed back to the model`() {
        val model = ScriptedModel(calls("get_pot_odds"), submits("call"))
        play(model)

        val secondAsk = model.conversations[1]
        val results = secondAsk.filterIsInstance<ChatMessage.ToolResult>()
        val result = results.first()
        assertEquals("get_pot_odds", result.toolName, "what it asked for comes first")
        assertTrue("0.25" in result.content, result.content)
        assertEquals(4, results.size, "and the rest of what there is to look at comes with it")
    }

    @Test
    fun `a refused submission can be corrected on the next try`() {
        val model = ScriptedModel(submits("check"), submits("call"))

        val (action, _) = play(model)

        assertEquals(Action.Call, action)
        val refusal = model.conversations[1].filterIsInstance<ChatMessage.ToolResult>().single()
        assertTrue(refusal.content.startsWith("Error"), "the model is told why: ${refusal.content}")
    }

    @Test
    fun `a model that only talks is reminded to submit`() {
        val model = ScriptedModel(ModelReply("I think I should call here.", emptyList()), submits("call"))

        val (action, trace) = play(model)

        assertEquals(Action.Call, action)
        assertTrue(model.conversations[1].last() is ChatMessage.User)
        assertEquals(TraceEvent.THOUGHT, trace.first().kind)
    }

    @Test
    fun `a tool call written out as text is still carried out`() {
        // Exactly what a small local model produced instead of a real call.
        val model = ScriptedModel(ModelReply("""CallCheck submit_action {"action":"call", "amount":0}""", emptyList()))

        val (action, trace) = play(model, view = checkedTo())

        assertEquals(Action.Check, action)
        assertEquals(1, model.conversations.size, "no second ask was needed")
        assertEquals(listOf(TraceEvent.DECISION), trace.map { it.kind })
    }

    @Test
    fun `a call written in function style is carried out too`() {
        val checks = ScriptedModel(ModelReply("""CallCheck submit_action("check", 0)""", emptyList()))
        assertEquals(Action.Check, play(checks, view = checkedTo()).first)

        val raises = ScriptedModel(ModelReply("""submit_action("raise", 450)""", emptyList()))
        assertEquals(Action.Raise(450), play(raises).first)
    }

    @Test
    fun `written calls to several tools are carried out in the order written`() {
        val model = ScriptedModel(
            ModelReply("First get_hand_equity, then get_pot_odds.", emptyList()),
            ModelReply("""submit_action: {"action": "raise", "amount": 300}""", emptyList()),
        )

        val (action, trace) = play(model)

        assertEquals(Action.Raise(300), action)
        assertEquals(listOf("Estimated its hand equity", "Worked out the pot odds"), trace.take(2).map { it.label })
    }

    @Test
    fun `mentioning submit_action without arguments is not a decision`() {
        val model = ScriptedModel(ModelReply("I will use submit_action soon.", emptyList()), submits("fold"))

        val (action, _) = play(model)

        assertEquals(Action.Fold, action)
        assertEquals(2, model.conversations.size)
    }

    @Test
    fun `a model that never decides has the hand played for it once its budget is spent`() {
        val model = ScriptedModel(calls("get_game_state"))

        val (action, trace) = play(model, AgentConfig(maxModelCalls = 3))

        // Aces, facing a bet: whatever the mood, they are not thrown away.
        assertTrue(action == Action.Call || action is Action.Raise, "played by its lines, not given up: $action")
        assertEquals(3, model.conversations.size, "it is asked no more than the budget allows")
        assertEquals(TraceEvent.FALLBACK, trace.last().kind)
    }

    @Test
    fun `played for it, the hand is never folded when checking is free`() {
        val model = ScriptedModel(calls("get_game_state"))

        repeat(20) {
            val (action, _) = play(model, AgentConfig(maxModelCalls = 2), view = checkedTo())
            assertTrue(action == Action.Check || action is Action.Bet, "$action")
        }
    }

    @Test
    fun `a model that fails does not take the table down`() {
        val broken = object : ModelProvider {
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply =
                error("connection refused")
        }

        val (action, trace) = play(broken)

        assertTrue(action == Action.Call || action is Action.Raise, "the hand is played all the same: $action")
        assertEquals(TraceEvent.FALLBACK, trace.last().kind)
        assertEquals(
            listOf("Estimated its hand equity", "Worked out the pot odds"),
            trace.dropLast(1).map { it.label },
            "and the figures it was played from are there to be seen afterwards",
        )
    }

    @Test
    fun `a model that takes too long loses its turn`() {
        val slow = object : ModelProvider {
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
                delay(5_000)
                return ModelReply("", emptyList())
            }
        }

        val (action, trace) = play(slow, AgentConfig(timeout = 100.milliseconds))

        assertTrue(action == Action.Call || action is Action.Raise, "$action")
        assertTrue("time" in assertNotNull(trace.last().detail))
    }

    @Test
    fun `private detail stays out of the label shown during the hand`() {
        val model = ScriptedModel(calls("get_game_state", "get_hand_equity"), submits("call"))

        val (_, trace) = play(model)

        val steps = trace.filter { it.kind == TraceEvent.TOOL }
        assertTrue(steps.all { it.detail != null }, "the detail is kept for after the hand")
        assertTrue(steps.none { "Ah" in it.label || "0." in it.label }, "labels never carry cards or numbers")
        assertTrue(steps.all { it.withoutDetail().detail == null })
        assertNull(trace.last().detail, "the decision itself is public")
    }
}

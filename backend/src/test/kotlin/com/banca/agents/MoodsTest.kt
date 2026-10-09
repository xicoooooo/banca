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

    /** Looks at the table when first asked, as a model does, and calls whatever it is then asked. */
    private class Recording : ModelProvider {
        val system = mutableListOf<String>()
        val openings = mutableListOf<String>()
        val offered = mutableListOf<List<String>>()

        override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
            system += messages.filterIsInstance<ChatMessage.System>().joinToString { it.text }
            openings += messages.filterIsInstance<ChatMessage.User>().first().text
            offered += tools.map { it.name }
            val looked = messages.any { it is ChatMessage.ToolResult }
            return if (looked) ModelReply("", listOf(ToolCall("submit_action", buildJsonObject { put("action", "call") })))
            else ModelReply("", listOf("get_game_state", "get_hand_equity").map { ToolCall(it, buildJsonObject { }) })
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

        val deciding = model.system.filter { it != AgentDriver.BRIEFING }
        assertTrue(deciding.toSet().size >= 3, "different moods are different instructions")
        assertTrue(deciding.all { prompt -> Moods.ALL.any { it.rules.trimIndent() in prompt } })
        assertEquals(setOf(AgentDriver.BLUFF, AgentDriver.NO_BLUFF), model.openings.toSet(), "the dice are thrown for it")
        val bluffed = model.openings.count { it == AgentDriver.BLUFF } / 2
        assertTrue(bluffed in 3..30, "sometimes, and not mostly: $bluffed of 60")
    }

    @Test
    fun `each call to the model carries only what it needs`() = runBlocking {
        val model = Recording()
        val trace = mutableListOf<TraceEvent>()
        AgentDriver(model, AgentConfig(random = Random(4))).decide(facingABet()) { trace += it }

        assertEquals(AgentDriver.BRIEFING, model.system[0], "before it has looked, it is told only to look")
        assertTrue(model.system[0].length < model.system[1].length / 3, "which is a fraction of the words")
        assertEquals(5, model.offered[0].size, "with every tool to hand")
        assertEquals(listOf("submit_action"), model.offered[1], "and once it has looked, only the tool that finishes")
    }

    @Test
    fun `a model that reaches for one thing to look at is shown the rest alongside`() = runBlocking {
        // This one asks for two of the four, as some models do, where others ask for one or for all.
        val model = Recording()
        val trace = mutableListOf<TraceEvent>()
        AgentDriver(model, AgentConfig(random = Random(4))).decide(facingABet()) { trace += it }

        assertEquals(
            setOf("Looked at the table", "Estimated its hand equity", "Worked out the pot odds", "Checked what it may do"),
            trace.filter { it.kind == TraceEvent.TOOL }.map { it.label }.toSet(),
            "all four are looked at, and shown to have been",
        )
        assertEquals(2, model.system.size, "so the decision takes two asks of the model, whatever kind it is")
    }

    // ------------------------------------------------------- with no model at all

    private val weak = facingABet(cards = listOf("7h", "2d"), board = listOf("Ks", "Qd", "9c"))
    private val weakCheckedTo = checkedTo().let { view ->
        view.copy(board = listOf("Ks", "Qd", "9c"), players = view.players.map { if (it.seat == 1) it.copy(cards = listOf("7h", "2d")) else it })
    }

    private fun byTheLines(view: com.banca.sessions.TableView, mood: Mood, bluffing: Boolean = false, river: Boolean = false) =
        ByTheLines.play(PokerTools(view, Random(5)), view.legal!!, mood, bluffing, river)

    @Test
    fun `played by its lines, a strong hand is never given up and a weak one is not paid for`() {
        for (mood in Moods.ALL) {
            val strong = byTheLines(facingABet(), mood)
            assertTrue(strong == Action.Call || strong is Action.Raise, "${mood.name} with aces: $strong")
            assertEquals(Action.Fold, byTheLines(weak, mood), "${mood.name} with nothing, and no bluff called for")
            assertEquals(Action.Check, byTheLines(weakCheckedTo, mood), "${mood.name} with nothing, and nothing to call")
        }
    }

    @Test
    fun `the moods play the same hand differently`() {
        // Aces checked to: most bet them, the sly one waits for the river.
        assertTrue(byTheLines(checkedTo(), Moods.STEADY) is Action.Bet)
        assertTrue(byTheLines(checkedTo(), Moods.PRESSING) is Action.Bet)
        assertEquals(Action.Check, byTheLines(checkedTo(), Moods.SLY), "a trap is laid before the river")
        assertTrue(byTheLines(checkedTo(), Moods.SLY, river = true) is Action.Bet, "and sprung on it")

        // Facing a bet with them, the pressing mood raises by the whole pot where the steady one raises by half.
        val raisedSteadily = byTheLines(facingABet(), Moods.STEADY) as Action.Raise
        val raisedHard = byTheLines(facingABet(), Moods.PRESSING) as Action.Raise
        assertTrue(raisedHard.to > raisedSteadily.to)

        // The pressing mood bets bigger than the steady one with the same hand.
        val steady = byTheLines(checkedTo(), Moods.STEADY) as Action.Bet
        val pressing = byTheLines(checkedTo(), Moods.PRESSING) as Action.Bet
        assertTrue(pressing.amount >= steady.amount)
    }

    @Test
    fun `a bluff is a bet with nothing, made only on a turn the dice called for one`() {
        for (mood in Moods.ALL) {
            assertTrue(byTheLines(weakCheckedTo, mood, bluffing = true) is Action.Bet, "${mood.name} bluffs when told to")
            assertTrue(byTheLines(weak, mood, bluffing = true) is Action.Raise, "and raises a bet small enough to push back at")
        }
    }

    @Test
    fun `whatever is played by the lines is something the table allows`() {
        for (mood in Moods.ALL) for (view in listOf(facingABet(), checkedTo(), weak, weakCheckedTo)) for (bluffing in listOf(false, true)) {
            val legal = view.legal!!
            when (val action = byTheLines(view, mood, bluffing)) {
                is Action.Bet -> assertTrue(legal.canBet && action.amount in legal.minBet..legal.maxTo)
                is Action.Raise -> assertTrue(legal.canRaise && action.to in legal.minRaiseTo..legal.maxTo)
                Action.Call -> assertTrue(legal.canCall)
                Action.Check -> assertTrue(legal.canCheck)
                Action.Fold -> assertFalse(legal.canCheck, "never folded when checking is free")
            }
        }
    }
}

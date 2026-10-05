package com.banca.agents

import com.banca.games.poker.Action
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PokerToolsTest {

    private fun tools(view: com.banca.sessions.TableView = facingABet()) =
        PokerTools(view, random = Random(7), equityIterations = 2_000)

    /** Runs [block] with an MCP client connected to the tools, as the agent is. */
    private fun overMcp(tools: PokerTools, block: suspend (Client) -> Unit) = runBlocking {
        val link = CoroutineScope(currentCoroutineContext() + Job())
        try {
            val (serverEnd, clientEnd) = LinkedTransport.pair(link)
            tools.server().createSession(serverEnd)
            val client = Client(Implementation(name = "test", version = "0"))
            client.connect(clientEnd)
            block(client)
        } finally {
            link.cancel()
        }
    }

    private suspend fun Client.call(name: String, arguments: JsonObject = JsonObject(emptyMap())): String =
        callTool(CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)))
            .content.filterIsInstance<TextContent>().joinToString("") { it.text }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the server lists exactly the poker tools`() = overMcp(tools()) { client ->
        assertEquals(
            setOf("get_game_state", "get_legal_actions", "get_hand_equity", "get_pot_odds", "submit_action"),
            client.listTools().tools.map { it.name }.toSet(),
        )
    }

    @Test
    fun `game state holds the seat's own cards and nothing hidden`() = overMcp(tools()) { client ->
        val text = client.call("get_game_state")
        val state = json(text)

        assertEquals(listOf("Ah", "Ad"), state.getValue("your_cards").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(300, state.getValue("pot").jsonPrimitive.content.toInt())
        assertFalse("cards" in state.getValue("opponents").jsonArray.single().jsonObject, "no opponent cards, ever")
    }

    @Test
    fun `equity and pot odds come back as shares`() = overMcp(tools()) { client ->
        val equity = json(client.call("get_hand_equity")).getValue("equity").jsonPrimitive.double
        assertTrue(equity > 0.7, "aces on a dry board are a big favourite, got $equity")

        val odds = json(client.call("get_pot_odds")).getValue("pot_odds").jsonPrimitive.double
        assertEquals(0.25, odds, "calling 100 into 300")
    }

    @Test
    fun `legal actions describe only what is allowed`() = overMcp(tools()) { client ->
        val legal = json(client.call("get_legal_actions"))

        assertEquals(listOf("fold", "call", "raise"), legal.getValue("actions").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(200, legal.getValue("raise_to_amount").jsonObject.getValue("min").jsonPrimitive.content.toInt())
    }

    @Test
    fun `submitting a legal action over the protocol records it`() {
        val tools = tools()

        overMcp(tools) { client ->
            val reply = client.call("submit_action", buildJsonObject { put("action", "raise"); put("amount", 400) })
            assertTrue(reply.startsWith("Accepted"), reply)
        }

        assertEquals(Action.Raise(400), tools.decision)
    }

    // The rules of submit are plain logic, so they are tested directly.

    private fun submit(tools: PokerTools, action: String, amount: Long? = null): String =
        tools.submit(
            buildJsonObject {
                put("action", action)
                if (amount != null) put("amount", amount)
            },
        )

    @Test
    fun `checking into a bet is refused and nothing is recorded`() {
        val tools = tools()
        assertTrue(submit(tools, "check").startsWith("Error"))
        assertNull(tools.decision)
    }

    @Test
    fun `an unknown action or a missing amount is refused`() {
        val tools = tools()
        assertTrue(submit(tools, "dance").startsWith("Error"))
        assertTrue(submit(tools, "raise").startsWith("Error"))
        assertTrue(tools.submit(null).startsWith("Error"))
        assertNull(tools.decision)
    }

    @Test
    fun `a raise outside the limits is pulled back inside them`() {
        val low = tools().also { submit(it, "raise", 150) }
        assertEquals(Action.Raise(200), low.decision, "below the minimum becomes the minimum")

        val high = tools().also { submit(it, "raise", 50_000) }
        assertEquals(Action.Raise(1900), high.decision, "above the stack becomes all-in")
    }

    @Test
    fun `bet and raise are read as whichever one is legal`() {
        val facingBet = tools().also { submit(it, "bet", 400) }
        assertEquals(Action.Raise(400), facingBet.decision)

        val unopened = tools(checkedTo()).also { submit(it, "raise", 120) }
        assertEquals(Action.Bet(120), unopened.decision)
    }

    @Test
    fun `calling when nothing is owed is a check`() {
        val tools = tools(checkedTo()).also { submit(it, "call") }
        assertEquals(Action.Check, tools.decision)
    }

    @Test
    fun `folding to a bet is accepted`() {
        val tools = tools().also { submit(it, "fold") }
        assertEquals(Action.Fold, tools.decision)
    }

    @Test
    fun `folding when checking is free becomes a check`() {
        val tools = tools(checkedTo()).also { submit(it, "fold") }
        assertEquals(Action.Check, tools.decision)
    }
}

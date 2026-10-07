package com.banca.ws

import com.banca.agents.BookPokerAdvisor
import com.banca.agents.PokerAdvisor
import com.banca.module
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Asking the coach at the poker table. */
class PokerCoachSocketTest {

    private fun table(coach: PokerAdvisor = BookPokerAdvisor(Random(2)), test: suspend DefaultClientWebSocketSession.() -> Unit) = testApplication {
        val guest = TestPlayers()
        application {
            module(TableSocketConfig(opponentDelay = Duration.ZERO, random = { Random(3) }, coach = coach), players = guest.players)
        }
        createClient { install(WebSockets) }.webSocket("/ws/table") {
            sayHello(guest.token)
            test()
        }
    }

    private suspend fun DefaultClientWebSocketSession.say(json: String) = send(Frame.Text(json))

    private val JsonObject.type: String get() = getValue("type").jsonPrimitive.content

    private suspend fun DefaultClientWebSocketSession.nextOf(type: String): JsonObject {
        while (true) {
            val message = receiveJson()
            if (message.type == type) return message
        }
    }

    /** Plays on until it is the player's turn, dealing again if a hand ends first. */
    private suspend fun DefaultClientWebSocketSession.myTurn(): JsonObject {
        while (true) {
            val view = nextOf("state").getValue("view").jsonObject
            if (view["actorSeat"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int == 0) return view
            if (view["result"] !is JsonNull) say("""{"type":"next_hand"}""")
        }
    }

    @Test
    fun `asked, the coach shows its working and advises a play that is open`() = table {
        val view = myTurn()
        val legal = view.getValue("legal").jsonObject

        say("""{"type":"advise"}""")

        val steps = mutableListOf<JsonObject>()
        var message = receiveJson()
        while (message.type == "coach_trace") {
            steps += message.getValue("event").jsonObject
            message = receiveJson()
        }

        assertEquals("advice", message.type)
        assertEquals(view.getValue("handNumber").jsonPrimitive.int, message.getValue("handNumber").jsonPrimitive.int)
        val advice = message.getValue("advice").jsonObject
        val action = advice.getValue("action").jsonPrimitive.content
        assertTrue(action == "fold" || legal.getValue("can${action.replaceFirstChar(Char::uppercase)}").jsonPrimitive.boolean)
        assertTrue(advice.getValue("reason").jsonPrimitive.content.isNotBlank())
        assertEquals(1, advice.getValue("figures").jsonObject.getValue("opponents").jsonPrimitive.int)
        assertTrue(steps.size >= 2 && steps.dropLast(1).all { it.getValue("detail").jsonPrimitive.isString }, "its working is shown in full")
    }

    @Test
    fun `asking twice about the same decision is answered once and repeated`() {
        var asked = 0
        val counting = PokerAdvisor { view, trace -> asked++; BookPokerAdvisor(Random(2)).advise(view, trace) }

        table(counting) {
            myTurn()
            say("""{"type":"advise"}""")
            val first = nextOf("advice").getValue("advice")
            say("""{"type":"advise"}""")
            assertEquals(first, nextOf("advice").getValue("advice"))
            assertEquals(1, asked)
        }
    }

    @Test
    fun `a player who acts while the coach is thinking is not kept waiting, and the stale advice never comes`() {
        val slow = PokerAdvisor { view, trace ->
            delay(30_000)
            BookPokerAdvisor().advise(view, trace)
        }

        table(slow) {
            myTurn()
            say("""{"type":"advise"}""")
            say("""{"type":"act","action":"fold"}""")

            assertEquals("state", withTimeout(3_000) { receiveJson() }.type)
        }
    }

    @Test
    fun `a coach that breaks does not take the table with it`() {
        val broken = PokerAdvisor { _, _ -> error("the coach fell over") }

        table(broken) {
            myTurn()
            say("""{"type":"advise"}""")
            assertEquals("error", receiveJson().type)

            say("""{"type":"act","action":"fold"}""")
            assertEquals("state", receiveJson().type, "and the hand carries on")
        }
    }

    @Test
    fun `there is nothing to advise on once the hand is over`() = table {
        myTurn()
        say("""{"type":"act","action":"fold"}""")
        nextOf("state")

        say("""{"type":"advise"}""")
        assertEquals("error", nextOf("error").type)
    }
}

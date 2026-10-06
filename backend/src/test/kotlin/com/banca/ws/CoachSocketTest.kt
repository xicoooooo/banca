package com.banca.ws

import com.banca.agents.Advice
import com.banca.agents.BlackjackAdvisor
import com.banca.agents.BookAdvisor
import com.banca.module
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Asking the coach at the blackjack table. */
class CoachSocketTest {

    private fun table(
        advisor: BlackjackAdvisor = BookAdvisor(),
        guest: TestPlayers = TestPlayers(),
        test: suspend DefaultClientWebSocketSession.() -> Unit,
    ) = testApplication {
        application { module(blackjack = BlackjackSocketConfig(random = { Random(11) }, advisor = advisor), players = guest.players) }
        createClient { install(WebSockets) }.webSocket("/ws/blackjack") {
            sayHello(guest.token)
            receiveJson()
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

    /** Bets until a round is dealt that leaves the player something to decide, and returns its view. */
    private suspend fun DefaultClientWebSocketSession.dealADecision(): JsonObject {
        while (true) {
            say("""{"type":"bet","amount":50}""")
            val view = nextOf("state").getValue("view").jsonObject
            if (view.getValue("phase").jsonPrimitive.content == "player") return view
            if (view.getValue("phase").jsonPrimitive.content == "insurance") {
                say("""{"type":"act","action":"decline_insurance"}""")
                val after = nextOf("state").getValue("view").jsonObject
                if (after.getValue("phase").jsonPrimitive.content == "player") return after
            }
        }
    }

    private fun legalIn(view: JsonObject): Set<String> =
        view.getValue("legal").jsonObject.filterValues { it.jsonPrimitive.boolean }.keys

    @Test
    fun `asked, the coach shows its working and then advises a play that is open`() = table {
        val view = dealADecision()

        say("""{"type":"advise"}""")

        val steps = mutableListOf<String>()
        var message = receiveJson()
        while (message.type == "trace") {
            steps += message.getValue("event").jsonObject.getValue("label").jsonPrimitive.content
            assertTrue(message.getValue("event").jsonObject.getValue("detail").jsonPrimitive.isString || steps.size == 3, "the working is shown as it happens")
            message = receiveJson()
        }

        assertEquals("advice", message.type)
        assertEquals(view.getValue("roundNumber").jsonPrimitive.int, message.getValue("roundNumber").jsonPrimitive.int)
        val advice = message.getValue("advice").jsonObject
        assertTrue(advice.getValue("action").jsonPrimitive.content in legalIn(view))
        assertTrue(advice.getValue("reason").jsonPrimitive.content.isNotBlank())
        assertEquals(advice.getValue("action"), advice.getValue("values").jsonArray.first().jsonObject.getValue("action"))
        assertTrue(steps.size >= 2 && steps.last().startsWith("Advises you to"))
    }

    @Test
    fun `asking twice about the same decision is answered once and repeated`() {
        var asked = 0
        val counting = BlackjackAdvisor { view, trace -> asked++; BookAdvisor().advise(view, trace) }

        table(advisor = counting) {
            dealADecision()
            say("""{"type":"advise"}""")
            val first = nextOf("advice").getValue("advice")
            say("""{"type":"advise"}""")
            assertEquals(first, nextOf("advice").getValue("advice"))
            assertEquals(1, asked)
        }
    }

    @Test
    fun `there is nothing to advise on between rounds`() = table {
        say("""{"type":"advise"}""")
        assertEquals("error", receiveJson().type)

        say("""{"type":"bet","amount":50}""")
        assertEquals("state", receiveJson().type, "and the table carries on")
    }

    @Test
    fun `a player who acts while the coach is thinking is not kept waiting`() {
        val slow = BlackjackAdvisor { view, trace ->
            delay(30_000)
            BookAdvisor().advise(view, trace)
        }

        table(advisor = slow) {
            dealADecision()
            say("""{"type":"advise"}""")
            say("""{"type":"act","action":"stand"}""")

            val next = withTimeout(3_000) { receiveJson() }
            assertEquals("state", next.type, "the action is taken at once, and the stale advice never arrives")
        }
    }

    @Test
    fun `a coach that breaks does not take the table with it`() {
        val broken = BlackjackAdvisor { _, _ -> error("the coach fell over") }

        table(advisor = broken) {
            dealADecision()
            say("""{"type":"advise"}""")
            assertEquals("error", receiveJson().type)

            say("""{"type":"act","action":"stand"}""")
            assertEquals("state", receiveJson().type)
        }
    }

    @Test
    fun `how the player's decisions compare with the best play is kept with the round`() {
        val guest = TestPlayers()

        table(guest = guest) {
            var view = dealADecision()
            var decisions = 0
            // Does what the coach says, every time, until the round is over.
            while (view.getValue("phase").jsonPrimitive.content == "player") {
                say("""{"type":"advise"}""")
                val action = nextOf("advice").getValue("advice").jsonObject.getValue("action").jsonPrimitive.content
                say("""{"type":"act","action":"$action"}""")
                decisions++
                view = nextOf("state").getValue("view").jsonObject
            }

            val detail = guest.store.rounds(guest.player.id, 1).single().detail
            assertEquals(decisions, detail.getValue("advised").jsonPrimitive.int)
            assertEquals(decisions, detail.getValue("followedAdvice").jsonPrimitive.int)
            assertEquals(detail.getValue("decisions"), detail.getValue("byTheBook"), "following the coach is playing by the book")
            assertTrue(detail.getValue("decisions").jsonPrimitive.int >= decisions)
        }
    }

    @Test
    fun `going against the best play is counted as such`() {
        val guest = TestPlayers()

        table(guest = guest) {
            val view = dealADecision()
            say("""{"type":"advise"}""")
            val advised = nextOf("advice").getValue("advice").jsonObject.getValue("action").jsonPrimitive.content
            // Whichever of the two simplest plays the coach did not choose.
            val other = if (advised == "stand") "hit" else "stand"
            say("""{"type":"act","action":"$other"}""")
            var after = nextOf("state").getValue("view").jsonObject
            while (after.getValue("phase").jsonPrimitive.content == "player") {
                say("""{"type":"act","action":"stand"}""")
                after = nextOf("state").getValue("view").jsonObject
            }
            check(view.getValue("roundNumber") == after.getValue("roundNumber"))

            val detail = guest.store.rounds(guest.player.id, 1).single().detail
            assertEquals(1, detail.getValue("advised").jsonPrimitive.int)
            assertEquals(0, detail.getValue("followedAdvice").jsonPrimitive.int)
        }
    }
}

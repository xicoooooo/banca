package com.banca.ws

import com.banca.module
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlackjackSocketTest {

    private fun table(seed: Int = 4, test: suspend DefaultClientWebSocketSession.() -> Unit) = testApplication {
        val guest = TestPlayers()
        application { module(blackjack = BlackjackSocketConfig(random = { Random(seed) }), players = guest.players) }
        createClient { install(WebSockets) }.webSocket("/ws/blackjack") {
            sayHello(guest.token)
            test()
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveMessage(): JsonObject =
        wireJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject

    private suspend fun DefaultClientWebSocketSession.receiveView(): JsonObject =
        receiveMessage().also { assertEquals("state", it.type) }.getValue("view").jsonObject

    private suspend fun DefaultClientWebSocketSession.say(json: String) = send(Frame.Text(json))

    private val JsonObject.type: String get() = getValue("type").jsonPrimitive.content
    private val JsonObject.phase: String get() = getValue("phase").jsonPrimitive.content

    @Test
    fun `connecting opens a table that is waiting for a bet`() = table {
        val view = receiveView()

        assertEquals("betting", view.phase)
        assertEquals(0, view.getValue("roundNumber").jsonPrimitive.int)
        assertTrue(view.getValue("dealer") is JsonNull, "nothing is dealt until chips are down")
    }

    @Test
    fun `a round can be bet, played and settled over the socket`() = table {
        receiveView()

        say("""{"type":"bet","amount":100}""")
        var view = receiveView()
        assertEquals(1, view.getValue("roundNumber").jsonPrimitive.int)
        assertEquals(2, view.getValue("hands").jsonArray.single().jsonObject.getValue("cards").jsonArray.size)

        var guard = 0
        while (view.phase != "settled") {
            check(guard++ < 10) { "the round did not settle" }
            say(if (view.phase == "insurance") """{"type":"act","action":"decline_insurance"}""" else """{"type":"act","action":"stand"}""")
            view = receiveView()
        }

        val dealer = view.getValue("dealer").jsonObject.getValue("cards").jsonArray
        assertTrue(dealer.none { it is JsonNull }, "the hole card is shown once the round is over")
        assertTrue(view.getValue("result") !is JsonNull)
    }

    @Test
    fun `the hole card is never sent while the round is live`() {
        // Find a deal that is not settled at once, then look at exactly what went over the wire.
        for (seed in 1..40) {
            var checked = false
            table(seed) {
                receiveView()
                say("""{"type":"bet","amount":100}""")
                val view = receiveView()
                if (view.phase == "player") {
                    val cards = view.getValue("dealer").jsonObject.getValue("cards").jsonArray
                    assertTrue(cards[0] !is JsonNull)
                    assertTrue(cards[1] is JsonNull)
                    checked = true
                }
            }
            if (checked) return
        }
        error("no seed produced a round to play")
    }

    @Test
    fun `what is not allowed is refused and the table carries on`() = table {
        receiveView()

        say("""{"type":"act","action":"hit"}""")
        assertEquals("error", receiveMessage().type, "there is no round yet")

        say("""{"type":"bet","amount":5}""")
        assertEquals("error", receiveMessage().type, "below the table minimum")

        say("this is not json")
        assertEquals("error", receiveMessage().type)

        say("""{"type":"act","action":"dance"}""")
        assertEquals("error", receiveMessage().type)

        say("""{"type":"bet","amount":100}""")
        assertEquals("state", receiveMessage().type, "the table still works afterwards")
    }
}

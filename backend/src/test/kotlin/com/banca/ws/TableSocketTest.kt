package com.banca.ws

import com.banca.games.poker.Action
import com.banca.module
import com.banca.sessions.SeatDriver
import com.banca.sessions.TraceEvent
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
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
import kotlin.time.Duration

class TableSocketTest {

    private fun table(test: suspend DefaultClientWebSocketSession.() -> Unit) = testApplication {
        val guest = TestPlayers()
        application {
            module(TableSocketConfig(opponentDelay = Duration.ZERO, random = { Random(3) }), players = guest.players)
        }
        socketClient().webSocket("/ws/table") {
            sayHello(guest.token)
            test()
        }
    }

    private fun ApplicationTestBuilder.socketClient() = createClient { install(WebSockets) }

    private suspend fun DefaultClientWebSocketSession.receiveMessage(): JsonObject =
        wireJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject

    /** Reads states until the person has a decision or the hand has ended. */
    private suspend fun DefaultClientWebSocketSession.receiveUntilMyTurnOrOver(): JsonObject {
        while (true) {
            val view = receiveMessage().also { assertEquals("state", it.type) }.getValue("view").jsonObject
            val myTurn = view["actorSeat"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int == 0
            if (myTurn || view["result"] !is JsonNull) return view
        }
    }

    private val JsonObject.type: String get() = getValue("type").jsonPrimitive.content

    @Test
    fun `connecting deals a hand and sends the person their view`() = table {
        val view = receiveMessage().also { assertEquals("state", it.type) }.getValue("view").jsonObject

        assertEquals(0, view.getValue("yourSeat").jsonPrimitive.int)
        assertEquals(1, view.getValue("handNumber").jsonPrimitive.int)

        val players = view.getValue("players").jsonArray.map { it.jsonObject }
        assertEquals(2, players.size)
        assertEquals(2, players[0].getValue("cards").jsonArray.size, "the person sees their own cards")
        assertTrue(players[1].getValue("cards") is JsonNull, "and never the opponent's")
    }

    @Test
    fun `folding ends the hand and a new one can be dealt`() = table {
        receiveUntilMyTurnOrOver()

        send(Frame.Text("""{"type":"act","action":"fold"}"""))
        val over = receiveUntilMyTurnOrOver()
        assertTrue(over["result"] !is JsonNull)

        send(Frame.Text("""{"type":"next_hand"}"""))
        val next = receiveMessage().getValue("view").jsonObject
        assertEquals(2, next.getValue("handNumber").jsonPrimitive.int)
    }

    @Test
    fun `a whole hand can be played to showdown over the socket`() = table {
        var view = receiveUntilMyTurnOrOver()

        var guard = 0
        while (view["result"] is JsonNull) {
            check(guard++ < 50) { "the hand did not finish" }
            val canCheck = view.getValue("legal").jsonObject.getValue("canCheck").jsonPrimitive.content == "true"
            send(Frame.Text("""{"type":"act","action":"${if (canCheck) "check" else "call"}"}"""))
            view = receiveUntilMyTurnOrOver()
        }

        assertEquals("showdown", view.getValue("street").jsonPrimitive.content)
        assertEquals(5, view.getValue("board").jsonArray.size)
        val players = view.getValue("players").jsonArray.map { it.jsonObject }
        assertTrue(players.all { it.getValue("cards") !is JsonNull }, "the showdown reveals both hands")
    }

    @Test
    fun `an illegal action is answered with an error and the table carries on`() = table {
        val view = receiveUntilMyTurnOrOver()
        val maxTo = view.getValue("legal").jsonObject.getValue("maxTo").jsonPrimitive.content.toLong()

        send(Frame.Text("""{"type":"act","action":"raise","amount":${maxTo + 1}}"""))
        assertEquals("error", receiveMessage().type)

        send(Frame.Text("""{"type":"act","action":"fold"}"""))
        assertTrue(receiveUntilMyTurnOrOver()["result"] !is JsonNull, "the table still works afterwards")
    }

    @Test
    fun `the opponent has its say once the hand is over, and only once`() = testApplication {
        val talkative = object : SeatDriver {
            override suspend fun decide(view: com.banca.sessions.TableView, trace: suspend (TraceEvent) -> Unit): Action =
                if (view.legal!!.canCheck) Action.Check else Action.Call

            override fun remark(view: com.banca.sessions.TableView, net: Long): String = "Hand ${view.handNumber} came to $net."
        }
        val guest = TestPlayers()
        application {
            module(TableSocketConfig(opponentDelay = Duration.ZERO, opponent = { talkative }, random = { Random(3) }), players = guest.players)
        }

        socketClient().webSocket("/ws/table") {
            sayHello(guest.token)
            var handOver = false
            val remarks = mutableListOf<JsonObject>()

            // The first hand is folded, and the second asked for, by which time anything said about the first has been heard.
            while (true) {
                val message = receiveMessage()
                if (message.type == "remark") {
                    assertTrue(handOver, "nothing is said while the hand is live")
                    remarks += message
                }
                if (message.type != "state") continue
                val view = message.getValue("view").jsonObject
                if (view.getValue("handNumber").jsonPrimitive.int == 2) break
                handOver = view["result"] !is JsonNull
                if (handOver) send(Frame.Text("""{"type":"next_hand"}"""))
                else if (view["actorSeat"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int == 0) send(Frame.Text("""{"type":"act","action":"fold"}"""))
            }

            assertEquals(1, remarks.size)
            assertEquals(1, remarks.single().getValue("handNumber").jsonPrimitive.int)
            assertTrue(remarks.single().getValue("text").jsonPrimitive.content.startsWith("Hand 1 came to "))
        }
    }

    @Test
    fun `the opponent's reasoning is held back until the hand is over`() = testApplication {
        val chatty = SeatDriver { view, trace ->
            trace(TraceEvent(TraceEvent.TOOL, "Looked at the table", detail = "SECRET my cards are strong"))
            if (view.legal!!.canCheck) Action.Check else Action.Call
        }
        val guest = TestPlayers()
        application {
            module(
                TableSocketConfig(opponentDelay = Duration.ZERO, opponent = { chatty }, random = { Random(3) }),
                players = guest.players,
            )
        }

        socketClient().webSocket("/ws/table") {
            sayHello(guest.token)
            val duringHand = mutableListOf<String>()
            var handOver = false

            while (true) {
                val raw = (incoming.receive() as Frame.Text).readText()
                val message = wireJson.parseToJsonElement(raw).jsonObject

                when (message.type) {
                    "reveal" -> {
                        assertTrue(handOver, "the reveal must not arrive while the hand is live")
                        assertTrue("SECRET" in raw)
                        break
                    }
                    "trace" -> duringHand += raw
                    "state" -> {
                        val view = message.getValue("view").jsonObject
                        handOver = view["result"] !is JsonNull
                        val myTurn = view["actorSeat"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int == 0
                        if (myTurn) {
                            val canCheck = view.getValue("legal").jsonObject.getValue("canCheck").jsonPrimitive.content == "true"
                            send(Frame.Text("""{"type":"act","action":"${if (canCheck) "check" else "call"}"}"""))
                        }
                    }
                }
            }

            assertTrue(duringHand.isNotEmpty(), "steps are still shown live")
            assertTrue(duringHand.none { "SECRET" in it }, "but never with their private detail")
            assertTrue(duringHand.all { "Looked at the table" in it })
        }
    }

    @Test
    fun `nonsense is answered with an error rather than a dropped connection`() = table {
        receiveUntilMyTurnOrOver()

        send(Frame.Text("this is not json"))
        assertEquals("error", receiveMessage().type)

        send(Frame.Text("""{"type":"act","action":"dance"}"""))
        assertEquals("error", receiveMessage().type)

        send(Frame.Text("""{"type":"next_hand"}"""))
        assertEquals("error", receiveMessage().type, "a hand is still in progress")
    }
}

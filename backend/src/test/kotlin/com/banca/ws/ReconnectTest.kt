package com.banca.ws

import com.banca.module
import com.banca.players.Game
import com.banca.players.RoundOutcome
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** A table waits for a player whose connection has dropped, and is not a way out of a losing round. */
class ReconnectTest {

    private fun ApplicationTestBuilder.serve(guest: TestPlayers, keepTablesFor: Duration = 3.minutes) {
        application {
            module(
                tableSocket = TableSocketConfig(opponentDelay = Duration.ZERO, random = { Random(3) }),
                blackjack = BlackjackSocketConfig(random = { Random(11) }),
                roulette = RouletteSocketConfig(random = { Random(5) }),
                players = guest.players,
                keepTablesFor = keepTablesFor,
            )
        }
    }

    private fun ApplicationTestBuilder.sockets() = createClient { install(WebSockets) }

    private suspend fun DefaultClientWebSocketSession.say(json: String) = send(Frame.Text(json))

    private suspend fun DefaultClientWebSocketSession.nextView(): JsonObject {
        while (true) {
            val message = receiveJson()
            if (message.getValue("type").jsonPrimitive.content == "state") return message.getValue("view").jsonObject
        }
    }

    private val JsonObject.phase: String get() = getValue("phase").jsonPrimitive.content

    /** Bets at blackjack until a round leaves the player something to decide. */
    private suspend fun DefaultClientWebSocketSession.dealADecision(): JsonObject {
        while (true) {
            say("""{"type":"bet","amount":50}""")
            var view = nextView()
            if (view.phase == "insurance") {
                say("""{"type":"act","action":"decline_insurance"}""")
                view = nextView()
            }
            if (view.phase == "player") return view
        }
    }

    /** Waits for the server to have done something it does in its own time. */
    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(5_000) {
        while (!check()) delay(20)
    }

    @Test
    fun `a blackjack round is still there after the connection drops`() = testApplication {
        val guest = TestPlayers()
        serve(guest)
        lateinit var left: JsonObject

        sockets().webSocket("/ws/blackjack") {
            sayHello(guest.token)
            nextView()
            left = dealADecision()
        }

        sockets().webSocket("/ws/blackjack") {
            sayHello(guest.token)
            val back = nextView()

            assertEquals(left.getValue("roundNumber"), back.getValue("roundNumber"))
            assertEquals(left.getValue("hands"), back.getValue("hands"), "the same cards, the same bet")
            assertEquals(left.getValue("dealer"), back.getValue("dealer"))
            assertEquals("player", back.phase)

            say("""{"type":"act","action":"stand"}""")
            assertEquals("settled", nextView().phase, "and it can be played to the end")
        }

        assertEquals(1, guest.store.rounds(guest.player.id, 10).count { it.game == Game.BLACKJACK }, "written down once")
    }

    @Test
    fun `a poker hand is still there after the connection drops`() = testApplication {
        val guest = TestPlayers()
        serve(guest)
        lateinit var left: JsonObject

        sockets().webSocket("/ws/table") {
            sayHello(guest.token)
            left = nextView()
            while (left.getValue("actorSeat").let { it is JsonNull || it.jsonPrimitive.int != 0 }) left = nextView()
        }

        sockets().webSocket("/ws/table") {
            sayHello(guest.token)
            val back = nextView()

            assertEquals(left.getValue("handNumber"), back.getValue("handNumber"))
            assertEquals(
                left.getValue("players").jsonArray[0].jsonObject.getValue("cards"),
                back.getValue("players").jsonArray[0].jsonObject.getValue("cards"),
                "the same two cards",
            )
            assertEquals(left.getValue("pot"), back.getValue("pot"))
            assertEquals(0, back.getValue("actorSeat").jsonPrimitive.int, "still the player's turn")
        }

        assertTrue(guest.store.rounds(guest.player.id, 10).isEmpty(), "nothing is settled by dropping and coming back")
    }

    @Test
    fun `the wheel's history is still there after the connection drops`() = testApplication {
        val guest = TestPlayers()
        serve(guest)
        lateinit var left: JsonObject

        sockets().webSocket("/ws/roulette") {
            sayHello(guest.token)
            nextView()
            repeat(3) {
                say("""{"type":"spin","bets":[{"kind":"red","amount":10}]}""")
                left = nextView()
            }
        }

        sockets().webSocket("/ws/roulette") {
            sayHello(guest.token)
            val back = nextView()
            assertEquals(left.getValue("history"), back.getValue("history"))
            assertEquals(3, back.getValue("roundNumber").jsonPrimitive.int)
            assertEquals(left.getValue("stack"), back.getValue("stack"))
        }
    }

    @Test
    fun `opening the table somewhere else takes it over`() = testApplication {
        val guest = TestPlayers()
        serve(guest)
        val client = sockets()

        client.webSocket("/ws/blackjack") {
            sayHello(guest.token)
            nextView()
            val left = dealADecision()

            // A second tab, while this one is still open.
            val second = launch {
                client.webSocket("/ws/blackjack") {
                    sayHello(guest.token)
                    assertEquals(left.getValue("hands"), nextView().getValue("hands"), "the newcomer has the table")
                    say("""{"type":"act","action":"stand"}""")
                    assertEquals("settled", nextView().phase)
                }
            }

            val told = receiveJson()
            assertEquals("error", told.getValue("type").jsonPrimitive.content)
            assertEquals("replaced", told.getValue("code").jsonPrimitive.content)
            assertNull(withTimeout(3_000) { incoming.receiveCatching().getOrNull() }, "and the first connection is closed")
            second.join()
        }
    }

    @Test
    fun `a blackjack round walked away from is stood on and settled`() = testApplication {
        val guest = TestPlayers()
        serve(guest, keepTablesFor = 100.milliseconds)

        sockets().webSocket("/ws/blackjack") {
            sayHello(guest.token)
            nextView()
            dealADecision()
        }

        eventually { guest.store.rounds(guest.player.id, 10).any { it.game == Game.BLACKJACK } }

        // Earlier rounds in this sitting may have settled on the deal; the last is the one left behind.
        val round = guest.store.rounds(guest.player.id, 1).single()
        assertEquals(50, round.staked, "no chip was added to what was already down")
        assertEquals(0, round.detail.getValue("busts").jsonPrimitive.int, "standing cannot bust")
        assertEquals(2_000 + guest.store.rounds(guest.player.id, 10).sumOf { it.net }, guest.players.balance(guest.player))
    }

    @Test
    fun `a poker hand walked away from is folded, and costs what was already in`() = testApplication {
        val guest = TestPlayers()
        serve(guest, keepTablesFor = 100.milliseconds)

        sockets().webSocket("/ws/table") {
            sayHello(guest.token)
            nextView()
        }

        eventually { guest.store.rounds(guest.player.id, 10).isNotEmpty() }

        val hand = guest.store.rounds(guest.player.id, 10).single()
        assertEquals(Game.POKER, hand.game)
        assertEquals(RoundOutcome.LOSS, hand.outcome)
        assertTrue(hand.detail.getValue("folded").jsonPrimitive.boolean)
        assertTrue(hand.net in -20..-10, "a blind and no more: ${hand.net}")
        assertEquals(2_000 + hand.net, guest.players.balance(guest.player))
    }

    @Test
    fun `a table walked away from is cleared, and coming back later starts afresh`() = testApplication {
        val guest = TestPlayers()
        serve(guest, keepTablesFor = 100.milliseconds)

        sockets().webSocket("/ws/roulette") {
            sayHello(guest.token)
            nextView()
            say("""{"type":"spin","bets":[{"kind":"red","amount":10}]}""")
            assertEquals(1, nextView().getValue("roundNumber").jsonPrimitive.int)
        }

        delay(400)

        sockets().webSocket("/ws/roulette") {
            sayHello(guest.token)
            val fresh = nextView()
            assertEquals(0, fresh.getValue("roundNumber").jsonPrimitive.int)
            assertTrue(fresh.getValue("history").jsonArray.isEmpty())
            assertEquals(guest.players.balance(guest.player), fresh.getValue("stack").jsonPrimitive.long, "the chips, as ever, are the player's")
        }
    }

    @Test
    fun `coming back in time stops the table being cleared`() = testApplication {
        val guest = TestPlayers()
        serve(guest, keepTablesFor = 300.milliseconds)

        sockets().webSocket("/ws/blackjack") {
            sayHello(guest.token)
            nextView()
            dealADecision()
        }

        sockets().webSocket("/ws/blackjack") {
            sayHello(guest.token)
            assertEquals("player", nextView().phase)
            // Longer than the table would have waited, had the player stayed away.
            delay(500)
            say("""{"type":"act","action":"stand"}""")
            assertEquals("settled", nextView().phase, "the round was the player's to finish")
        }
    }
}

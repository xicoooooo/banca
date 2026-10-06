package com.banca.ws

import com.banca.games.roulette.Wheel
import com.banca.module
import com.banca.players.Game
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The shared rooms: one wheel, one clock, several players. */
class RouletteRoomTest {

    // Quick enough to test, slow enough that a bet can be placed while betting is open.
    private val quick = RoomTimings(betting = 700.milliseconds, spinning = 60.milliseconds, results = 60.milliseconds)

    private fun ApplicationTestBuilder.serve(vararg guests: TestPlayers, timings: RoomTimings = quick, seed: Int = 5) {
        // Every guest must be known to the same server, so they share the first one's players.
        application {
            module(
                rooms = RoomsConfig(timings = timings, random = { Random(seed) }),
                players = guests.first().players,
                keepTablesFor = 150.milliseconds,
            )
        }
    }

    private fun ApplicationTestBuilder.sockets() = createClient { install(WebSockets) }

    private suspend fun DefaultClientWebSocketSession.say(json: String) = send(Frame.Text(json))

    private val JsonObject.type: String get() = getValue("type").jsonPrimitive.content

    private suspend fun DefaultClientWebSocketSession.nextOf(type: String): JsonObject = withTimeout(5_000) {
        var message = receiveJson()
        while (message.type != type) message = receiveJson()
        message
    }

    /** Reads states until one is in [phase], and returns its view. */
    private suspend fun DefaultClientWebSocketSession.viewIn(phase: String, round: Int? = null): JsonObject = withTimeout(5_000) {
        while (true) {
            val view = nextOf("state").getValue("view").jsonObject
            val right = view.getValue("phase").jsonPrimitive.content == phase
            if (right && (round == null || view.getValue("roundNumber").jsonPrimitive.int == round)) return@withTimeout view
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** A second player known to the same server as [host]. */
    private fun anotherPlayerOf(host: TestPlayers): Pair<java.util.UUID, String> = runBlocking {
        val (player, token) = host.players.createGuest()
        player.id to token
    }

    @Test
    fun `the lobby lists the rooms, empty to begin with`() = testApplication {
        serve(TestPlayers())

        val rooms = Json.parseToJsonElement(client.get("/roulette/rooms").bodyAsText()).jsonArray.map { it.jsonObject }

        assertEquals(listOf("emerald", "gold", "ivory"), rooms.map { it.getValue("id").jsonPrimitive.content })
        assertEquals("Emerald Room", rooms[0].getValue("name").jsonPrimitive.content)
        assertTrue(rooms.all { it.getValue("players").jsonPrimitive.int == 0 })
    }

    @Test
    fun `walking in starts the room, with bets open and the clock running`() = testApplication {
        val guest = TestPlayers()
        serve(guest)

        sockets().webSocket("/ws/roulette/rooms/emerald") {
            sayHello(guest.token)

            val log = nextOf("chat_log")
            assertTrue(log.getValue("phrases").jsonArray.size > 10, "the things that can be said come with the room")

            val view = viewIn("betting")
            assertEquals("emerald", view.getValue("room").jsonPrimitive.content)
            assertEquals(1, view.getValue("roundNumber").jsonPrimitive.int)
            assertTrue(view.getValue("msLeft").jsonPrimitive.long in 1..700)
            assertEquals(2_000, view.getValue("stack").jsonPrimitive.long)
            assertEquals(listOf(true), view.getValue("players").jsonArray.map { it.jsonObject.getValue("you").jsonPrimitive.boolean })
            assertTrue(view.getValue("pocket") is JsonNull)
        }
    }

    @Test
    fun `a round runs by the clock and pays what the bets came to`() {
        val pocket = Wheel.spin(Random(5))
        val guest = TestPlayers()

        testApplication {
            serve(guest)

            sockets().webSocket("/ws/roulette/rooms/emerald") {
                sayHello(guest.token)
                viewIn("betting")
                say("""{"type":"bets","bets":[{"kind":"straight","number":$pocket,"amount":10},{"kind":"straight","number":${(pocket + 1) % 37},"amount":10}]}""")

                val placed = viewIn("betting")
                assertEquals(2, placed.getValue("bets").jsonArray.size)
                assertEquals(1_980, placed.getValue("stack").jsonPrimitive.long, "chips on the felt are not in the stack")

                val spinning = viewIn("spinning")
                assertEquals(pocket, spinning.getValue("pocket").jsonPrimitive.int)
                assertEquals(1_980, spinning.getValue("stack").jsonPrimitive.long, "winnings are not shown before the ball lands")
                assertTrue(spinning.getValue("history").jsonArray.isEmpty(), "nor is the result in the history yet")
                assertTrue(spinning.getValue("players").jsonArray.single().jsonObject.getValue("net") is JsonNull)

                val results = viewIn("results")
                assertEquals(340, results.getValue("result").jsonObject.getValue("net").jsonPrimitive.long)
                assertEquals(2_340, results.getValue("stack").jsonPrimitive.long)
                assertEquals(listOf(pocket), results.getValue("history").jsonArray.map { it.jsonPrimitive.int })
                assertEquals(340, results.getValue("players").jsonArray.single().jsonObject.getValue("net").jsonPrimitive.long)

                val next = viewIn("betting", round = 2)
                assertTrue(next.getValue("bets").jsonArray.isEmpty(), "a new round starts with a clear felt")
                assertTrue(next.getValue("result") is JsonNull)
            }
        }

        val round = runBlocking { guest.store.rounds(guest.player.id, 10) }.single()
        assertEquals(Game.ROULETTE, round.game)
        assertEquals(340, round.net)
        assertEquals(2_340, runBlocking { guest.players.balance(guest.player) })
    }

    @Test
    fun `two players share the wheel, see each other's chips, and are each paid their own`() {
        val pocket = Wheel.spin(Random(5))
        val miss = (pocket + 1) % 37
        val host = TestPlayers()
        val (otherId, otherToken) = anotherPlayerOf(host)

        testApplication {
            serve(host)
            val client = sockets()

            client.webSocket("/ws/roulette/rooms/gold") {
                sayHello(host.token)
                viewIn("betting")

                val other = launch {
                    client.webSocket("/ws/roulette/rooms/gold") {
                        sayHello(otherToken)
                        viewIn("betting")
                        say("""{"type":"bets","bets":[{"kind":"straight","number":$miss,"amount":20}]}""")
                        val results = viewIn("results")
                        assertEquals(-20, results.getValue("result").jsonObject.getValue("net").jsonPrimitive.long)
                        assertEquals(pocket, results.getValue("pocket").jsonPrimitive.int, "the same ball for everyone")
                    }
                }

                say("""{"type":"bets","bets":[{"kind":"straight","number":$pocket,"amount":10}]}""")

                // Before the spin, both players' chips are on the felt for both to see.
                var crowd = viewIn("betting").getValue("crowd").jsonArray
                while (crowd.size < 2) crowd = viewIn("betting").getValue("crowd").jsonArray
                assertEquals(setOf(pocket, miss), crowd.map { it.jsonObject.getValue("number").jsonPrimitive.int }.toSet())

                val results = viewIn("results")
                assertEquals(350, results.getValue("result").jsonObject.getValue("net").jsonPrimitive.long)
                val nets = results.getValue("players").jsonArray.map { it.jsonObject.getValue("net").jsonPrimitive.long }.toSet()
                assertEquals(setOf(350L, -20L), nets, "everyone sees how everyone did")
                other.join()
            }
        }

        assertEquals(2_350, runBlocking { host.players.balance(host.player) })
        assertEquals(-20, runBlocking { host.store.rounds(otherId, 10) }.single().net)
    }

    @Test
    fun `bets are not taken once they are closed, nor ones the table would not take`() = testApplication {
        val guest = TestPlayers()
        serve(guest)

        sockets().webSocket("/ws/roulette/rooms/emerald") {
            sayHello(guest.token)
            viewIn("betting")

            say("""{"type":"bets","bets":[{"kind":"straight","number":17,"amount":500}]}""")
            assertEquals("error", nextOf("error").type)
            say("""{"type":"bets","bets":[{"kind":"red","amount":10}]}""")
            assertEquals(1, viewIn("betting").getValue("bets").jsonArray.size, "the room carries on")

            viewIn("spinning")
            say("""{"type":"bets","bets":[{"kind":"black","amount":10}]}""")
            assertTrue("No more bets" in nextOf("error").getValue("message").jsonPrimitive.content)
        }
    }

    @Test
    fun `a layout can be changed or taken back while bets are open`() = testApplication {
        val guest = TestPlayers()
        serve(guest, timings = RoomTimings(betting = 800.milliseconds, spinning = 50.milliseconds, results = 50.milliseconds))

        sockets().webSocket("/ws/roulette/rooms/emerald") {
            sayHello(guest.token)
            viewIn("betting")

            say("""{"type":"bets","bets":[{"kind":"red","amount":100}]}""")
            assertEquals(1_900, viewIn("betting").getValue("stack").jsonPrimitive.long)

            say("""{"type":"bets","bets":[]}""")
            val cleared = viewIn("betting")
            assertEquals(2_000, cleared.getValue("stack").jsonPrimitive.long)
            assertTrue(cleared.getValue("crowd").jsonArray.isEmpty())

            assertTrue(viewIn("results").getValue("result") is JsonNull, "with nothing down, the spin is only watched")
        }
        assertTrue(runBlocking { guest.store.rounds(guest.player.id, 10) }.isEmpty())
    }

    @Test
    fun `players can type to each other, and what they type is tidied first`() = testApplication {
        val host = TestPlayers()
        serve(host)

        sockets().webSocket("/ws/roulette/rooms/ivory") {
            sayHello(host.token)
            nextOf("chat_log")

            suspend fun typed(text: String): String {
                // The room asks for a moment between lines.
                delay(1_600)
                say("""{"type":"chat","text":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}""")
                return nextOf("chat").getValue("line").jsonObject.getValue("text").jsonPrimitive.content
            }

            assertEquals("Anyone else on black?", typed("  Anyone   else\non black?  "))
            assertEquals("free chips at [link] go now", typed("free chips at http://scam.example/x?y=1 go now"))
            assertEquals("try [link]", typed("try chips-for-free.com/now"))
            assertEquals("well f*** that spin", typed("well FUCK that spin").lowercase())
            assertEquals(140, typed("x".repeat(500)).length)

            delay(1_600)
            say("""{"type":"chat","text":"   "}""")
            assertEquals("error", nextOf("error").type)
            say("""{"type":"chat"}""")
            assertEquals("error", nextOf("error").type)
        }
    }

    @Test
    fun `players can say the room's set phrases to each other`() = testApplication {
        val host = TestPlayers()
        val (_, otherToken) = anotherPlayerOf(host)
        serve(host)
        val client = sockets()

        client.webSocket("/ws/roulette/rooms/ivory") {
            sayHello(host.token)
            nextOf("chat_log")

            say("""{"type":"chat","say":"good_luck"}""")
            val heard = nextOf("chat").getValue("line").jsonObject
            assertEquals("Good luck", heard.getValue("text").jsonPrimitive.content)
            assertEquals(host.player.name, heard.getValue("from").jsonPrimitive.content)

            say("""{"type":"chat","say":"clap"}""")
            assertTrue("moment" in nextOf("error").getValue("message").jsonPrimitive.content, "not too fast")

            say("""{"type":"chat","say":"not_a_phrase"}""")
            assertEquals("error", nextOf("error").type)

            // Someone walking in later is shown what was said.
            client.webSocket("/ws/roulette/rooms/ivory") {
                sayHello(otherToken)
                val lines = nextOf("chat_log").getValue("lines").jsonArray
                assertEquals(listOf("Good luck"), lines.map { it.jsonObject.getValue("text").jsonPrimitive.content })
            }
        }
    }

    @Test
    fun `a bet made is played even if the player drops, and they find the result on coming back`() {
        val pocket = Wheel.spin(Random(5))
        val guest = TestPlayers()

        testApplication {
            serve(guest, timings = RoomTimings(betting = 300.milliseconds, spinning = 40.milliseconds, results = 2_000.milliseconds))

            sockets().webSocket("/ws/roulette/rooms/emerald") {
                sayHello(guest.token)
                viewIn("betting")
                say("""{"type":"bets","bets":[{"kind":"straight","number":$pocket,"amount":10}]}""")
                viewIn("betting")
            }

            withTimeout(5_000) { while (guest.store.rounds(guest.player.id, 1).isEmpty()) delay(20) }

            sockets().webSocket("/ws/roulette/rooms/emerald") {
                sayHello(guest.token)
                // They may be back before the ball has been seen to land.
                val back = viewIn("results")
                assertEquals(350, back.getValue("result").jsonObject.getValue("net").jsonPrimitive.long)
            }
        }
        assertEquals(2_350, runBlocking { guest.players.balance(guest.player) })
    }

    @Test
    fun `a room everyone has left stops, and starts again for the next player`() = testApplication {
        val guest = TestPlayers()
        serve(guest)

        sockets().webSocket("/ws/roulette/rooms/emerald") {
            sayHello(guest.token)
            viewIn("results")
        }

        // Long enough for the seat to be given up and the room to notice it is empty.
        delay(1_200)
        val idle = Json.parseToJsonElement(client.get("/roulette/rooms").bodyAsText()).jsonArray[0].jsonObject
        assertEquals(0, idle.getValue("players").jsonPrimitive.int)
        assertEquals(1, idle.getValue("history").jsonArray.size, "what happened there is still on the board")

        sockets().webSocket("/ws/roulette/rooms/emerald") {
            sayHello(guest.token)
            val view = viewIn("betting")
            assertTrue(view.getValue("msLeft").jsonPrimitive.long > 0, "the clock is running again")
            assertEquals(1, view.getValue("history").jsonArray.size)
        }
    }

    @Test
    fun `Banca reads a layout in a room as it does at a private table`() = testApplication {
        val guest = TestPlayers()
        serve(guest)

        sockets().webSocket("/ws/roulette/rooms/emerald") {
            sayHello(guest.token)
            viewIn("betting")
            say("""{"type":"analyse","bets":[{"kind":"red","amount":50},{"kind":"black","amount":50}]}""")

            val read = nextOf("read").getValue("read").jsonObject
            assertTrue("cancel each other out" in read.getValue("text").jsonPrimitive.content)
        }
    }
}

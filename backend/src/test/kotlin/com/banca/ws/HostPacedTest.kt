package com.banca.ws

import com.banca.module
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Private tables, which go at their host's pace rather than a clock's. */
class HostPacedTest {

    // Clocks short enough that a table left to them would have dealt long before a test stops looking.
    private fun ApplicationTestBuilder.serve(host: TestPlayers) {
        application {
            module(
                players = host.players,
                pokerTables = PokerTablesConfig(
                    timings = PokerTimings(turn = 2_000.milliseconds, turnAway = 100.milliseconds, results = 200.milliseconds, agentDelay = Duration.ZERO),
                    random = { Random(3) },
                ),
                blackjackTables = BlackjackTablesConfig(
                    timings = BlackjackTimings(betting = 200.milliseconds, insurance = 100.milliseconds, turn = 300.milliseconds, turnAway = 50.milliseconds, results = 200.milliseconds),
                    random = { Random(5) },
                ),
                rooms = RoomsConfig(timings = RoomTimings(betting = 200.milliseconds, spinning = 50.milliseconds, results = 100.milliseconds)),
            )
        }
    }

    private fun ApplicationTestBuilder.sockets() = createClient { install(WebSockets) }

    private suspend fun ApplicationTestBuilder.open(path: String, token: String, body: String = "") =
        client.post(path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            if (body.isNotEmpty()) setBody(body)
        }

    private suspend fun ApplicationTestBuilder.opened(path: String, token: String, body: String = ""): String =
        Json.parseToJsonElement(open(path, token, body).bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content

    private suspend fun DefaultClientWebSocketSession.say(json: String) = send(Frame.Text(json))

    private val JsonObject.type: String get() = getValue("type").jsonPrimitive.content
    private val JsonObject.phase: String get() = getValue("phase").jsonPrimitive.content

    private suspend fun DefaultClientWebSocketSession.nextOf(type: String): JsonObject = withTimeout(8_000) {
        var message = receiveJson()
        while (message.type != type) message = receiveJson()
        message
    }

    private suspend fun DefaultClientWebSocketSession.viewWhere(wanted: (JsonObject) -> Boolean): JsonObject = withTimeout(8_000) {
        var view = nextOf("state").getValue("view").jsonObject
        while (!wanted(view)) view = nextOf("state").getValue("view").jsonObject
        view
    }

    /** Watches for a while and says whether the table ever left the phase it should be waiting in. */
    private suspend fun DefaultClientWebSocketSession.staysIn(phase: String, forMs: Long): Boolean =
        withTimeoutOrNull(forMs) { viewWhere { it.phase != phase } } == null

    private fun friendOf(host: TestPlayers): String = runBlocking { host.players.createGuest().second }

    // ------------------------------------------------------------------ opening

    @Test
    fun `a host chooses the seats, the turns and whether Banca plays, within what each game allows`() = testApplication {
        val host = TestPlayers()
        serve(host)

        assertEquals(HttpStatusCode.Created, open("/poker/tables", host.token, """{"seats":4,"banca":false,"turns":"long"}""").status)
        assertEquals(HttpStatusCode.Created, open("/blackjack/tables", host.token, """{"seats":2}""").status)
        assertEquals(HttpStatusCode.Created, open("/roulette/rooms", host.token).status, "and may leave it all to the usual")

        assertEquals(HttpStatusCode.BadRequest, open("/poker/tables", host.token, """{"seats":9}""").status)
        assertEquals(HttpStatusCode.BadRequest, open("/blackjack/tables", host.token, """{"seats":1}""").status)
        assertEquals(HttpStatusCode.BadRequest, open("/blackjack/tables", host.token, """{"banca":false}""").status, "Banca only has a seat at poker")
        assertEquals(HttpStatusCode.BadRequest, open("/roulette/rooms", host.token, """{"turns":"endless"}""").status)
        assertEquals(HttpStatusCode.BadRequest, open("/poker/tables", host.token, "not json").status)
    }

    // -------------------------------------------------------------------- poker

    @Test
    fun `a private poker table deals nothing until its host starts it, and then carries on by itself`() = testApplication {
        val host = TestPlayers()
        val friend = friendOf(host)
        serve(host)
        val id = opened("/poker/tables", host.token)

        sockets().webSocket("/ws/poker/tables/$id") {
            sayHello(host.token)
            val waiting = viewWhere { true }
            assertEquals("waiting", waiting.phase)
            assertFalse(waiting.getValue("started").jsonPrimitive.boolean)
            assertTrue(waiting.getValue("youHost").jsonPrimitive.boolean)
            assertEquals(host.player.name, waiting.getValue("host").jsonPrimitive.content)
            assertTrue(waiting.getValue("table") is JsonNull, "no cards are out")
            assertTrue(staysIn("waiting", 1_300), "however long it is left")

            val seated = CompletableDeferred<Unit>()
            val guest = launch {
                sockets().webSocket("/ws/poker/tables/$id") {
                    sayHello(friend)
                    assertFalse(viewWhere { true }.getValue("youHost").jsonPrimitive.boolean)
                    say("""{"type":"start"}""")
                    assertEquals("error", nextOf("error").type, "only the host can start the game")
                    seated.complete(Unit)
                    // Plays along once the host starts.
                    while (true) {
                        val view = viewWhere { it.getValue("yourTurn").jsonPrimitive.boolean }
                        val canCheck = view.getValue("table").jsonObject.getValue("legal").jsonObject.getValue("canCheck").jsonPrimitive.boolean
                        say("""{"type":"act","action":"${if (canCheck) "check" else "call"}"}""")
                    }
                }
            }
            seated.await()

            say("""{"type":"start"}""")
            val playing = viewWhere { it.phase == "playing" }
            assertTrue(playing.getValue("started").jsonPrimitive.boolean)
            assertEquals(3, playing.getValue("table").jsonObject.getValue("players").jsonArray.size, "the host, the friend and Banca")

            // The first hand over, the next comes without anyone asking.
            val first = playing.getValue("table").jsonObject.getValue("handNumber").jsonPrimitive.int
            viewWhere { view ->
                if (view.getValue("yourTurn").jsonPrimitive.boolean) runBlocking { say("""{"type":"act","action":"fold"}""") }
                view.getValue("table") !is JsonNull && view.getValue("table").jsonObject.getValue("handNumber").jsonPrimitive.int > first
            }
            guest.cancel()
        }
    }

    @Test
    fun `without Banca a table needs two players, seats only as many as its host chose, and has no seat for Banca`() = testApplication {
        val host = TestPlayers()
        val friend = friendOf(host)
        val third = friendOf(host)
        serve(host)
        val id = opened("/poker/tables", host.token, """{"seats":2,"banca":false}""")

        sockets().webSocket("/ws/poker/tables/$id") {
            sayHello(host.token)
            val alone = viewWhere { true }
            assertEquals(2, alone.getValue("seatsInAll").jsonPrimitive.int)
            assertEquals(listOf(host.player.name), alone.getValue("seats").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })

            say("""{"type":"start"}""")
            assertEquals("error", nextOf("error").type, "there is nobody to play against yet")

            val guest = launch {
                sockets().webSocket("/ws/poker/tables/$id") {
                    sayHello(friend)
                    while (true) receiveJson()
                }
            }
            viewWhere { it.getValue("seats").jsonArray.size == 2 }

            sockets().webSocket("/ws/poker/tables/$id") {
                sayHello(third)
                val told = nextOf("error")
                assertEquals("full", told.getValue("code").jsonPrimitive.content)
            }

            say("""{"type":"start"}""")
            val playing = viewWhere { it.phase == "playing" }
            val seats = playing.getValue("table").jsonObject.getValue("players").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertEquals(2, seats.size)
            assertFalse("Banca" in seats)
            guest.cancel()
        }
    }

    // ---------------------------------------------------------------- blackjack

    @Test
    fun `a private blackjack table waits for bets with no clock, and deals when the host asks`() = testApplication {
        val host = TestPlayers()
        val friend = friendOf(host)
        serve(host)
        val id = opened("/blackjack/tables", host.token)

        sockets().webSocket("/ws/blackjack/tables/$id") {
            sayHello(host.token)
            val open = viewWhere { true }
            assertEquals("betting", open.phase)
            assertEquals(0, open.getValue("msLeft").jsonPrimitive.int, "no clock is running")
            assertTrue(open.getValue("youHost").jsonPrimitive.boolean)
            assertTrue(staysIn("betting", 900), "though a listed table would have closed its bets several times over")

            say("""{"type":"start"}""")
            assertEquals("error", nextOf("error").type, "there is nothing to deal to yet")

            val seated = CompletableDeferred<Unit>()
            val guest = launch {
                sockets().webSocket("/ws/blackjack/tables/$id") {
                    sayHello(friend)
                    viewWhere { true }
                    say("""{"type":"start"}""")
                    assertEquals("error", nextOf("error").type, "only the host can deal")
                    seated.complete(Unit)
                    while (true) receiveJson()
                }
            }
            seated.await()

            say("""{"type":"bet","amount":50}""")
            viewWhere { view -> view.getValue("seats").jsonArray.any { it.jsonObject.getValue("bet").jsonPrimitive.int == 50 } }
            assertTrue(staysIn("betting", 700), "one player has yet to bet, so the table goes on waiting")

            say("""{"type":"start"}""")
            val dealt = viewWhere { it.phase != "betting" }
            val hands = dealt.getValue("seats").jsonArray.map { it.jsonObject.getValue("hands").jsonArray.size }
            assertEquals(listOf(1, 0), hands, "the host is dealt in, and the friend who had not bet sits it out")
            guest.cancel()
        }
    }

    @Test
    fun `once everyone at a private blackjack table has bet, the cards follow without being asked for`() = testApplication {
        val host = TestPlayers()
        serve(host)
        val id = opened("/blackjack/tables", host.token)

        sockets().webSocket("/ws/blackjack/tables/$id") {
            sayHello(host.token)
            viewWhere { it.phase == "betting" }
            say("""{"type":"bet","amount":50}""")

            val counting = viewWhere { it.getValue("msLeft").jsonPrimitive.int > 0 }
            assertEquals("betting", counting.phase, "a moment is left in which a bet can still be changed")
            viewWhere { it.phase != "betting" }
        }
    }

    // ----------------------------------------------------------------- roulette

    @Test
    fun `a private roulette room spins when its host says, and the host passes on when they leave`() = testApplication {
        val host = TestPlayers()
        val friend = friendOf(host)
        serve(host)
        val id = opened("/roulette/rooms", host.token, """{"seats":2}""")

        sockets().webSocket("/ws/roulette/rooms/$id") {
            sayHello(friend)
            // The friend is first in, so until the host arrives the wheel is theirs to spin.
            assertTrue(viewWhere { true }.getValue("youHost").jsonPrimitive.boolean)

            val hostSeated = CompletableDeferred<Unit>()
            val hostLeft = CompletableDeferred<Unit>()
            val opener = launch {
                sockets().webSocket("/ws/roulette/rooms/$id") {
                    sayHello(host.token)
                    assertTrue(viewWhere { true }.getValue("youHost").jsonPrimitive.boolean, "the player who opened the room takes it over")
                    hostSeated.complete(Unit)
                    hostLeft.await()
                }
            }
            hostSeated.await()
            val told = viewWhere { !it.getValue("youHost").jsonPrimitive.boolean }
            assertEquals(host.player.name, told.getValue("host").jsonPrimitive.content)

            say("""{"type":"bets","bets":[{"kind":"red","amount":20}]}""")
            assertTrue(staysIn("betting", 800), "chips are down, and still nothing spins on a clock")
            say("""{"type":"start"}""")
            assertEquals("error", nextOf("error").type, "it is not the friend's wheel while the host is here")

            hostLeft.complete(Unit)
            opener.join()
            viewWhere { it.getValue("youHost").jsonPrimitive.boolean }

            say("""{"type":"start"}""")
            val spun = viewWhere { it.phase != "betting" }
            assertTrue(spun.getValue("pocket") !is JsonNull)
            assertNull(withTimeoutOrNull(600) { viewWhere { it.phase == "spinning" && it.getValue("roundNumber").jsonPrimitive.int > spun.getValue("roundNumber").jsonPrimitive.int } }, "and the next spin waits to be asked for too")
        }
    }

    @Test
    fun `the tables everyone can see still run on their clocks, with nobody as host`() = testApplication {
        val host = TestPlayers()
        serve(host)

        sockets().webSocket("/ws/roulette/rooms/emerald") {
            sayHello(host.token)
            val view = viewWhere { true }
            assertTrue(view.getValue("host") is JsonNull)
            assertFalse(view.getValue("youHost").jsonPrimitive.boolean)

            say("""{"type":"start"}""")
            assertEquals("error", nextOf("error").type)
            say("""{"type":"bets","bets":[{"kind":"red","amount":20}]}""")
            viewWhere { it.phase == "spinning" }
        }
    }
}

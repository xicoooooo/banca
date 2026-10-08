package com.banca.ws

import com.banca.Allowance
import com.banca.Limit
import com.banca.module
import com.banca.players.FinishedRound
import com.banca.players.Funding
import com.banca.players.Game
import com.banca.players.PlayerSession
import com.banca.players.PracticePurse
import com.banca.players.RoundOutcome
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/** What a private table has beyond its pace: a code to find it by, chips of its own if wanted, and an end. */
class PrivateTableExtrasTest {

    private fun ApplicationTestBuilder.serve(host: TestPlayers, lookups: Allowance = Allowance(Limit(50, 1.hours))) {
        application {
            module(
                players = host.players,
                pokerTables = PokerTablesConfig(
                    timings = PokerTimings(turn = 2_000.milliseconds, turnAway = 100.milliseconds, results = 200.milliseconds, agentDelay = Duration.ZERO),
                    random = { Random(3) },
                ),
                blackjackTables = BlackjackTablesConfig(
                    timings = BlackjackTimings(betting = 200.milliseconds, insurance = 100.milliseconds, turn = 1_500.milliseconds, turnAway = 50.milliseconds, results = 200.milliseconds),
                    random = { Random(5) },
                ),
                rooms = RoomsConfig(timings = RoomTimings(betting = 200.milliseconds, spinning = 50.milliseconds, results = 100.milliseconds)),
                lookups = lookups,
            )
        }
    }

    private fun ApplicationTestBuilder.sockets() = createClient { install(WebSockets) }

    private suspend fun ApplicationTestBuilder.opened(path: String, token: String, body: String = ""): String {
        val response = client.post(path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            if (body.isNotEmpty()) setBody(body)
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
    }

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

    /** Reads on until the table says it has closed, and returns how it said so. */
    private suspend fun DefaultClientWebSocketSession.untilClosed(): JsonObject = withTimeout(8_000) {
        var message = receiveJson()
        while (!(message.type == "error" && message["code"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content == "no_table")) message = receiveJson()
        message
    }

    private fun friendOf(host: TestPlayers): String = runBlocking { host.players.createGuest().second }

    // ------------------------------------------------------------------ by code

    @Test
    fun `a table is found by its code alone, however it is typed, and the code says which game it is`() = testApplication {
        val host = TestPlayers()
        serve(host)
        val poker = opened("/poker/tables", host.token)
        val roulette = opened("/roulette/rooms", host.token)

        val found = Json.parseToJsonElement(client.get("/tables/$poker").bodyAsText()).jsonObject
        assertEquals("poker", found.getValue("game").jsonPrimitive.content)
        assertEquals(poker, found.getValue("id").jsonPrimitive.content)
        assertEquals("${host.player.name}'s table", found.getValue("name").jsonPrimitive.content)

        // Read out over the phone and typed in capitals, with a gap in the middle.
        val spoken = roulette.uppercase().chunked(3).joinToString("%20")
        val again = Json.parseToJsonElement(client.get("/tables/$spoken").bodyAsText()).jsonObject
        assertEquals("roulette", again.getValue("game").jsonPrimitive.content)
        assertEquals(roulette, again.getValue("id").jsonPrimitive.content)

        assertEquals(HttpStatusCode.NotFound, client.get("/tables/zzzzzz").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/tables/emerald").status, "the tables everyone can see have no code")
    }

    @Test
    fun `codes cannot be guessed at by trying them one after another`() = testApplication {
        val host = TestPlayers()
        serve(host, lookups = Allowance(Limit(3, 1.hours)))

        val answers = List(5) { client.get("/tables/aaaaa$it").status }

        assertEquals(List(3) { HttpStatusCode.NotFound } + List(2) { HttpStatusCode.TooManyRequests }, answers)
    }

    // ----------------------------------------------------------- practice chips

    @Test
    fun `at a practice table everyone has the table's chips, and their own are never touched`() = testApplication {
        val host = TestPlayers()
        serve(host)
        val before = host.players.balance(host.player)
        val id = opened("/roulette/rooms", host.token, """{"chips":"practice"}""")

        sockets().webSocket("/ws/roulette/rooms/$id") {
            sayHello(host.token)
            val view = viewWhere { true }
            assertTrue(view.getValue("practice").jsonPrimitive.boolean)
            assertEquals(2_000, view.getValue("stack").jsonPrimitive.long, "a fresh stack, whatever they have of their own")

            // Every number at once: one of them wins and the rest lose, so the stack is sure to change.
            say("""{"type":"bets","bets":[{"kind":"red","amount":100},{"kind":"straight","number":0,"amount":50}]}""")
            viewWhere { it.getValue("bets").jsonArray.isNotEmpty() }
            say("""{"type":"start"}""")
            val settled = viewWhere { it.phase == "results" }
            assertNotEquals(2_000, settled.getValue("stack").jsonPrimitive.long, "the practice chips moved")
        }

        assertEquals(before, host.players.balance(host.player), "and the player's own did not")
        assertTrue(host.store.rounds(host.player.id, 10).isEmpty(), "nor is anything written to their record")
    }

    @Test
    fun `real chips are the usual, and chips are one or the other`() = testApplication {
        val host = TestPlayers()
        serve(host)
        val practice = opened("/blackjack/tables", host.token, """{"chips":"practice"}""")
        val real = opened("/blackjack/tables", host.token)

        sockets().webSocket("/ws/blackjack/tables/$real") {
            sayHello(host.token)
            assertEquals(false, viewWhere { true }.getValue("practice").jsonPrimitive.boolean)
        }
        assertEquals(HttpStatusCode.BadRequest, client.post("/poker/tables") {
            header(HttpHeaders.Authorization, "Bearer ${host.token}")
            setBody("""{"chips":"gold"}""")
        }.status)

    }

    @Test
    fun `a practice stack that runs short is made up again, and one player's is no part of another's`() = runBlocking {
        val host = TestPlayers()
        val (other, _) = host.players.createGuest()
        val purse = PracticePurse(stack = 1_000)
        val mine = purse.sessionFor(PlayerSession(host.player, host.players))
        val theirs = purse.sessionFor(PlayerSession(other, host.players))
        fun lost(chips: Long) = FinishedRound(Game.BLACKJACK, "invite:abc234", staked = chips, net = -chips, outcome = RoundOutcome.LOSS, detail = buildJsonObject { })

        assertEquals(1_000, mine.balance())
        assertEquals(400, mine.settle(lost(600)))
        assertEquals(Funding.Ready(400), mine.fund(10))
        assertEquals(5, mine.settle(lost(395)))

        assertEquals(Funding.Staked(balance = 1_000, amount = 995), mine.fund(10), "short of the smallest bet, so a fresh stack")
        assertEquals(1_000, mine.balance())
        assertEquals(1_000, theirs.balance(), "untouched by any of it")
        assertEquals(2_000, host.players.balance(host.player), "as are the player's own chips")
    }

    // ------------------------------------------------------------------- ending

    @Test
    fun `the host can close a table that is not in play, and everyone at it is told at once`() = testApplication {
        val host = TestPlayers()
        val friend = friendOf(host)
        serve(host)

        for ((open, socket) in listOf("/poker/tables" to "/ws/poker/tables", "/blackjack/tables" to "/ws/blackjack/tables", "/roulette/rooms" to "/ws/roulette/rooms")) {
            val id = opened(open, host.token)

            sockets().webSocket("$socket/$id") {
                sayHello(host.token)
                viewWhere { true }

                val seated = CompletableDeferred<Unit>()
                val told = CompletableDeferred<JsonObject>()
                val guest = launch {
                    sockets().webSocket("$socket/$id") {
                        sayHello(friend)
                        viewWhere { true }
                        say("""{"type":"end"}""")
                        assertEquals("error", nextOf("error").type, "only the host can close it")
                        seated.complete(Unit)
                        told.complete(untilClosed())
                    }
                }
                seated.await()

                say("""{"type":"end"}""")
                untilClosed()
                assertEquals("no_table", withTimeout(5_000) { told.await() }.getValue("code").jsonPrimitive.content)
                guest.join()
            }

            assertEquals(HttpStatusCode.NotFound, client.get("/tables/$id").status, "its code leads nowhere from then on")
            sockets().webSocket("$socket/$id") {
                sayHello(friend)
                assertEquals("no_table", receiveJson().getValue("code").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `a table closed in the middle of a round sees the round out first`() = testApplication {
        val host = TestPlayers()
        serve(host)
        val id = opened("/blackjack/tables", host.token)

        sockets().webSocket("/ws/blackjack/tables/$id") {
            sayHello(host.token)
            viewWhere { it.phase == "betting" }

            // Deal until there is a hand to play, so the round is truly under way when the table is closed.
            var view: JsonObject
            while (true) {
                say("""{"type":"bet","amount":50}""")
                view = viewWhere { it.phase != "betting" }
                if (view.phase == "insurance") say("""{"type":"act","action":"decline_insurance"}""")
                view = viewWhere { it.phase == "results" || it.getValue("yourTurn").jsonPrimitive.boolean }
                if (view.phase != "results") break
                viewWhere { it.phase == "betting" }
            }

            say("""{"type":"end"}""")
            say("""{"type":"act","action":"stand"}""")
            val settled = viewWhere { it.phase == "results" }
            assertTrue(settled.getValue("you").jsonObject.getValue("result") !is JsonNull, "the hand was played out and settled")
            untilClosed()
        }

        assertTrue(host.store.rounds(host.player.id, 10).isNotEmpty(), "and written down like any other")
    }

    @Test
    fun `a table everyone can see cannot be closed`() = testApplication {
        val host = TestPlayers()
        serve(host)

        sockets().webSocket("/ws/poker/tables/emerald") {
            sayHello(host.token)
            viewWhere { true }
            say("""{"type":"end"}""")
            val refused = nextOf("error")
            assertTrue(refused["code"].let { it == null || it is JsonNull }, "refused, and the table is still there")
        }
    }
}

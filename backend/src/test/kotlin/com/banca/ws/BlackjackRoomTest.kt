package com.banca.ws

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
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The shared blackjack tables: one dealer, several players, a turn each. */
class BlackjackRoomTest {

    // Quick enough to test, slow enough for a bet or a play to be made in time.
    private val quick = BlackjackTimings(
        betting = 600.milliseconds,
        insurance = 400.milliseconds,
        turn = 2_000.milliseconds,
        turnAway = 100.milliseconds,
        results = 300.milliseconds,
    )

    private fun ApplicationTestBuilder.serve(host: TestPlayers, timings: BlackjackTimings = quick, seats: Int = 5, seed: Int = 11) {
        application {
            module(
                blackjackTables = BlackjackTablesConfig(seats = seats, timings = timings, random = { Random(seed) }),
                players = host.players,
                keepTablesFor = 150.milliseconds,
            )
        }
    }

    private fun ApplicationTestBuilder.sockets() = createClient { install(WebSockets) }

    private suspend fun DefaultClientWebSocketSession.say(json: String) = send(Frame.Text(json))

    private val JsonObject.type: String get() = getValue("type").jsonPrimitive.content
    private val JsonObject.phase: String get() = getValue("phase").jsonPrimitive.content
    private val JsonObject.you: JsonObject get() = getValue("you").jsonObject

    private suspend fun DefaultClientWebSocketSession.nextOf(type: String): JsonObject = withTimeout(8_000) {
        var message = receiveJson()
        while (message.type != type) message = receiveJson()
        message
    }

    /** Reads states until one satisfies [wanted], and returns its view. */
    private suspend fun DefaultClientWebSocketSession.viewWhere(wanted: (JsonObject) -> Boolean): JsonObject = withTimeout(8_000) {
        var view = nextOf("state").getValue("view").jsonObject
        while (!wanted(view)) view = nextOf("state").getValue("view").jsonObject
        view
    }

    private suspend fun DefaultClientWebSocketSession.viewIn(phase: String) = viewWhere { it.phase == phase }

    /** Answers insurance if it is offered, then stands on every hand when the turn comes, and returns the results view. */
    private suspend fun DefaultClientWebSocketSession.playSafely(): JsonObject = viewWhere { view ->
        when {
            view.phase == "insurance" && view.you.getValue("legal").jsonObject.getValue("insurance").jsonPrimitive.boolean ->
                runBlocking { say("""{"type":"act","action":"decline_insurance"}""") }
            view.getValue("yourTurn").jsonPrimitive.boolean -> runBlocking { say("""{"type":"act","action":"stand"}""") }
        }
        view.phase == "results"
    }

    private fun anotherPlayerOf(host: TestPlayers): Pair<UUID, String> = runBlocking {
        val (player, token) = host.players.createGuest()
        player.id to token
    }

    @Test
    fun `the lobby lists the tables and how many seats each has`() = testApplication {
        serve(TestPlayers())

        val tables = Json.parseToJsonElement(client.get("/blackjack/tables").bodyAsText()).jsonArray.map { it.jsonObject }

        assertEquals(listOf("emerald", "gold", "ivory"), tables.map { it.getValue("id").jsonPrimitive.content })
        assertEquals("Emerald Table", tables[0].getValue("name").jsonPrimitive.content)
        assertTrue(tables.all { it.getValue("players").jsonPrimitive.int == 0 && it.getValue("seats").jsonPrimitive.int == 5 })
    }

    @Test
    fun `sitting down opens the betting, and nothing is dealt until someone bets`() = testApplication {
        val guest = TestPlayers()
        serve(guest)

        sockets().webSocket("/ws/blackjack/tables/emerald") {
            sayHello(guest.token)
            nextOf("chat_log")

            val view = viewIn("betting")
            assertEquals(0, view.getValue("roundNumber").jsonPrimitive.int)
            assertEquals("betting", view.you.phase)
            assertTrue(view.you.getValue("legal").jsonObject.getValue("bet").jsonPrimitive.boolean)
            assertEquals(2_000, view.you.getValue("stack").jsonPrimitive.long)
            assertEquals(1, view.getValue("seats").jsonArray.size)

            // A whole betting window passes with no bet, and another opens. No round.
            delay(700)
            assertEquals(0, viewIn("betting").getValue("roundNumber").jsonPrimitive.int)
        }
    }

    @Test
    fun `a round is dealt to whoever bet, played in turn, and settled against the dealer`() {
        val guest = TestPlayers()

        testApplication {
            serve(guest)

            sockets().webSocket("/ws/blackjack/tables/emerald") {
                sayHello(guest.token)
                viewIn("betting")
                say("""{"type":"bet","amount":50}""")
                assertEquals(1_950, viewIn("betting").you.getValue("stack").jsonPrimitive.long, "the stake is off the stack")

                val dealt = viewWhere { it.phase != "betting" }
                assertEquals(1, dealt.getValue("roundNumber").jsonPrimitive.int)
                assertEquals(2, dealt.you.getValue("hands").jsonArray.single().jsonObject.getValue("cards").jsonArray.size)
                val dealerCards = dealt.you.getValue("dealer").jsonObject.getValue("cards").jsonArray
                assertTrue(dealerCards[1] is JsonNull, "the hole card is face down")

                val results = if (dealt.phase == "results") dealt else playSafely()
                val result = results.you.getValue("result").jsonObject
                assertTrue(results.you.getValue("dealer").jsonObject.getValue("cards").jsonArray.none { it is JsonNull }, "and turned over at the end")
                assertEquals(2_000 + result.getValue("net").jsonPrimitive.long, results.you.getValue("stack").jsonPrimitive.long)
                assertEquals(result.getValue("net"), results.getValue("seats").jsonArray.single().jsonObject.getValue("net"))

                val next = viewIn("betting")
                assertTrue(next.you.getValue("hands").jsonArray.isEmpty(), "the felt is cleared for the next round")
                assertEquals(50, next.you.getValue("lastBet").jsonPrimitive.long)
            }
        }

        val round = runBlocking { guest.store.rounds(guest.player.id, 10) }.single()
        assertEquals(Game.BLACKJACK, round.game)
        assertEquals(2_000 + round.net, runBlocking { guest.players.balance(guest.player) })
    }

    @Test
    fun `two players share the dealer and take their turns in the order they sat down`() {
        val host = TestPlayers()
        val (otherId, otherToken) = anotherPlayerOf(host)

        testApplication {
            serve(host)
            val client = sockets()

            client.webSocket("/ws/blackjack/tables/gold") {
                sayHello(host.token)
                viewIn("betting")

                val other = launch {
                    client.webSocket("/ws/blackjack/tables/gold") {
                        sayHello(otherToken)
                        viewIn("betting")
                        say("""{"type":"bet","amount":20}""")

                        // Out of turn, nothing can be played.
                        val waiting = viewWhere { it.phase == "playing" || it.phase == "results" }
                        if (waiting.phase == "playing" && !waiting.getValue("yourTurn").jsonPrimitive.boolean) {
                            say("""{"type":"act","action":"stand"}""")
                            assertTrue("not your turn" in nextOf("error").getValue("message").jsonPrimitive.content)
                            assertTrue(waiting.you.getValue("legal").jsonObject.values.none { it.jsonPrimitive.boolean }, "and no play is offered")
                        }
                        playSafely()
                    }
                }

                say("""{"type":"bet","amount":50}""")
                // Both bets are on the table before the cards come out.
                viewWhere { it.phase == "betting" && it.getValue("seats").jsonArray.count { seat -> seat.jsonObject.getValue("bet").jsonPrimitive.long > 0 } == 2 }

                val results = playSafely()
                val seats = results.getValue("seats").jsonArray.map { it.jsonObject }
                assertEquals(2, seats.size)
                assertEquals(listOf(true, false), seats.map { it.getValue("you").jsonPrimitive.boolean }, "seats are in the order players sat down")
                assertTrue(seats.all { it.getValue("hands").jsonArray.isNotEmpty() && it.getValue("net") !is JsonNull }, "everyone's cards and results are shown")
                other.join()
            }
        }

        val mine = runBlocking { host.store.rounds(host.player.id, 10) }.single()
        val theirs = runBlocking { host.store.rounds(otherId, 10) }.single()
        assertEquals(mine.detail.getValue("dealerTotal"), theirs.detail.getValue("dealerTotal"), "both were settled against the same dealer hand")
        assertEquals(50, mine.staked)
        assertEquals(20, theirs.staked)
    }

    @Test
    fun `the turn is only ever a player's while they have something to decide`() = testApplication {
        val guest = TestPlayers()
        serve(guest)

        sockets().webSocket("/ws/blackjack/tables/emerald") {
            sayHello(guest.token)

            // Every state of several rounds: whenever it says it is the player's turn, there is a play to make.
            repeat(4) {
                viewIn("betting")
                say("""{"type":"bet","amount":10}""")
                viewWhere { view ->
                    if (view.getValue("yourTurn").jsonPrimitive.boolean) {
                        assertTrue(view.you.getValue("legal").jsonObject.getValue("stand").jsonPrimitive.boolean, "a turn with nothing to do: $view")
                        assertTrue(view.you.getValue("activeHand") !is JsonNull)
                        assertTrue(view.getValue("seats").jsonArray.single().jsonObject.getValue("acting").jsonPrimitive.boolean)
                        runBlocking { say("""{"type":"act","action":"stand"}""") }
                    }
                    view.phase == "results"
                }
            }
        }
    }

    @Test
    fun `a player who takes too long has their hand stood for them`() {
        val guest = TestPlayers()

        testApplication {
            serve(guest, timings = BlackjackTimings(betting = 500.milliseconds, insurance = 150.milliseconds, turn = 250.milliseconds, turnAway = 80.milliseconds, results = 300.milliseconds))

            sockets().webSocket("/ws/blackjack/tables/emerald") {
                sayHello(guest.token)
                viewIn("betting")
                say("""{"type":"bet","amount":50}""")

                // Does nothing at all, and the round still ends.
                val results = viewIn("results")
                assertEquals(50, results.you.getValue("hands").jsonArray.sumOf { it.jsonObject.getValue("bet").jsonPrimitive.long }, "nothing was added to the stake")
            }
        }
        assertEquals(1, runBlocking { guest.store.rounds(guest.player.id, 10) }.size)
    }

    @Test
    fun `a player who drops is not waited for, and their hand is settled all the same`() {
        val guest = TestPlayers()

        testApplication {
            serve(guest)

            sockets().webSocket("/ws/blackjack/tables/emerald") {
                sayHello(guest.token)
                viewIn("betting")
                say("""{"type":"bet","amount":50}""")
                viewWhere { it.phase != "betting" }
            }

            // Well inside the two seconds a player who was still there would have been given.
            withTimeout(1_500) { while (guest.store.rounds(guest.player.id, 1).isEmpty()) delay(20) }
        }
        val round = runBlocking { guest.store.rounds(guest.player.id, 1) }.single()
        assertEquals(2_000 + round.net, runBlocking { guest.players.balance(guest.player) })
    }

    @Test
    fun `a full table takes nobody else`() = testApplication {
        val host = TestPlayers()
        val (_, otherToken) = anotherPlayerOf(host)
        serve(host, seats = 1)
        val client = sockets()

        client.webSocket("/ws/blackjack/tables/emerald") {
            sayHello(host.token)
            viewIn("betting")

            client.webSocket("/ws/blackjack/tables/emerald") {
                sayHello(otherToken)
                val refused = nextOf("error")
                assertEquals("full", refused.getValue("code").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `a bet outside the table's limits, or after the cards are out, is refused`() = testApplication {
        val guest = TestPlayers()
        serve(guest)

        sockets().webSocket("/ws/blackjack/tables/emerald") {
            sayHello(guest.token)
            viewIn("betting")

            say("""{"type":"bet","amount":5}""")
            assertEquals("error", nextOf("error").type)
            say("""{"type":"bet","amount":5000}""")
            assertEquals("error", nextOf("error").type)

            say("""{"type":"bet","amount":50}""")
            viewWhere { it.phase != "betting" }
            say("""{"type":"bet","amount":100}""")
            assertTrue("started" in nextOf("error").getValue("message").jsonPrimitive.content)
        }
    }

    @Test
    fun `a bet can be taken back while betting is open`() = testApplication {
        val guest = TestPlayers()
        serve(guest, timings = BlackjackTimings(betting = 900.milliseconds, insurance = 100.milliseconds, turn = 500.milliseconds, turnAway = 50.milliseconds, results = 100.milliseconds))

        sockets().webSocket("/ws/blackjack/tables/emerald") {
            sayHello(guest.token)
            viewIn("betting")
            say("""{"type":"bet","amount":100}""")
            assertEquals(1_900, viewWhere { it.you.getValue("stack").jsonPrimitive.long != 2_000L }.you.getValue("stack").jsonPrimitive.long)
            say("""{"type":"bet","amount":0}""")
            assertEquals(2_000, viewWhere { it.you.getValue("stack").jsonPrimitive.long == 2_000L }.you.getValue("stack").jsonPrimitive.long)

            delay(1_000)
            assertEquals(0, viewIn("betting").getValue("roundNumber").jsonPrimitive.int, "so nothing was dealt")
        }
    }

    @Test
    fun `the coach advises on a player's own turn, as at a table alone`() = testApplication {
        val guest = TestPlayers()
        serve(guest)

        sockets().webSocket("/ws/blackjack/tables/emerald") {
            sayHello(guest.token)
            viewIn("betting")
            say("""{"type":"advise"}""")
            assertEquals("error", nextOf("error").type, "there is nothing to advise on between rounds")

            // Bets until a round leaves a decision to make.
            while (true) {
                viewIn("betting")
                say("""{"type":"bet","amount":10}""")
                val view = viewWhere { it.phase == "results" || it.getValue("yourTurn").jsonPrimitive.boolean }
                if (view.phase == "results") continue

                say("""{"type":"advise"}""")
                val advice = nextOf("advice").getValue("advice").jsonObject
                val legal = view.you.getValue("legal").jsonObject.filterValues { it.jsonPrimitive.boolean }.keys
                assertTrue(advice.getValue("action").jsonPrimitive.content in legal)
                break
            }
        }
    }

    @Test
    fun `players at a table can talk to each other`() = testApplication {
        val guest = TestPlayers()
        serve(guest)

        sockets().webSocket("/ws/blackjack/tables/ivory") {
            sayHello(guest.token)
            nextOf("chat_log")
            say("""{"type":"chat","text":"Dealer always has a ten under there"}""")
            assertEquals("Dealer always has a ten under there", nextOf("chat").getValue("line").jsonObject.getValue("text").jsonPrimitive.content)
        }
    }
}

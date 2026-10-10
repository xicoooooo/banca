package com.banca.ws

import com.banca.games.poker.Action
import com.banca.module
import com.banca.players.Game
import com.banca.players.RoundOutcome
import com.banca.sessions.SeatDriver
import com.banca.sessions.TableView
import com.banca.sessions.TraceEvent
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
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The shared poker tables: several players and Banca, a hand after a hand. */
class PokerRoomTest {

    // Banca here only checks and calls, at once, so a test decides how each hand goes.
    private val quick = PokerTimings(turn = 2_000.milliseconds, turnAway = 100.milliseconds, results = 300.milliseconds, agentDelay = Duration.ZERO)

    private fun ApplicationTestBuilder.serve(host: TestPlayers, timings: PokerTimings = quick, seats: Int = 6) {
        application {
            module(
                pokerTables = PokerTablesConfig(seats = seats, timings = timings, random = { Random(3) }),
                players = host.players,
                keepTablesFor = 150.milliseconds,
            )
        }
    }

    private fun ApplicationTestBuilder.sockets() = createClient { install(WebSockets) }

    private suspend fun DefaultClientWebSocketSession.say(json: String) = send(Frame.Text(json))

    private val JsonObject.type: String get() = getValue("type").jsonPrimitive.content
    private val JsonObject.phase: String get() = getValue("phase").jsonPrimitive.content
    private val JsonObject.table: JsonObject get() = getValue("table").jsonObject
    private val JsonObject.hand: Int get() = table.getValue("handNumber").jsonPrimitive.int
    private val JsonObject.myTurn: Boolean get() = getValue("yourTurn").jsonPrimitive.boolean

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

    /** Checks or calls whenever the turn comes, until the hand numbered [hand] is over, and returns its last view. */
    private suspend fun DefaultClientWebSocketSession.playAlong(hand: Int): JsonObject = viewWhere { view ->
        if (view.getValue("table") is JsonNull || view.hand != hand) return@viewWhere false
        if (view.myTurn) {
            val canCheck = view.table.getValue("legal").jsonObject.getValue("canCheck").jsonPrimitive.boolean
            runBlocking { say("""{"type":"act","action":"${if (canCheck) "check" else "call"}"}""") }
        }
        view.phase == "results"
    }

    private fun anotherPlayerOf(host: TestPlayers): Pair<UUID, String> = runBlocking {
        val (player, token) = host.players.createGuest()
        player.id to token
    }

    private fun cardsOf(view: JsonObject, seat: Int) =
        view.table.getValue("players").jsonArray.map { it.jsonObject }.first { it.getValue("seat").jsonPrimitive.int == seat }.getValue("cards")

    @Test
    fun `the lobby lists the tables and the seats left for players`() = testApplication {
        serve(TestPlayers())

        val tables = Json.parseToJsonElement(client.get("/poker/tables").bodyAsText()).jsonArray.map { it.jsonObject }

        assertEquals(listOf("emerald", "gold", "ivory"), tables.map { it.getValue("id").jsonPrimitive.content })
        assertTrue(tables.all { it.getValue("players").jsonPrimitive.int == 0 })
        assertTrue(tables.all { it.getValue("seats").jsonPrimitive.int == 5 }, "six seats, and one of them is always Banca's")
    }

    @Test
    fun `sitting down alone is a hand against Banca, and the next follows without asking`() {
        val guest = TestPlayers()

        testApplication {
            serve(guest)

            sockets().webSocket("/ws/poker/tables/emerald") {
                sayHello(guest.token)
                nextOf("chat_log")

                val first = viewWhere { it.getValue("table") !is JsonNull }
                assertEquals(1, first.hand)
                assertTrue(first.getValue("dealtIn").jsonPrimitive.boolean)
                val mySeat = first.table.getValue("yourSeat").jsonPrimitive.int
                assertEquals(2, first.table.getValue("players").jsonArray.size)
                assertEquals(2, cardsOf(first, mySeat).jsonArray.size, "the player sees their own cards")
                assertTrue(cardsOf(first, 0) is JsonNull, "and never Banca's")
                assertEquals(listOf("Banca", guest.player.name), first.getValue("seats").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })

                val over = playAlong(hand = 1)
                assertTrue(over.table.getValue("result") !is JsonNull)

                val next = viewWhere { it.getValue("table") !is JsonNull && it.hand == 2 }
                assertEquals("playing", next.phase, "nobody had to ask for it")
            }
        }

        val hand = runBlocking { guest.store.rounds(guest.player.id, 10) }.last()
        assertEquals(Game.POKER, hand.game)
        assertEquals(2, hand.detail.getValue("players").jsonPrimitive.int)
    }

    @Test
    fun `two players and Banca make a three-handed game, each seeing only their own cards`() {
        val host = TestPlayers()
        val (otherId, otherToken) = anotherPlayerOf(host)

        testApplication {
            serve(host)
            val client = sockets()

            client.webSocket("/ws/poker/tables/gold") {
                sayHello(host.token)
                // The second player arrives while the first hand is being played.
                val other = launch {
                    client.webSocket("/ws/poker/tables/gold") {
                        sayHello(otherToken)

                        val watching = viewWhere { it.getValue("table") !is JsonNull }
                        if (watching.hand == 1) {
                            assertTrue(!watching.getValue("dealtIn").jsonPrimitive.boolean, "sitting down part way through means waiting")
                            assertEquals(-1, watching.table.getValue("yourSeat").jsonPrimitive.int)
                            assertTrue(watching.table.getValue("players").jsonArray.all { it.jsonObject.getValue("cards") is JsonNull || watching.phase == "results" })
                            say("""{"type":"act","action":"fold"}""")
                            assertEquals("error", nextOf("error").type, "and having no hand to play")
                        }

                        val dealt = viewWhere { it.getValue("table") !is JsonNull && it.getValue("dealtIn").jsonPrimitive.boolean }
                        assertEquals(3, dealt.table.getValue("players").jsonArray.size)
                        val seat = dealt.table.getValue("yourSeat").jsonPrimitive.int
                        assertEquals(2, seat, "the next free seat after Banca's and the first player's")
                        assertTrue(cardsOf(dealt, 1) is JsonNull, "the other player's cards are not shown")
                        playAlong(hand = dealt.hand)
                    }
                }

                val firstHand = viewWhere { it.getValue("table") !is JsonNull }
                // Takes a moment over the first hand, so the other player finds it in progress.
                delay(300)
                playAlong(hand = firstHand.hand)

                val three = viewWhere { it.getValue("table") !is JsonNull && it.table.getValue("players").jsonArray.size == 3 }
                assertTrue(cardsOf(three, 2) is JsonNull || three.phase == "results")
                playAlong(hand = three.hand)
                other.join()
            }
        }

        val theirs = runBlocking { host.store.rounds(otherId, 10) }
        assertTrue(theirs.isNotEmpty() && theirs.all { it.detail.getValue("players").jsonPrimitive.int == 3 })
    }

    @Test
    fun `nothing can be played out of turn`() = testApplication {
        val guest = TestPlayers()
        serve(guest, timings = PokerTimings(turn = 2_000.milliseconds, turnAway = 100.milliseconds, results = 300.milliseconds, agentDelay = 600.milliseconds))

        sockets().webSocket("/ws/poker/tables/emerald") {
            sayHello(guest.token)

            // Banca takes a moment over each decision here, so there are moments that are not the player's.
            val notMine = viewWhere { it.getValue("table") !is JsonNull && it.phase == "playing" && !it.myTurn }
            assertEquals("Banca", notMine.getValue("actor").jsonPrimitive.content)
            assertTrue(notMine.table.getValue("legal") is JsonNull)
            say("""{"type":"act","action":"fold"}""")
            assertTrue("not your turn" in nextOf("error").getValue("message").jsonPrimitive.content)
        }
    }

    @Test
    fun `a player who takes too long is checked or folded, and the table moves on`() {
        val guest = TestPlayers()

        testApplication {
            serve(guest, timings = PokerTimings(turn = 200.milliseconds, turnAway = 80.milliseconds, results = 250.milliseconds, agentDelay = Duration.ZERO))

            sockets().webSocket("/ws/poker/tables/emerald") {
                sayHello(guest.token)
                // Does nothing at all, and hands still come and go.
                viewWhere { it.getValue("table") !is JsonNull && it.hand >= 2 }
            }
        }

        val first = runBlocking { guest.store.rounds(guest.player.id, 10) }.last()
        assertTrue(first.net <= 0, "nothing was put in by choice")
        assertTrue(first.detail.getValue("bets").jsonPrimitive.int == 0 && first.detail.getValue("raises").jsonPrimitive.int == 0)
    }

    @Test
    fun `a player who drops mid-hand is not waited for, and the hand is settled`() {
        val guest = TestPlayers()

        testApplication {
            serve(guest)

            sockets().webSocket("/ws/poker/tables/emerald") {
                sayHello(guest.token)
                viewWhere { it.getValue("table") !is JsonNull && it.phase == "playing" }
            }

            // Well inside the two seconds a player who was still there would have had for each decision.
            withTimeout(1_500) { while (guest.store.rounds(guest.player.id, 1).isEmpty()) delay(20) }
        }

        val hand = runBlocking { guest.store.rounds(guest.player.id, 1) }.single()
        assertTrue(hand.outcome != RoundOutcome.WIN || hand.detail.getValue("showdown").jsonPrimitive.boolean)
        assertEquals(2_000 + hand.net, runBlocking { guest.players.balance(guest.player) })
    }

    @Test
    fun `a table everyone has left stops, and starts again for the next player`() = testApplication {
        val guest = TestPlayers()
        serve(guest)

        sockets().webSocket("/ws/poker/tables/emerald") {
            sayHello(guest.token)
            viewWhere { it.getValue("table") !is JsonNull }
        }

        delay(1_500)
        val idle = Json.parseToJsonElement(client.get("/poker/tables").bodyAsText()).jsonArray[0].jsonObject
        assertEquals(0, idle.getValue("players").jsonPrimitive.int)
        val played = runBlocking { guest.store.rounds(guest.player.id, 50) }.size
        delay(700)
        assertEquals(played, runBlocking { guest.store.rounds(guest.player.id, 50) }.size, "no hands are dealt to an empty table")

        sockets().webSocket("/ws/poker/tables/emerald") {
            sayHello(guest.token)
            val back = viewWhere { it.getValue("table") !is JsonNull && it.getValue("dealtIn").jsonPrimitive.boolean }
            assertEquals("playing", back.phase)
        }
    }

    @Test
    fun `a full table takes nobody else`() = testApplication {
        val host = TestPlayers()
        val (_, otherToken) = anotherPlayerOf(host)
        // Two seats: Banca's, and one for a player.
        serve(host, seats = 2)
        val client = sockets()

        client.webSocket("/ws/poker/tables/emerald") {
            sayHello(host.token)
            viewWhere { it.getValue("table") !is JsonNull }

            client.webSocket("/ws/poker/tables/emerald") {
                sayHello(otherToken)
                assertEquals("full", nextOf("error").getValue("code").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `Banca has its say in the chat once a hand is over, marked as its own`() = testApplication {
        val talkative = object : SeatDriver {
            override suspend fun decide(view: TableView, trace: suspend (TraceEvent) -> Unit): Action =
                if (view.legal!!.canCheck) Action.Check else Action.Call

            override fun remark(view: TableView, net: Long): String? =
                "Seat ${view.yourSeat}, hand ${view.handNumber}.".takeIf { view.result != null }
        }
        val guest = TestPlayers()
        application {
            module(
                pokerTables = PokerTablesConfig(timings = quick, opponent = { talkative }, random = { Random(3) }),
                players = guest.players,
                keepTablesFor = 150.milliseconds,
            )
        }

        sockets().webSocket("/ws/poker/tables/emerald") {
            sayHello(guest.token)
            assertTrue(nextOf("chat_log").getValue("lines").jsonArray.isEmpty())

            var over = false
            var line: JsonObject? = null
            withTimeout(8_000) {
                while (line == null) {
                    val message = receiveJson()
                    when (message.type) {
                        "chat" -> {
                            assertTrue(over, "nothing is said while the hand is live")
                            line = message.getValue("line").jsonObject
                        }
                        "state" -> {
                            val view = message.getValue("view").jsonObject
                            if (view.getValue("table") is JsonNull) continue
                            over = view.phase == "results"
                            if (view.myTurn) say("""{"type":"act","action":"fold"}""")
                        }
                    }
                }
            }

            assertEquals("Banca", line!!.getValue("from").jsonPrimitive.content)
            assertEquals("Seat 0, hand 1.", line!!.getValue("text").jsonPrimitive.content)
            assertTrue(line!!.getValue("banca").jsonPrimitive.boolean)

            // A player's own lines are never marked as Banca's, whatever they call themselves.
            say("""{"type":"chat","say":"good_luck"}""")
            assertFalse(nextOf("chat").getValue("line").jsonObject.getValue("banca").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `players at a table can talk to each other`() = testApplication {
        val guest = TestPlayers()
        serve(guest)

        sockets().webSocket("/ws/poker/tables/ivory") {
            sayHello(guest.token)
            nextOf("chat_log")
            say("""{"type":"chat","say":"good_luck"}""")
            assertEquals("Good luck", nextOf("chat").getValue("line").jsonObject.getValue("text").jsonPrimitive.content)
        }
    }

    @Test
    fun `the coach answers the player who asked, about their own hand, and nobody else hears it`() {
        val host = TestPlayers()
        val (_, otherToken) = anotherPlayerOf(host)
        val overheard = mutableListOf<String>()

        testApplication {
            serve(host, quick.let { PokerTimings(turn = 6_000.milliseconds, turnAway = it.turnAway, results = it.results, agentDelay = Duration.ZERO) })

            sockets().webSocket("/ws/poker/tables/emerald") {
                sayHello(host.token)
                nextOf("chat_log")

                val other = launch {
                    sockets().webSocket("/ws/poker/tables/emerald") {
                        sayHello(otherToken)
                        // Plays along and notes every kind of message that reaches this seat.
                        while (true) {
                            val message = receiveJson()
                            overheard += message.type
                            if (message.type != "state") continue
                            val view = message.getValue("view").jsonObject
                            if (view.myTurn) {
                                val canCheck = view.table.getValue("legal").jsonObject.getValue("canCheck").jsonPrimitive.boolean
                                say("""{"type":"act","action":"${if (canCheck) "check" else "call"}"}""")
                            }
                        }
                    }
                }

                say("""{"type":"advise"}""".also { viewWhere { it.myTurn } })
                val advice = nextOf("advice").getValue("advice").jsonObject
                assertTrue(advice.getValue("reason").jsonPrimitive.content.isNotBlank())
                assertTrue(advice.getValue("figures").jsonObject.getValue("opponents").jsonPrimitive.int >= 1)

                say("""{"type":"act","action":"fold"}""")
                viewWhere { !it.myTurn }
                say("""{"type":"advise"}""")
                assertEquals("error", nextOf("error").type, "with no decision of their own there is nothing to ask about")

                other.cancel()
            }
        }

        assertTrue("coach_trace" !in overheard && "advice" !in overheard, "advice is for the player who asked: $overheard")
    }
}

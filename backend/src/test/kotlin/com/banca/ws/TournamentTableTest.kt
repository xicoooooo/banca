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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A private poker table played as a tournament, from the opening to the podium. */
class TournamentTableTest {

    private fun ApplicationTestBuilder.serve(host: TestPlayers) {
        application {
            module(
                players = host.players,
                pokerTables = PokerTablesConfig(
                    // Banca here only checks and calls, at once.
                    timings = PokerTimings(turn = 2_000.milliseconds, turnAway = 100.milliseconds, results = 100.milliseconds, agentDelay = Duration.ZERO),
                    random = { Random(11) },
                ),
            )
        }
    }

    private suspend fun ApplicationTestBuilder.open(path: String, token: String, body: String) =
        client.post(path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(body)
        }

    private val JsonObject.type: String get() = getValue("type").jsonPrimitive.content

    private suspend fun DefaultClientWebSocketSession.nextView(): JsonObject {
        var message = receiveJson()
        while (message.type != "state") message = receiveJson()
        return message.getValue("view").jsonObject
    }

    @Test
    fun `only poker is played as a tournament, and a format must be one there is`() = testApplication {
        val host = TestPlayers()
        serve(host)

        assertEquals(HttpStatusCode.Created, open("/poker/tables", host.token, """{"format":"tournament"}""").status)
        assertEquals(HttpStatusCode.BadRequest, open("/blackjack/tables", host.token, """{"format":"tournament"}""").status)
        assertEquals(HttpStatusCode.BadRequest, open("/roulette/rooms", host.token, """{"format":"tournament"}""").status)
        assertEquals(HttpStatusCode.BadRequest, open("/poker/tables", host.token, """{"format":"knockout"}""").status)
    }

    @Test
    fun `a tournament is played from equal stacks to a winner, and touches nobody's own chips`() = testApplication {
        val host = TestPlayers()
        serve(host)
        val before = runBlocking { host.players.balance(host.player) }
        val id = Json.parseToJsonElement(open("/poker/tables", host.token, """{"format":"tournament"}""").bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content

        createClient { install(WebSockets) }.webSocket("/ws/poker/tables/$id") {
            sayHello(host.token)
            val waiting = withTimeout(5_000) { nextView() }
            assertFalse(waiting.getValue("started").jsonPrimitive.boolean)
            assertTrue(waiting.getValue("practice").jsonPrimitive.boolean, "its chips are its own")
            send(Frame.Text("""{"type":"start"}"""))

            // The first hand: everyone on the same stack, at the first blinds.
            val first = withTimeout(5_000) {
                var view = nextView()
                while (view.getValue("table") is JsonNull) view = nextView()
                view
            }
            val tournament = first.getValue("tournament").jsonObject
            assertTrue(tournament.getValue("running").jsonPrimitive.boolean)
            assertEquals(1, tournament.getValue("level").jsonPrimitive.int)
            assertEquals(listOf(1_500L, 1_500L), tournament.getValue("standings").jsonArray.map { it.jsonObject.getValue("chips").jsonPrimitive.long })
            assertEquals(20, first.getValue("table").jsonObject.getValue("bigBlind").jsonPrimitive.int)

            // All in, every time, until one of the two has every chip.
            val over = withTimeout(30_000) {
                var view = first
                while (true) {
                    val standing = view["tournament"]?.takeIf { it !is JsonNull }?.jsonObject
                    if (standing != null && !standing.getValue("running").jsonPrimitive.boolean) break
                    if (view.getValue("yourTurn").jsonPrimitive.boolean) {
                        val legal = view.getValue("table").jsonObject.getValue("legal").jsonObject
                        val move = when {
                            legal.getValue("canRaise").jsonPrimitive.boolean -> """{"type":"act","action":"raise","amount":${legal.getValue("maxTo").jsonPrimitive.long}}"""
                            legal.getValue("canBet").jsonPrimitive.boolean -> """{"type":"act","action":"bet","amount":${legal.getValue("maxTo").jsonPrimitive.long}}"""
                            legal.getValue("canCall").jsonPrimitive.boolean -> """{"type":"act","action":"call"}"""
                            else -> """{"type":"act","action":"check"}"""
                        }
                        send(Frame.Text(move))
                    }
                    view = nextView()
                }
                view
            }

            val standings = over.getValue("tournament").jsonObject.getValue("standings").jsonArray.map { it.jsonObject }
            assertEquals(listOf(1, 2), standings.map { it.getValue("place").jsonPrimitive.int })
            assertEquals(3_000L, standings.first().getValue("chips").jsonPrimitive.long, "the winner has every chip")
            assertEquals(setOf("Banca", host.player.name), standings.map { it.getValue("name").jsonPrimitive.content }.toSet())
            assertEquals("results", over.getValue("phase").jsonPrimitive.content, "the hand that won it is shown first")
            val after = withTimeout(5_000) {
                var view = nextView()
                while (view.getValue("started").jsonPrimitive.boolean) view = nextView()
                view
            }
            assertEquals("waiting", after.getValue("phase").jsonPrimitive.content, "and then the table waits for its host to start another")
            assertFalse(after.getValue("tournament").jsonObject.getValue("running").jsonPrimitive.boolean)
        }

        assertEquals(before, runBlocking { host.players.balance(host.player) }, "nobody's own chips moved")
        assertTrue(runBlocking { host.store.rounds(host.player.id, 10) }.isEmpty(), "and no round was written down")
    }
}

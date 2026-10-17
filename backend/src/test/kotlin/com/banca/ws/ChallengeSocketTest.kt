package com.banca.ws

import com.banca.module
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Challenges: one player sets a run of blackjack, others play the same shoe, and they are compared. */
class ChallengeSocketTest {

    private fun ApplicationTestBuilder.serve(host: TestPlayers) {
        // A connection that drops is let go quickly, so leaving can be tested.
        application { module(players = host.players, keepTablesFor = 150.milliseconds) }
    }

    private fun ApplicationTestBuilder.sockets() = createClient { install(WebSockets) }

    /** Challenges are for players who have signed in. */
    private fun TestPlayers.signIn(id: java.util.UUID = player.id) = runBlocking { store.linkAccount(id, "account-$id") }

    private val JsonObject.type: String get() = getValue("type").jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.set(token: String): String =
        Json.parseToJsonElement(client.post("/blackjack/challenges") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText())
            .jsonObject.getValue("code").jsonPrimitive.content

    private suspend fun DefaultClientWebSocketSession.next(type: String): JsonObject = withTimeout(8_000) {
        var message = receiveJson()
        while (message.type != type) message = receiveJson()
        message
    }

    /**
     * Plays every round of a challenge the same simple way: the smallest bet,
     * standing on whatever is dealt, declining insurance. Returns the cards
     * each round began with and how the challenge stood at the end.
     */
    private suspend fun DefaultClientWebSocketSession.playThrough(): Pair<List<String>, JsonObject> {
        val dealt = mutableListOf<String>()
        var finished: JsonObject? = null
        withTimeout(15_000) {
            send(Frame.Text("""{"type":"bet","amount":10}"""))
            while (finished == null) {
                val message = receiveJson()
                when (message.type) {
                    "challenge" -> {
                        val view = message.getValue("view").jsonObject
                        val mine = view.getValue("standings").jsonArray.map { it.jsonObject }.firstOrNull { it.getValue("you").jsonPrimitive.boolean }
                        if (mine != null && mine["chips"] !is JsonNull) finished = view
                    }
                    "state" -> {
                        val view = message.getValue("view").jsonObject
                        when (view.getValue("phase").jsonPrimitive.content) {
                            "player" -> {
                                val hand = view.getValue("hands").jsonArray.first().jsonObject.getValue("cards").jsonArray
                                if (hand.size == 2 && dealt.size < view.getValue("roundNumber").jsonPrimitive.int) dealt += hand.joinToString { it.jsonPrimitive.content }
                                send(Frame.Text("""{"type":"act","action":"stand"}"""))
                            }
                            "insurance" -> send(Frame.Text("""{"type":"act","action":"decline_insurance"}"""))
                            "settled" -> if (view.getValue("roundNumber").jsonPrimitive.int < 10) send(Frame.Text("""{"type":"bet","amount":10}"""))
                        }
                    }
                }
            }
        }
        return dealt to finished!!
    }

    @Test
    fun `two players take the same challenge from the same shoe, and are compared`() = testApplication {
        val host = TestPlayers()
        serve(host)
        val (friend, friendToken) = runBlocking { host.players.createGuest() }
        host.signIn()
        host.signIn(friend.id)
        val before = runBlocking { host.players.balance(host.player) to host.players.balance(friend) }
        val code = set(host.token)

        val (mine, _) = sockets().run {
            var result: Pair<List<String>, JsonObject>? = null
            webSocket("/ws/blackjack/challenges/$code") {
                sayHello(host.token)
                val start = next("challenge").getValue("view").jsonObject
                assertEquals(10, start.getValue("rounds").jsonPrimitive.int)
                assertEquals(1_000, start.getValue("stack").jsonPrimitive.int)
                assertTrue(start.getValue("yours").jsonPrimitive.boolean)
                result = playThrough()
            }
            result!!
        }

        val (theirs, standing) = sockets().run {
            var result: Pair<List<String>, JsonObject>? = null
            webSocket("/ws/blackjack/challenges/$code") {
                sayHello(friendToken)
                result = playThrough()
            }
            result!!
        }

        assertEquals(mine, theirs, "played the same way, the same shoe deals the same cards")
        val standings = standing.getValue("standings").jsonArray.map { it.jsonObject }
        assertEquals(2, standings.size)
        assertTrue(standings.all { it.getValue("roundsPlayed").jsonPrimitive.int == 10 || it.getValue("chips").jsonPrimitive.long < 10 })
        assertEquals(standings[0].getValue("chips"), standings[1].getValue("chips"), "and the same play ends on the same chips")
        assertEquals(listOf(1, 1), standings.map { it.getValue("place").jsonPrimitive.int }, "so they share first place")

        assertEquals(before, runBlocking { host.players.balance(host.player) to host.players.balance(friend) }, "nobody's own chips moved")
        assertTrue(runBlocking { host.store.rounds(host.player.id, 50) }.isEmpty(), "and nothing was written to anyone's record")

        // Anyone can look at how it stands.
        val looked = Json.parseToJsonElement(client.get("/blackjack/challenges/$code").bodyAsText()).jsonObject
        assertEquals(host.player.name, looked.getValue("by").jsonPrimitive.content)
        assertEquals(2, looked.getValue("standings").jsonArray.size)
    }

    @Test
    fun `a challenge is played within its bets and with no coach`() = testApplication {
        val host = TestPlayers().apply { signIn() }
        serve(host)
        val code = set(host.token)

        sockets().webSocket("/ws/blackjack/challenges/$code") {
            sayHello(host.token)
            next("state")
            send(Frame.Text("""{"type":"bet","amount":500}"""))
            assertEquals("error", next("error").type, "no more than a hundred on a round")
            send(Frame.Text("""{"type":"bet","amount":10}"""))
            next("state")
            send(Frame.Text("""{"type":"advise"}"""))
            assertTrue(next("error").getValue("message").jsonPrimitive.content.contains("no coach"))
        }
    }

    @Test
    fun `opening a challenge takes nothing, and the go begins with the first chips down`() = testApplication {
        val host = TestPlayers().apply { signIn() }
        serve(host)
        val code = set(host.token)

        // A look, and away again.
        sockets().webSocket("/ws/blackjack/challenges/$code") {
            sayHello(host.token)
            next("state")
        }
        kotlinx.coroutines.delay(600)

        sockets().webSocket("/ws/blackjack/challenges/$code") {
            sayHello(host.token)
            val view = next("challenge").getValue("view").jsonObject
            assertFalse(view.getValue("taken").jsonPrimitive.boolean, "looking was not taking it")
            next("state")
            send(Frame.Text("""{"type":"bet","amount":10}"""))
            next("state")
        }
        kotlinx.coroutines.delay(600)

        // Leaving once a go has begun ends it there.
        sockets().webSocket("/ws/blackjack/challenges/$code") {
            sayHello(host.token)
            assertEquals("challenge_taken", next("error").getValue("code").jsonPrimitive.content)
        }
        val standing = Json.parseToJsonElement(client.get("/blackjack/challenges/$code").bodyAsText()).jsonObject
            .getValue("standings").jsonArray.single().jsonObject
        assertFalse(standing.getValue("complete").jsonPrimitive.boolean, "a go left part way is marked as cut short")
        assertTrue(standing.getValue("roundsPlayed").jsonPrimitive.int <= 1)
    }

    @Test
    fun `a code that is no challenge says so, and the list of a player's challenges has the ones they set`() = testApplication {
        val host = TestPlayers().apply { signIn() }
        serve(host)

        sockets().webSocket("/ws/blackjack/challenges/zzzzzz") {
            sayHello(host.token)
            assertEquals("no_challenge", next("error").getValue("code").jsonPrimitive.content)
        }

        val code = set(host.token)
        val mine = Json.parseToJsonElement(client.get("/players/me/challenges") { header(HttpHeaders.Authorization, "Bearer ${host.token}") }.bodyAsText()).jsonArray
        assertEquals(listOf(code), mine.map { it.jsonObject.getValue("code").jsonPrimitive.content })
        assertFalse(mine.single().jsonObject.getValue("taken").jsonPrimitive.boolean, "set, but not yet played")

        val found = Json.parseToJsonElement(client.get("/tables/$code").bodyAsText()).jsonObject
        assertEquals("challenge", found.getValue("game").jsonPrimitive.content, "and its code is found where table codes are")
        assertEquals(HttpStatusCode.Unauthorized, client.post("/blackjack/challenges").status)
    }

    @Test
    fun `a guest is shown a challenge but asked to sign in to take it`() = testApplication {
        val host = TestPlayers().apply { signIn() }
        serve(host)
        val code = set(host.token)
        val (_, guestToken) = runBlocking { host.players.createGuest() }

        sockets().webSocket("/ws/blackjack/challenges/$code") {
            sayHello(guestToken)
            assertEquals("sign_in_needed", next("error").getValue("code").jsonPrimitive.content)
        }
        assertEquals(HttpStatusCode.BadRequest, client.post("/blackjack/challenges") { header(HttpHeaders.Authorization, "Bearer $guestToken") }.status)
    }
}

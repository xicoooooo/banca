package com.banca.players

import com.banca.Allowance
import com.banca.Limit
import com.banca.module
import com.banca.ws.TestPlayers
import com.banca.ws.sayHello
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class FriendsTest {

    private val host = TestPlayers()
    private val players = host.players

    /** A player who has signed in, known by [name], and the token their browser holds. */
    private fun member(name: String): Pair<Player, String> = runBlocking {
        val (guest, token) = players.createGuest()
        val saved = players.signIn(guest, token, Account(UUID.randomUUID().toString(), null)).player
        players.rename(saved, name) to token
    }

    private fun friendsOf(player: Player) = runBlocking { players.friends.view(player) }

    // ---------------------------------------------------------------- the rules

    @Test
    fun `a player gives out a code, and whoever has it can ask to be their friend`() = runBlocking {
        val (ana, _) = member("Ana")
        val (rui, _) = member("Rui")
        val code = friendsOf(ana).code

        assertEquals(8, code.length)
        assertEquals(code, friendsOf(ana).code, "it is theirs to keep")

        // Typed in capitals with a gap, as it might be read out.
        assertEquals(FriendState.OUTGOING, players.friends.ask(rui, code.uppercase().chunked(4).joinToString(" "), null))
        assertEquals(listOf("Ana"), friendsOf(rui).outgoing.map { it.name })
        assertEquals(listOf("Rui"), friendsOf(ana).incoming.map { it.name })
        assertTrue(friendsOf(ana).friends.isEmpty(), "asking is not yet being friends")

        assertEquals(FriendState.FRIENDS, players.friends.accept(ana, rui.id))
        assertEquals(listOf("Rui"), friendsOf(ana).friends.map { it.name })
        assertEquals(listOf("Ana"), friendsOf(rui).friends.map { it.name })
        assertTrue(friendsOf(ana).incoming.isEmpty() && friendsOf(rui).outgoing.isEmpty())
    }

    @Test
    fun `friends are for players who have signed in`() = runBlocking {
        val (ana, _) = member("Ana")
        val guest = host.player

        assertEquals(Friends.NEEDS_SIGN_IN, assertFailsWith<IllegalArgumentException> { players.friends.view(guest) }.message)
        assertFailsWith<IllegalArgumentException> { players.friends.ask(guest, friendsOf(ana).code, null) }
        assertFailsWith<IllegalArgumentException>("a guest is not to be found by anyone") { players.friends.ask(ana, null, guest.id) }
        Unit
    }

    @Test
    fun `nobody befriends themselves, a stranger's request cannot be accepted, and a code nobody has finds nobody`() = runBlocking {
        val (ana, _) = member("Ana")
        val (rui, _) = member("Rui")

        assertFailsWith<IllegalArgumentException> { players.friends.ask(ana, friendsOf(ana).code, null) }
        assertFailsWith<IllegalArgumentException> { players.friends.ask(ana, "zzzzzzzz", null) }
        assertFailsWith<IllegalArgumentException> { players.friends.ask(ana, null, null) }
        assertFailsWith<IllegalArgumentException>("Rui has not asked") { players.friends.accept(ana, rui.id) }

        players.friends.ask(ana, null, rui.id)
        assertFailsWith<IllegalArgumentException>("nor can Ana accept her own request") { players.friends.accept(ana, rui.id) }
        Unit
    }

    @Test
    fun `either friend can end it, and a request can be turned down`() = runBlocking {
        val (ana, _) = member("Ana")
        val (rui, _) = member("Rui")
        players.friends.ask(ana, null, rui.id)

        assertTrue(players.friends.remove(rui, ana.id), "turned down")
        assertTrue(friendsOf(ana).outgoing.isEmpty())

        players.friends.ask(ana, null, rui.id)
        players.friends.accept(rui, ana.id)
        assertTrue(players.friends.remove(ana, rui.id))
        assertTrue(friendsOf(rui).friends.isEmpty())
    }

    // ------------------------------------------------------------- who is here

    @Test
    fun `a friend is online while they are at a table or have just been heard from, and not after`() = runBlocking {
        val (ana, _) = member("Ana")
        val (rui, _) = member("Rui")
        players.friends.ask(ana, null, rui.id)
        players.friends.accept(rui, ana.id)
        fun rui() = friendsOf(ana).friends.single()

        assertFalse(rui().online)
        assertNull(rui().doing)

        players.presence.seen(rui.id)
        assertTrue(rui().online, "in the lobby a moment ago")
        host.clock.advance(Duration.ofMinutes(3))
        assertFalse(rui().online, "and gone quiet since")

        players.presence.sat(rui.id, Place(Game.POKER, "gold"))
        host.clock.advance(Duration.ofHours(2))
        assertTrue(rui().online, "at a table for as long as they sit there")
        assertEquals("Playing poker at the Gold Table", rui().doing)
        assertEquals("poker/gold", rui().joinAt, "a table anyone may walk into is somewhere to join them")

        players.presence.left(rui.id, Place(Game.POKER, "gold"))
        assertNull(rui().doing)
        assertTrue(rui().online, "just got up")
        host.clock.advance(Duration.ofMinutes(3))
        assertFalse(rui().online)
    }

    @Test
    fun `a friend at a private table or playing alone is said to be, with nowhere to follow them`() = runBlocking {
        val (ana, _) = member("Ana")
        val (rui, _) = member("Rui")
        players.friends.ask(ana, null, rui.id)
        players.friends.accept(rui, ana.id)
        fun rui() = friendsOf(ana).friends.single()

        players.presence.sat(rui.id, Place(Game.BLACKJACK, "k7x2m9"))
        assertEquals("Playing blackjack at a private table", rui().doing)
        assertNull(rui().joinAt, "its code is the host's to give out")
        players.presence.left(rui.id, Place(Game.BLACKJACK, "k7x2m9"))

        players.presence.sat(rui.id, Place(Game.ROULETTE, Place.ALONE))
        assertEquals("Playing roulette alone", rui().doing)
        assertNull(rui().joinAt)

        players.presence.sat(rui.id, Place(Game.ROULETTE, "emerald"))
        assertEquals("Playing roulette at the Emerald Room", rui().doing, "the table they sat down at last")
    }

    @Test
    fun `friends who are here come first`() = runBlocking {
        val (ana, _) = member("Ana")
        for (name in listOf("Zeca", "Bia", "Marta")) {
            val (friend, _) = member(name)
            players.friends.ask(ana, null, friend.id)
            players.friends.accept(friend, ana.id)
            if (name == "Zeca") players.presence.sat(friend.id, Place(Game.POKER, "gold"))
        }

        assertEquals(listOf("Zeca", "Bia", "Marta"), friendsOf(ana).friends.map { it.name })
    }

    @Test
    fun `an address is read as the place it is`() {
        assertEquals(Place(Game.POKER, Place.ALONE), Place.at("/ws/table"))
        assertEquals(Place(Game.BLACKJACK, "gold"), Place.at("/ws/blackjack/tables/gold"))
        assertEquals(Place(Game.ROULETTE, "k7x2m9"), Place.at("/ws/roulette/rooms/k7x2m9"))
        assertNull(Place.at("/health"))
    }

    // ------------------------------------------------------------ over the wire

    private fun ApplicationTestBuilder.serve(asks: Allowance = Allowance(Limit(50, 1.hours))) {
        application { module(players = players, friendRequests = asks) }
    }

    private fun json(response: String): JsonObject = Json.parseToJsonElement(response).jsonObject

    private suspend fun ApplicationTestBuilder.ask(token: String, body: String): HttpResponse = client.post("/friends") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `friends are asked, accepted, listed and ended over the wire`() = testApplication {
        serve()
        val (ana, anaToken) = member("Ana")
        val (rui, ruiToken) = member("Rui")

        val mine = json(client.get("/friends") { header(HttpHeaders.Authorization, "Bearer $anaToken") }.bodyAsText())
        val code = mine.getValue("code").jsonPrimitive.content

        val asked = ask(ruiToken, """{"code":"$code"}""")
        assertEquals(HttpStatusCode.OK, asked.status)
        assertEquals("Ana", json(asked.bodyAsText()).getValue("outgoing").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)

        val accepted = client.post("/friends/${rui.id}/accept") { header(HttpHeaders.Authorization, "Bearer $anaToken") }
        assertEquals(HttpStatusCode.OK, accepted.status)
        val friend = json(accepted.bodyAsText()).getValue("friends").jsonArray.single().jsonObject
        assertEquals("Rui", friend.getValue("name").jsonPrimitive.content)
        assertEquals("Bronze", friend.getValue("league").jsonPrimitive.content)
        assertTrue(friend.getValue("online").jsonPrimitive.boolean, "Rui has just been heard from")

        val ended = client.delete("/friends/${ana.id}") { header(HttpHeaders.Authorization, "Bearer $ruiToken") }
        assertTrue(json(ended.bodyAsText()).getValue("friends").jsonArray.isEmpty())
    }

    @Test
    fun `what cannot be done is refused plainly`() = testApplication {
        serve(asks = Allowance(Limit(4, 1.hours)))
        val (ana, anaToken) = member("Ana")
        val mine = json(client.get("/friends") { header(HttpHeaders.Authorization, "Bearer $anaToken") }.bodyAsText()).getValue("code").jsonPrimitive.content

        assertEquals(HttpStatusCode.Unauthorized, client.get("/friends").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/friends") { header(HttpHeaders.Authorization, "Bearer ${host.token}") }.status, "a guest is told to sign in")
        assertEquals(HttpStatusCode.BadRequest, ask(anaToken, """{"code":"$mine"}""").status, "their own code")
        assertEquals(HttpStatusCode.BadRequest, ask(anaToken, """{"code":"zzzzzzzz"}""").status)
        assertEquals(HttpStatusCode.BadRequest, ask(anaToken, "not json").status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/friends/${ana.id}/accept") { header(HttpHeaders.Authorization, "Bearer $anaToken") }.status)
        assertEquals(HttpStatusCode.BadRequest, ask(anaToken, """{"id":"${host.player.id}"}""").status, "a guest cannot be found")
        assertEquals(HttpStatusCode.TooManyRequests, ask(anaToken, """{"code":"zzzzzzzz"}""").status, "and only so many may be asked")
    }

    @Test
    fun `sitting down at a table is what tells a friend where someone is`() = testApplication {
        serve()
        val (ana, anaToken) = member("Ana")
        val (rui, ruiToken) = member("Rui")
        players.friends.ask(ana, null, rui.id)
        players.friends.accept(rui, ana.id)
        suspend fun ruiAsAnaSeesHim() =
            json(client.get("/friends") { header(HttpHeaders.Authorization, "Bearer $anaToken") }.bodyAsText()).getValue("friends").jsonArray.single().jsonObject

        createClient { install(WebSockets) }.webSocket("/ws/roulette/rooms/gold") {
            sayHello(ruiToken)
            val seen = ruiAsAnaSeesHim()
            assertTrue(seen.getValue("online").jsonPrimitive.boolean)
            assertEquals("Playing roulette at the Gold Room", seen.getValue("doing").jsonPrimitive.content)
            assertEquals("roulette/gold", seen.getValue("joinAt").jsonPrimitive.content)
        }

        // The socket has closed. Give the server a moment to notice.
        var after = ruiAsAnaSeesHim()
        repeat(20) {
            if (after.getValue("doing") is JsonNull) return@repeat
            kotlinx.coroutines.delay(100)
            after = ruiAsAnaSeesHim()
        }
        assertTrue(after.getValue("doing") is JsonNull, "and getting up is what tells them he has gone")
    }
}

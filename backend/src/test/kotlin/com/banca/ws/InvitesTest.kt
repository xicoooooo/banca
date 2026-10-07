package com.banca.ws

import com.banca.Allowance
import com.banca.Limit
import com.banca.module
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Tables a player opens for their own company, reached only by their address. */
class InvitesTest {

    // ------------------------------------------------------------ the register

    private var now = 1_000_000L
    private val sitting = mutableSetOf<String>()
    private fun register(most: Int = 150) =
        Invites(make = { spec -> spec.id }, occupied = { it in sitting }, most = most, keepEmptyFor = 30.minutes, now = { now })

    @Test
    fun `a table that is opened can be found by its address, and by nothing else`() = runBlocking {
        val invites = register()

        val spec = assertNotNull(invites.open("Ana's table"))

        assertEquals("Ana's table", spec.name)
        assertTrue(spec.byInvite)
        assertEquals(spec.id, invites.find(spec.id))
        assertNull(invites.find("emerald"))
        assertNull(invites.find(spec.id.reversed()))
    }

    @Test
    fun `addresses are long, plain and never the same twice`() = runBlocking {
        val invites = register(most = 500)
        val ids = List(300) { invites.open("A table")!!.id }

        assertEquals(300, ids.toSet().size)
        assertTrue(ids.all { Regex("[a-z2-9]{8}").matches(it) }, "eight characters that fit in an address")
        assertTrue(ids.none { 'l' in it || 'o' in it || 'i' in it || '0' in it || '1' in it }, "and nothing easily misread")
    }

    @Test
    fun `a table left empty is cleared away, and one in use is kept however old`() = runBlocking {
        val invites = register()
        val empty = invites.open("Empty")!!.id
        val busy = invites.open("Busy")!!.id
        sitting += busy

        now += 31.minutes.inWholeMilliseconds
        invites.open("Another")

        assertNull(invites.find(empty))
        assertEquals(busy, invites.find(busy))
    }

    @Test
    fun `looking for a table counts as using it`() = runBlocking {
        val invites = register()
        val id = invites.open("Visited")!!.id

        now += 20.minutes.inWholeMilliseconds
        invites.find(id)
        now += 20.minutes.inWholeMilliseconds
        invites.open("Another")

        assertEquals(id, invites.find(id), "forty minutes old, but used twenty minutes ago")
    }

    @Test
    fun `there are only so many, until some are cleared`() = runBlocking {
        val invites = register(most = 2)
        invites.open("One")
        invites.open("Two")
        assertNull(invites.open("Three"))

        now += 31.minutes.inWholeMilliseconds
        assertNotNull(invites.open("Three"), "the first two stood empty and have gone")
    }

    // ------------------------------------------------------------- at the door

    private fun ApplicationTestBuilder.serve(host: TestPlayers, invites: Allowance = Allowance(Limit(5, 1.hours))) {
        application { module(players = host.players, invites = invites) }
    }

    private suspend fun ApplicationTestBuilder.open(path: String, token: String?): HttpResponse =
        client.post(path) { if (token != null) header(HttpHeaders.Authorization, "Bearer $token") }

    private fun idIn(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("id").jsonPrimitive.content

    @Test
    fun `a player can open a table at each game, named after them and on no list`() = testApplication {
        val host = TestPlayers()
        serve(host)

        for ((path, kind) in listOf("/poker/tables" to "table", "/blackjack/tables" to "table", "/roulette/rooms" to "room")) {
            val response = open(path, host.token)
            assertEquals(HttpStatusCode.Created, response.status)

            val opened = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("${host.player.name}'s $kind", opened.getValue("name").jsonPrimitive.content)

            val listed = Json.parseToJsonElement(client.get(path).bodyAsText()).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals(listOf("emerald", "gold", "ivory"), listed, "the address is the invitation")
        }
    }

    @Test
    fun `only a known player may open one, and only so many`() = testApplication {
        val host = TestPlayers()
        serve(host, invites = Allowance(Limit(2, 1.hours)))

        assertEquals(HttpStatusCode.Unauthorized, open("/poker/tables", null).status)
        assertEquals(HttpStatusCode.Unauthorized, open("/poker/tables", "not-a-token").status)

        assertEquals(HttpStatusCode.Created, open("/poker/tables", host.token).status)
        assertEquals(HttpStatusCode.Created, open("/blackjack/tables", host.token).status)
        assertEquals(HttpStatusCode.TooManyRequests, open("/roulette/rooms", host.token).status, "the share is across all three games")
    }

    @Test
    fun `whoever has the address sits down at the same table`() = testApplication {
        val host = TestPlayers()
        val (_, friendToken) = host.players.createGuest()
        serve(host)
        val id = idIn(open("/blackjack/tables", host.token).bodyAsText())
        val sockets = createClient { install(WebSockets) }

        sockets.webSocket("/ws/blackjack/tables/$id") {
            sayHello(host.token)
            var view = receiveJson()
            while (view.getValue("type").jsonPrimitive.content != "state") view = receiveJson()
            val mine = view.getValue("view").jsonObject
            assertEquals(id, mine.getValue("room").jsonPrimitive.content)
            assertTrue(mine.getValue("byInvite").jsonPrimitive.boolean)
            assertEquals(1, mine.getValue("seats").jsonArray.size)

            sockets.webSocket("/ws/blackjack/tables/$id") {
                sayHello(friendToken)
                var theirs = receiveJson()
                while (theirs.getValue("type").jsonPrimitive.content != "state") theirs = receiveJson()
                assertEquals(2, theirs.getValue("view").jsonObject.getValue("seats").jsonArray.size, "the friend finds the host already there")
            }
        }
    }

    @Test
    fun `the tables everyone can see are not marked as invitations`() = testApplication {
        val host = TestPlayers()
        serve(host)

        createClient { install(WebSockets) }.webSocket("/ws/roulette/rooms/emerald") {
            sayHello(host.token)
            var view = receiveJson()
            while (view.getValue("type").jsonPrimitive.content != "state") view = receiveJson()
            assertFalse(view.getValue("view").jsonObject.getValue("byInvite").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `an address that leads nowhere is answered plainly and closed`() = testApplication {
        val host = TestPlayers()
        serve(host)

        for (path in listOf("/ws/poker/tables/abcdefgh", "/ws/blackjack/tables/abcdefgh", "/ws/roulette/rooms/abcdefgh")) {
            createClient { install(WebSockets) }.webSocket(path) {
                // Welcomed first: it is the table that is missing, not the player.
                sayHello(host.token)
                val told = receiveJson()
                assertEquals("error", told.getValue("type").jsonPrimitive.content)
                assertEquals("no_table", told.getValue("code").jsonPrimitive.content)
                assertEquals(NO_TABLE, withTimeout(3_000) { closeReason.await() }?.code)
            }
        }
    }

    @Test
    fun `two tables opened by one player are two tables`() = testApplication {
        val host = TestPlayers()
        serve(host)

        val first = idIn(open("/poker/tables", host.token).bodyAsText())
        val second = idIn(open("/poker/tables", host.token).bodyAsText())

        assertNotEquals(first, second)
        createClient { install(WebSockets) }.webSocket("/ws/poker/tables/$second") {
            sayHello(host.token)
            var view = receiveJson()
            while (view.getValue("type").jsonPrimitive.content != "state") view = receiveJson()
            assertEquals(second, view.getValue("view").jsonObject.getValue("room").jsonPrimitive.content)
            assertEquals(6, view.getValue("view").jsonObject.getValue("seatsInAll").jsonPrimitive.int, "Banca has its seat here too")
        }
    }
}

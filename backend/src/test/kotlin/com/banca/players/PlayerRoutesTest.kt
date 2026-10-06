package com.banca.players

import com.banca.module
import com.banca.ws.TestPlayers
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerRoutesTest {

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `asking for a guest gives a token and a profile with opening chips`() = testApplication {
        val guest = TestPlayers()
        application { module(players = guest.players) }

        val response = client.post("/players")
        assertEquals(HttpStatusCode.Created, response.status)

        val body = json(response.bodyAsText())
        val token = body.getValue("token").jsonPrimitive.content
        assertTrue(token.length >= 40, "long enough that it cannot be guessed")
        assertEquals(2_000, body.getValue("player").jsonObject.getValue("balance").jsonPrimitive.long)

        val me = client.get("/players/me") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(body.getValue("player"), json(me.bodyAsText()))
    }

    @Test
    fun `nothing about a player is given without their token`() = testApplication {
        val guest = TestPlayers()
        application { module(players = guest.players) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/players/me").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/players/me/dashboard").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/players/me/dashboard") { header(HttpHeaders.Authorization, "Bearer wrong") }.status,
        )
    }

    @Test
    fun `a player can change their name, within reason`() = testApplication {
        val guest = TestPlayers()
        application { module(players = guest.players) }

        suspend fun rename(name: String) = client.patch("/players/me") {
            header(HttpHeaders.Authorization, "Bearer ${guest.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"name":${Json.encodeToString(kotlinx.serialization.serializer<String>(), name)}}""")
        }

        val renamed = rename("  Ana   Sofia ")
        assertEquals(HttpStatusCode.OK, renamed.status)
        assertEquals("Ana Sofia", json(renamed.bodyAsText()).getValue("name").jsonPrimitive.content, "tidied")

        assertEquals(HttpStatusCode.BadRequest, rename("A").status)
        assertEquals(HttpStatusCode.BadRequest, rename("x".repeat(21)).status)
        assertEquals(HttpStatusCode.BadRequest, rename("<script>").status)
        assertEquals("Ana Sofia", runBlocking { guest.players.authenticate(guest.token) }!!.name, "a refused name changes nothing")
    }

    @Test
    fun `the dashboard is built from the rounds the player has really played`() = testApplication {
        val guest = TestPlayers()
        application { module(players = guest.players) }
        runBlocking {
            fun round(net: Long, detail: JsonObject = buildJsonObject { }) = FinishedRound(
                Game.BLACKJACK, "t", 100, net,
                if (net > 0) RoundOutcome.WIN else if (net < 0) RoundOutcome.LOSS else RoundOutcome.PUSH, detail,
            )
            guest.players.settle(guest.player, round(150))
            guest.players.settle(guest.player, round(-100))
        }

        val response = client.get("/players/me/dashboard") { header(HttpHeaders.Authorization, "Bearer ${guest.token}") }
        assertEquals(HttpStatusCode.OK, response.status)

        val dashboard = json(response.bodyAsText())
        assertEquals(2_050, dashboard.getValue("bankroll").jsonObject.getValue("balance").jsonPrimitive.long)
        assertEquals(2, dashboard.getValue("totals").jsonObject.getValue("rounds").jsonPrimitive.int)
        assertEquals(2, dashboard.getValue("recent").jsonArray.size)
        assertEquals(3, dashboard.getValue("bankroll").jsonObject.getValue("history").jsonArray.size)
        assertEquals(guest.player.name, dashboard.getValue("player").jsonObject.getValue("name").jsonPrimitive.content)
    }
}

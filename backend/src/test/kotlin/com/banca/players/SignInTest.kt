package com.banca.players

import com.banca.module
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saving a profile to an account, and reaching it again from somewhere else. */
class SignInTest {

    private val ana = Account("11111111-1111-1111-1111-111111111111", "Ana Sofia Martins")
    private val rui = Account("22222222-2222-2222-2222-222222222222", null)

    private val store = InMemoryPlayerStore()
    private val players = Players(store)

    /** Stands in for the provider: these access tokens are good, and nothing else is. */
    private val verifier = AccountVerifier { accessToken ->
        when (accessToken) {
            "ana-signed-in" -> ana
            "rui-signed-in" -> rui
            else -> null
        }
    }

    private fun lost(amount: Long) =
        FinishedRound(Game.BLACKJACK, "t", amount, -amount, RoundOutcome.LOSS, JsonObject(emptyMap()))

    // ------------------------------------------------------------- the rules

    @Test
    fun `signing in for the first time keeps everything the guest had`() = runBlocking {
        val (guest, token) = players.createGuest()
        players.settle(guest, lost(300))

        val result = players.signIn(guest, token, ana)

        assertEquals(guest.id, result.player.id, "the same profile, now saved")
        assertEquals(ana.id, result.player.accountId)
        assertNull(result.token, "and the device keeps the token it had")
        assertEquals(1_700, players.balance(result.player))
        assertEquals(guest.id, assertNotNull(players.authenticate(token)).id)
    }

    @Test
    fun `a guest who never chose a name takes their first name`() = runBlocking {
        val (guest, token) = players.createGuest()
        assertEquals("Ana", players.signIn(guest, token, ana).player.name)

        val (other, otherToken) = players.createGuest()
        val named = players.rename(other, "Ruizinho")
        assertEquals("Ruizinho", players.signIn(named, otherToken, rui).player.name, "a chosen name is left alone")
    }

    @Test
    fun `a name that would not be allowed is not taken`() = runBlocking {
        val (guest, token) = players.createGuest()
        val result = players.signIn(guest, token, Account(ana.id, "<script>"))
        assertEquals(guest.name, result.player.name)
    }

    @Test
    fun `signing in on a second device brings the saved profile to it`() = runBlocking {
        val (phone, phoneToken) = players.createGuest()
        players.settle(phone, lost(300))
        players.signIn(phone, phoneToken, ana)

        val (laptopGuest, laptopGuestToken) = players.createGuest()
        players.settle(laptopGuest, lost(50))
        val result = players.signIn(laptopGuest, laptopGuestToken, ana)

        assertEquals(phone.id, result.player.id)
        val laptopToken = assertNotNull(result.token, "the device is given a token for the saved profile")
        assertNotEquals(phoneToken, laptopToken)
        assertEquals(phone.id, assertNotNull(players.authenticate(laptopToken)).id)
        assertEquals(phone.id, assertNotNull(players.authenticate(phoneToken)).id, "the first device still works")

        assertEquals(1_700, players.balance(result.player), "the two bankrolls are not added together")
        assertNull(players.authenticate(laptopGuestToken), "the guest left behind can no longer be reached")
    }

    @Test
    fun `signing in again where already signed in changes nothing`() = runBlocking {
        val (guest, token) = players.createGuest()
        val saved = players.signIn(guest, token, ana).player

        val again = players.signIn(saved, token, ana)

        assertEquals(saved.id, again.player.id)
        assertNull(again.token)
    }

    @Test
    fun `someone else signing in on a signed-in device does not take its profile`() = runBlocking {
        val (guest, token) = players.createGuest()
        val anas = players.signIn(guest, token, ana).player
        players.settle(anas, lost(300))

        val result = players.signIn(anas, token, rui)

        assertNotEquals(anas.id, result.player.id)
        assertEquals(rui.id, result.player.accountId)
        assertEquals(2_000, players.balance(result.player), "a profile of their own, from the start")
        assertEquals(anas.id, assertNotNull(players.authenticate(token)).id, "hers is still hers")
        assertEquals(1_700, players.balance(anas))
    }

    @Test
    fun `signing out forgets the device and keeps the profile`() = runBlocking {
        val (guest, token) = players.createGuest()
        val saved = players.signIn(guest, token, ana).player
        players.settle(saved, lost(300))

        players.signOut(token)
        assertNull(players.authenticate(token))

        val (fresh, freshToken) = players.createGuest()
        val back = players.signIn(fresh, freshToken, ana)
        assertEquals(saved.id, back.player.id)
        assertEquals(1_700, players.balance(back.player), "signing in again finds it as it was left")
    }

    // ---------------------------------------------------------- over the wire

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun ApplicationTestBuilder.serve(signIn: SignInConfig? = SignInConfig("https://accounts.example", "public-key", verifier)) {
        application { module(players = players, signIn = signIn) }
    }

    private suspend fun ApplicationTestBuilder.signIn(token: String, accessToken: String): HttpResponse =
        client.post("/players/me/account") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"accessToken":"$accessToken"}""")
        }

    @Test
    fun `the browser is told where to sign in, or that it cannot`() = testApplication {
        serve()
        val settings = json(client.get("/sign-in").bodyAsText())
        assertEquals("https://accounts.example", settings.getValue("url").jsonPrimitive.content)
        assertEquals("public-key", settings.getValue("publicKey").jsonPrimitive.content)
    }

    @Test
    fun `without sign-in set up everyone is a guest and is told so`() = testApplication {
        serve(signIn = null)
        val token = players.createGuest().second

        assertTrue(json(client.get("/sign-in").bodyAsText()).getValue("url") is JsonNull)
        assertEquals(HttpStatusCode.ServiceUnavailable, signIn(token, "ana-signed-in").status)
    }

    @Test
    fun `a confirmed sign-in saves the profile and says so from then on`() = testApplication {
        serve()
        val token = players.createGuest().second

        val before = json(client.get("/players/me") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText())
        assertFalse(before.getValue("signedIn").jsonPrimitive.boolean)

        val response = signIn(token, "ana-signed-in")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertTrue(body.getValue("token") is JsonNull)
        assertTrue(body.getValue("player").jsonObject.getValue("signedIn").jsonPrimitive.boolean)

        val dashboard = json(client.get("/players/me/dashboard") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText())
        assertTrue(dashboard.getValue("player").jsonObject.getValue("signedIn").jsonPrimitive.boolean)
    }

    @Test
    fun `a second device is handed a token for the saved profile`() = testApplication {
        serve()
        val (phone, phoneToken) = players.createGuest()
        players.settle(phone, lost(300))
        signIn(phoneToken, "ana-signed-in")

        val laptopGuestToken = players.createGuest().second
        val body = json(signIn(laptopGuestToken, "ana-signed-in").bodyAsText())
        val laptopToken = body.getValue("token").jsonPrimitive.content

        val me = json(client.get("/players/me") { header(HttpHeaders.Authorization, "Bearer $laptopToken") }.bodyAsText())
        assertEquals(1_700, me.getValue("balance").jsonPrimitive.long)
    }

    @Test
    fun `a sign-in that cannot be confirmed changes nothing`() = testApplication {
        serve()
        val (guest, token) = players.createGuest()

        assertEquals(HttpStatusCode.Forbidden, signIn(token, "made-up").status)
        assertEquals(HttpStatusCode.Unauthorized, signIn("not-a-player", "ana-signed-in").status)
        assertNull(assertNotNull(players.authenticate(token)).accountId)
        assertNull(store.findByAccount(ana.id))
        assertEquals(guest.id, assertNotNull(players.authenticate(token)).id)
    }

    @Test
    fun `signing out over the wire ends that device's access`() = testApplication {
        serve()
        val token = players.createGuest().second
        signIn(token, "ana-signed-in")

        assertEquals(HttpStatusCode.NoContent, client.delete("/players/me/session") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/players/me") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
    }
}

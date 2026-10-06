package com.banca.ws

import com.banca.players.InMemoryPlayerStore
import com.banca.players.Players
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals

/** A clock a test can move, for rules that turn on the time of day. */
class TestClock(var now: Instant = Instant.parse("2026-10-06T12:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

/** Players for one test, with a guest already made and their token to hand. */
class TestPlayers {
    val clock = TestClock()
    val store = InMemoryPlayerStore(clock)
    val players = Players(store, clock)
    private val guest = runBlocking { players.createGuest() }
    val player = guest.first
    val token = guest.second
}

suspend fun DefaultClientWebSocketSession.receiveJson(): JsonObject =
    wireJson.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject

/** Says who is connecting and waits to be welcomed, as every client must before anything else. */
suspend fun DefaultClientWebSocketSession.sayHello(token: String): JsonObject {
    send(Frame.Text("""{"type":"hello","token":"$token"}"""))
    return receiveJson().also { assertEquals("welcome", it.getValue("type").jsonPrimitive.content) }
}

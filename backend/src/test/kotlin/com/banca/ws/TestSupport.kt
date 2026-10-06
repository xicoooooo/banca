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
import kotlin.test.assertEquals

/** Players for one test, with a guest already made and their token to hand. */
class TestPlayers {
    val store = InMemoryPlayerStore()
    val players = Players(store)
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

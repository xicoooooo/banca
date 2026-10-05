package com.banca.ws

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * One player's connection to one game.
 *
 * This is what poker and blackjack turned out to share once both existed. The
 * games differ in almost everything a player sees: who acts, what a round is,
 * what may be known. What they have in common is the shape of a connection. A
 * table is set up when someone connects, messages arrive one at a time and are
 * applied in order, and a message that is wrong is that sender's problem and
 * never the table's.
 */
interface GameConnection {
    /** Called once, when the player has connected. */
    suspend fun opened()

    /**
     * Called for each message, in order. Throwing [IllegalArgumentException] or
     * [IllegalStateException] refuses the message: the player is told why and
     * the game carries on unchanged.
     */
    suspend fun received(text: String)
}

/** Sends one text message to the player. */
typealias Send = suspend (String) -> Unit

@Serializable
private data class Refusal(val type: String = "error", val message: String)

private fun refusal(message: String): String = wireJson.encodeToString(Refusal.serializer(), Refusal(message = message))

/** Serves a game at [path], giving every connection a table of its own. */
fun Route.gameSocket(path: String, connect: (Send) -> GameConnection) {
    webSocket(path) {
        val send: Send = { text -> send(Frame.Text(text)) }
        val connection = connect(send)

        connection.opened()

        for (frame in incoming) {
            if (frame !is Frame.Text) continue

            try {
                connection.received(frame.readText())
            } catch (problem: SerializationException) {
                send(refusal("That message could not be read"))
            } catch (problem: IllegalArgumentException) {
                send(refusal(problem.message ?: "That is not allowed"))
            } catch (problem: IllegalStateException) {
                send(refusal(problem.message ?: "That is not possible right now"))
            }
        }
    }
}

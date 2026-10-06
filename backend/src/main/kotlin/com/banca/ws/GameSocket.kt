package com.banca.ws

import com.banca.players.Funding
import com.banca.players.Identity
import com.banca.players.PlayerSession
import com.banca.players.Players
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * One player's connection to one game.
 *
 * This is what poker and blackjack turned out to share once both existed. The
 * games differ in almost everything a player sees: who acts, what a round is,
 * what may be known. What they have in common is the shape of a connection. A
 * player says who they are, a table is set up for them, messages arrive one at
 * a time and are applied in order, and a message that is wrong is that sender's
 * problem and never the table's.
 */
interface GameConnection {
    /** Called once, when the player is known and seated. */
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

/** The first thing a client says: the token that shows which player it is. */
@Serializable
private data class Hello(val type: String, val token: String)

@Serializable
private data class Welcome(val type: String = "welcome", val player: Identity)

@Serializable
private data class Refusal(val type: String = "error", val message: String, val code: String? = null)

@Serializable
private data class StakedNotice(val type: String = "staked", val amount: Long)

@Serializable
private data class BrokeNotice(val type: String = "broke", val dailyReady: Boolean, val nextChipsAt: String)

/** Tells the player the house has staked them, since chips appearing unexplained would look like a fault. */
fun stakedNotice(funding: Funding.Staked): String =
    wireJson.encodeToString(StakedNotice.serializer(), StakedNotice(amount = funding.amount))

/** Tells the player why nothing is being dealt, and when that changes. */
fun brokeNotice(funding: Funding.Broke): String =
    wireJson.encodeToString(BrokeNotice.serializer(), BrokeNotice(dailyReady = funding.dailyReady, nextChipsAt = funding.nextChipsAt.toString()))

private fun refusal(message: String, code: String? = null): String =
    wireJson.encodeToString(Refusal.serializer(), Refusal(message = message, code = code))

/** Serves a game at [path], giving every connection a table of its own. */
fun Route.gameSocket(path: String, players: Players, connect: (Send, PlayerSession) -> GameConnection) {
    webSocket(path) {
        val send: Send = { text -> send(Frame.Text(text)) }

        // Nothing is dealt to a stranger. The first message must say who this is.
        val first = incoming.receiveCatching().getOrNull() as? Frame.Text ?: return@webSocket
        val hello = runCatching { wireJson.decodeFromString<Hello>(first.readText()) }.getOrNull()
        val player = hello?.takeIf { it.type == "hello" }?.let { players.authenticate(it.token) }

        if (player == null) {
            // The client answers this by asking for a new guest profile.
            send(refusal("This player is not known here", code = "unknown_player"))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unknown player"))
            return@webSocket
        }

        val session = PlayerSession(player, players)
        send(wireJson.encodeToString(Welcome.serializer(), Welcome(player = Identity(player.name, session.balance(), signedIn = player.accountId != null))))

        val connection = connect(send, session)
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

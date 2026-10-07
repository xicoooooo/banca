package com.banca.ws

import com.banca.players.Funding
import com.banca.players.Identity
import com.banca.players.PlayerSession
import com.banca.players.Players
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.job
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * One player's table at one game.
 *
 * This is what the games turned out to share once more than one existed. They
 * differ in almost everything a player sees: who acts, what a round is, what
 * may be known. What they have in common is the shape of sitting at a table. A
 * player says who they are and is shown where things stand, messages arrive
 * one at a time and are applied in order, and a message that is wrong is that
 * sender's problem and never the table's.
 *
 * A table outlives the connections to it. See [Tables].
 */
interface GameConnection {
    /**
     * Called each time the player connects, the first time and every time they
     * come back. Sets the table up if it is new, tells the player where things
     * stand, and picks up anything that was left waiting.
     */
    suspend fun attached()

    /**
     * Called for each message, in order. Throwing [IllegalArgumentException] or
     * [IllegalStateException] refuses the message: the player is told why and
     * the game carries on unchanged.
     */
    suspend fun received(text: String)

    /** Called when the connection drops, to stop anything running only for the player's benefit. */
    fun detached() {}

    /**
     * Called when the player has not come back. Whatever is being played is
     * finished for them, in the way that risks nothing more, and written down.
     */
    suspend fun abandoned() {}
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

internal fun refusal(message: String, code: String? = null): String =
    wireJson.encodeToString(Refusal.serializer(), Refusal(message = message, code = code))

/** The close code of a connection whose table has been opened somewhere else. In the range left to applications. */
const val REPLACED: Short = 4001

/** The close code of a connection to a table that is not there: one opened by invitation and since cleared away. */
const val NO_TABLE: Short = 4004

/** Serves a game at [path], sitting each player at a table of their own that waits for them if they drop. */
fun Route.gameSocket(path: String, players: Players, tables: Tables, connect: (Send, PlayerSession) -> GameConnection) =
    seatSocket(path, players, tables) { connect }

/**
 * Serves every shared table of a game at [path], which ends in the table's
 * own name as `{table}`. [find] says which table that is, or that there is no
 * such table, and [connect] sits the player down at it.
 */
fun <R : Any> Route.sharedSocket(
    path: String,
    players: Players,
    tables: Tables,
    find: (String) -> R?,
    connect: (R, Send, PlayerSession) -> GameConnection,
) = seatSocket(path, players, tables) { call ->
    call.parameters["table"]?.let(find)?.let { table -> { send, session -> connect(table, send, session) } }
}

/**
 * What every game's socket does: hears who the player is, and sits them at
 * whatever [connectFor] says this address leads to. Null is an address that
 * leads nowhere.
 */
private fun Route.seatSocket(
    path: String,
    players: Players,
    tables: Tables,
    connectFor: (ApplicationCall) -> ((Send, PlayerSession) -> GameConnection)?,
) {
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

        val connect = connectFor(call)
        if (connect == null) {
            // Said after the welcome, so the client knows it is the table that is missing and not the player.
            send(refusal("This table is not open any more", code = "no_table"))
            close(CloseReason(NO_TABLE, "no table"))
            return@webSocket
        }

        // The table is the player's at this game, whichever connection they reach it by.
        val connection = coroutineContext.job
        val seat = tables.sit(
            key = "${player.id}:${call.request.path()}",
            connection = connection,
            send = send,
            // The close code says why, for a client that missed being told in words.
            dismiss = { close(CloseReason(REPLACED, "replaced")) },
        ) { toPlayer -> connect(toPlayer, session) }
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue

                try {
                    seat.received(frame.readText())
                } catch (problem: SerializationException) {
                    send(refusal("That message could not be read"))
                } catch (problem: IllegalArgumentException) {
                    send(refusal(problem.message ?: "That is not allowed"))
                } catch (problem: IllegalStateException) {
                    send(refusal(problem.message ?: "That is not possible right now"))
                }
            }
        } finally {
            seat.leave(connection)
        }
    }
}

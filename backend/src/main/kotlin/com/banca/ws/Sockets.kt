package com.banca.ws

import com.banca.Allowance
import com.banca.Limit
import com.banca.players.Players
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.hours
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** How many tables one player may open for their friends: a handful at a time, and a few dozen in a day. */
fun inviteAllowance() = Allowance(Limit(5, 10.minutes), Limit(30, 24.hours))

/** Every game the server offers, each at its own address. */
fun Application.configureGameSockets(
    players: Players,
    poker: TableSocketConfig = TableSocketConfig(),
    blackjack: BlackjackSocketConfig = BlackjackSocketConfig(),
    roulette: RouletteSocketConfig = RouletteSocketConfig(),
    rooms: RoomsConfig = RoomsConfig(),
    blackjackTables: BlackjackTablesConfig = BlackjackTablesConfig(),
    pokerTables: PokerTablesConfig = PokerTablesConfig(),
    /** Each player's share of the model behind the coach, which is one allowance for everyone. */
    coaching: CoachAllowance = CoachAllowance(),
    /** How many tables one player may open for their friends. Each is cheap, and none is free. */
    invites: Allowance = inviteAllowance(),
    /** How long a table waits for a player who has dropped before it is cleared away. */
    keepTablesFor: Duration = 3.minutes,
) {
    install(WebSockets)

    // Tables live as long as the server does, not as long as a connection.
    val tables = Tables(scope = this, keepFor = keepTablesFor)

    routing {
        gameSocket("/ws/table", players, tables) { send, session -> PokerConnection(poker, send, session, coaching.poker(poker.coach, session)) }
        gameSocket("/ws/blackjack", players, tables) { send, session -> BlackjackConnection(blackjack, send, session, coaching.blackjack(blackjack.advisor, session)) }
        gameSocket("/ws/roulette", players, tables) { send, session -> RouletteConnection(roulette, send, session, coaching.roulette(roulette.analyst, session)) }

        // The shared rooms, which exist whether or not anyone is in them, and
        // beside them the ones players open for their own company.
        val scope = this@configureGameSockets

        val listedRooms = rooms.rooms.map { spec -> RouletteRoom(spec, scope, rooms.timings, rooms.random()) }.associateBy { it.id }
        val invitedRooms = Invites({ spec -> RouletteRoom(spec, scope, rooms.timings, rooms.random()) }, { it.summary().players > 0 })
        sharedSocket("/ws/roulette/rooms/{table}", players, tables, find = { listedRooms[it] ?: invitedRooms.find(it) }) { room, send, session ->
            RoomSeat(room, coaching.roulette(rooms.analyst, session), send, session)
        }
        get("/roulette/rooms") {
            call.respond(listedRooms.values.map { it.summary() })
        }
        post("/roulette/rooms") { call.openInvite(players, invites, invitedRooms, "room") }

        val listedBlackjack = blackjackTables.tables.map { spec -> BlackjackRoom(spec, blackjackTables, scope) }.associateBy { it.id }
        val invitedBlackjack = Invites({ spec -> BlackjackRoom(spec, blackjackTables, scope) }, { it.summary().players > 0 })
        sharedSocket("/ws/blackjack/tables/{table}", players, tables, find = { listedBlackjack[it] ?: invitedBlackjack.find(it) }) { table, send, session ->
            BlackjackSeat(table, coaching.blackjack(blackjackTables.advisor, session), send, session)
        }
        get("/blackjack/tables") {
            call.respond(listedBlackjack.values.map { it.summary() })
        }
        post("/blackjack/tables") { call.openInvite(players, invites, invitedBlackjack, "table") }

        val listedPoker = pokerTables.tables.map { spec -> PokerRoom(spec, pokerTables, scope) }.associateBy { it.id }
        val invitedPoker = Invites({ spec -> PokerRoom(spec, pokerTables, scope) }, { it.summary().players > 0 })
        sharedSocket("/ws/poker/tables/{table}", players, tables, find = { listedPoker[it] ?: invitedPoker.find(it) }) { table, send, session ->
            PokerSeat(table, coaching.poker(pokerTables.coach, session), send, session)
        }
        get("/poker/tables") {
            call.respond(listedPoker.values.map { it.summary() })
        }
        post("/poker/tables") { call.openInvite(players, invites, invitedPoker, "table") }
    }
}

/** A table just opened for a player's own company: where it is, and what it is called. */
@Serializable
data class OpenedTable(val id: String, val name: String)

@Serializable
private data class NotOpened(val message: String)

/**
 * Opens a table for the caller to invite others to, named after them. The
 * caller must be a player the server knows, and may open only so many.
 */
private suspend fun ApplicationCall.openInvite(players: Players, allowance: Allowance, tables: Invites<*>, kind: String) {
    val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim().orEmpty()
    val player = players.authenticate(token)
        ?: return respond(HttpStatusCode.Unauthorized, NotOpened("This player is not known here"))

    if (!allowance.take(player.id.toString())) {
        return respond(HttpStatusCode.TooManyRequests, NotOpened("You have opened a lot of tables. Use one of those, or try again in a while."))
    }
    val spec = tables.open("${player.name}'s $kind")
        ?: return respond(HttpStatusCode.ServiceUnavailable, NotOpened("The house is full just now. Try again in a little while."))

    respond(HttpStatusCode.Created, OpenedTable(spec.id, spec.name))
}

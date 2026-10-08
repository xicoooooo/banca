package com.banca.ws

import com.banca.Allowance
import com.banca.Limit
import com.banca.players.Players
import com.banca.players.PracticePurse
import com.banca.players.callerAddress
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.serialization.SerializationException
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

/** How many table codes one address may try: room to mistype one a few times, and no use for guessing. */
fun lookupAllowance() = Allowance(Limit(20, 10.minutes), Limit(100, 24.hours))

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
    /** How many table codes one address may try. Enough for typing one wrong a few times, and no use for guessing. */
    lookups: Allowance = lookupAllowance(),
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
            RoomSeat(room, coaching.roulette(rooms.analyst, session), send, room.chipsFor(session))
        }
        get("/roulette/rooms") {
            call.respond(listedRooms.values.map { it.summary() })
        }
        post("/roulette/rooms") { call.openInvite(players, invites, invitedRooms, "room", seats = 2..8, usual = 8) }

        val listedBlackjack = blackjackTables.tables.map { spec -> BlackjackRoom(spec, blackjackTables, scope) }.associateBy { it.id }
        val invitedBlackjack = Invites({ spec -> BlackjackRoom(spec, blackjackTables, scope) }, { it.summary().players > 0 })
        sharedSocket("/ws/blackjack/tables/{table}", players, tables, find = { listedBlackjack[it] ?: invitedBlackjack.find(it) }) { table, send, session ->
            BlackjackSeat(table, coaching.blackjack(blackjackTables.advisor, session), send, table.chipsFor(session))
        }
        get("/blackjack/tables") {
            call.respond(listedBlackjack.values.map { it.summary() })
        }
        post("/blackjack/tables") { call.openInvite(players, invites, invitedBlackjack, "table", seats = 2..blackjackTables.seats, usual = blackjackTables.seats) }

        val listedPoker = pokerTables.tables.map { spec -> PokerRoom(spec, pokerTables, scope) }.associateBy { it.id }
        val invitedPoker = Invites({ spec -> PokerRoom(spec, pokerTables, scope) }, { it.summary().players > 0 })
        sharedSocket("/ws/poker/tables/{table}", players, tables, find = { listedPoker[it] ?: invitedPoker.find(it) }) { table, send, session ->
            PokerSeat(table, coaching.poker(pokerTables.coach, session), send, table.chipsFor(session))
        }
        get("/poker/tables") {
            call.respond(listedPoker.values.map { it.summary() })
        }
        // A private table can be reached by its code alone, typed in by someone who was told it.
        // The code says which game it is, so the player need not.
        get("/tables/{code}") {
            if (!lookups.take(call.callerAddress())) {
                return@get call.respond(HttpStatusCode.TooManyRequests, NotOpened("Too many codes tried from here. Wait a little and try again."))
            }
            val code = call.parameters["code"].orEmpty().lowercase().filter { it.isLetterOrDigit() }
            val found = invitedPoker.find(code)?.let { FoundTable("poker", code, it.name) }
                ?: invitedBlackjack.find(code)?.let { FoundTable("blackjack", code, it.name) }
                ?: invitedRooms.find(code)?.let { FoundTable("roulette", code, it.name) }
                ?: return@get call.respond(HttpStatusCode.NotFound, NotOpened("There is no table with that code. Check it, or ask for a new one."))
            call.respond(found)
        }

        post("/poker/tables") { call.openInvite(players, invites, invitedPoker, "table", seats = 2..pokerTables.seats, usual = pokerTables.seats, mayLeaveBancaOut = true) }
    }
}

/** A table just opened for a player's own company: where it is, and what it is called. */
@Serializable
data class OpenedTable(val id: String, val name: String)

/** A private table found by its code: which game it is, so the client knows where to go. */
@Serializable
data class FoundTable(val game: String, val id: String, val name: String)

@Serializable
private data class NotOpened(val message: String)

/** What a host may choose for the table they open. Anything left out is the usual. */
@Serializable
private data class Wanted(val seats: Int? = null, val banca: Boolean = true, val turns: String = "normal", val chips: String = "real")

/**
 * Opens a table for the caller to invite others to, named after them and set
 * up as they asked. The caller must be a player the server knows, and may open
 * only so many. [seats] is how many the game allows at a table, Banca
 * included where Banca sits.
 */
private suspend fun ApplicationCall.openInvite(
    players: Players,
    allowance: Allowance,
    tables: Invites<*>,
    kind: String,
    seats: IntRange,
    usual: Int,
    mayLeaveBancaOut: Boolean = false,
) {
    val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim().orEmpty()
    val player = players.authenticate(token)
        ?: return respond(HttpStatusCode.Unauthorized, NotOpened("This player is not known here"))

    val body = receiveText()
    val wanted = try {
        if (body.isBlank()) Wanted() else wireJson.decodeFromString<Wanted>(body)
    } catch (unreadable: SerializationException) {
        return respond(HttpStatusCode.BadRequest, NotOpened("That table could not be understood"))
    }
    val size = wanted.seats ?: usual
    if (size !in seats) return respond(HttpStatusCode.BadRequest, NotOpened("A $kind here seats between ${seats.first} and ${seats.last}"))
    if (wanted.turns !in setOf("normal", "long")) return respond(HttpStatusCode.BadRequest, NotOpened("Turns are normal or long"))
    if (wanted.chips !in setOf("real", "practice")) return respond(HttpStatusCode.BadRequest, NotOpened("Chips are real or practice"))
    if (!wanted.banca && !mayLeaveBancaOut) return respond(HttpStatusCode.BadRequest, NotOpened("Banca is only a player at poker"))

    if (!allowance.take(player.id.toString())) {
        return respond(HttpStatusCode.TooManyRequests, NotOpened("You have opened a lot of tables. Use one of those, or try again in a while."))
    }
    val options = TableOptions(
        seats = size,
        banca = wanted.banca,
        longTurns = wanted.turns == "long",
        host = player.id,
        practice = if (wanted.chips == "practice") PracticePurse() else null,
    )
    val spec = tables.open("${player.name}'s $kind", options)
        ?: return respond(HttpStatusCode.ServiceUnavailable, NotOpened("The house is full just now. Try again in a little while."))

    respond(HttpStatusCode.Created, OpenedTable(spec.id, spec.name))
}

package com.banca.ws

import com.banca.players.Players
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

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

        // The shared rooms, which exist whether or not anyone is in them.
        val shared = rooms.rooms.map { spec -> RouletteRoom(spec, scope = this@configureGameSockets, rooms.timings, rooms.random()) }
        for (room in shared) {
            gameSocket("/ws/roulette/rooms/${room.id}", players, tables) { send, session -> RoomSeat(room, coaching.roulette(rooms.analyst, session), send, session) }
        }
        get("/roulette/rooms") {
            call.respond(shared.map { it.summary() })
        }

        val sharedBlackjack = blackjackTables.tables.map { spec -> BlackjackRoom(spec, blackjackTables, scope = this@configureGameSockets) }
        for (table in sharedBlackjack) {
            gameSocket("/ws/blackjack/tables/${table.id}", players, tables) { send, session ->
                BlackjackSeat(table, coaching.blackjack(blackjackTables.advisor, session), send, session)
            }
        }
        get("/blackjack/tables") {
            call.respond(sharedBlackjack.map { it.summary() })
        }

        val sharedPoker = pokerTables.tables.map { spec -> PokerRoom(spec, pokerTables, scope = this@configureGameSockets) }
        for (table in sharedPoker) {
            gameSocket("/ws/poker/tables/${table.id}", players, tables) { send, session -> PokerSeat(table, coaching.poker(pokerTables.coach, session), send, session) }
        }
        get("/poker/tables") {
            call.respond(sharedPoker.map { it.summary() })
        }
    }
}

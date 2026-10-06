package com.banca.ws

import com.banca.players.Players
import io.ktor.server.application.Application
import io.ktor.server.application.install
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
    /** How long a table waits for a player who has dropped before it is cleared away. */
    keepTablesFor: Duration = 3.minutes,
) {
    install(WebSockets)

    // Tables live as long as the server does, not as long as a connection.
    val tables = Tables(scope = this, keepFor = keepTablesFor)

    routing {
        gameSocket("/ws/table", players, tables) { send, session -> PokerConnection(poker, send, session) }
        gameSocket("/ws/blackjack", players, tables) { send, session -> BlackjackConnection(blackjack, send, session) }
        gameSocket("/ws/roulette", players, tables) { send, session -> RouletteConnection(roulette, send, session) }
    }
}

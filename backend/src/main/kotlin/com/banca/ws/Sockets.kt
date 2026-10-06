package com.banca.ws

import com.banca.players.Players
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets

/** Every game the server offers, each at its own address. */
fun Application.configureGameSockets(
    players: Players,
    poker: TableSocketConfig = TableSocketConfig(),
    blackjack: BlackjackSocketConfig = BlackjackSocketConfig(),
) {
    install(WebSockets)

    routing {
        gameSocket("/ws/table", players) { send, session -> PokerConnection(poker, send, session) }
        gameSocket("/ws/blackjack", players) { send, session -> BlackjackConnection(blackjack, send, session) }
    }
}

package com.banca.ws

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets

/** Every game the server offers, each at its own address. */
fun Application.configureGameSockets(
    poker: TableSocketConfig = TableSocketConfig(),
    blackjack: BlackjackSocketConfig = BlackjackSocketConfig(),
) {
    install(WebSockets)

    routing {
        gameSocket("/ws/table") { send -> PokerConnection(poker, send) }
        gameSocket("/ws/blackjack") { send -> BlackjackConnection(blackjack, send) }
    }
}

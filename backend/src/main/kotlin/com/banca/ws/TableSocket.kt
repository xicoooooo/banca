package com.banca.ws

import com.banca.sessions.PassiveBot
import com.banca.sessions.PokerTable
import com.banca.sessions.SeatDriver
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val HUMAN_SEAT = 0
private const val OPPONENT_SEAT = 1

class TableSocketConfig(
    /** A pause before the opponent acts, so a person can follow the hand. */
    val opponentDelay: Duration = 700.milliseconds,
    val opponent: () -> SeatDriver = { PassiveBot() },
    val random: () -> Random = { Random.Default },
)

/**
 * One private heads-up table per connection: the person who connected against
 * a driven seat. Shared tables and identity arrive with the lobby.
 */
fun Application.configureTableSocket(config: TableSocketConfig = TableSocketConfig()) {
    install(WebSockets)

    routing {
        webSocket("/ws/table") {
            val table = PokerTable(
                names = mapOf(HUMAN_SEAT to "You", OPPONENT_SEAT to "Banca"),
                startingStack = 2_000,
                smallBlind = 10,
                bigBlind = 20,
                random = config.random(),
            )
            val opponent = config.opponent()

            suspend fun pushState() = send(ServerMessage.State(table.view(HUMAN_SEAT)))

            suspend fun playOpponentTurns() {
                while (table.actorSeat == OPPONENT_SEAT) {
                    delay(config.opponentDelay)
                    table.act(OPPONENT_SEAT, opponent.decide(table.view(OPPONENT_SEAT)))
                    pushState()
                }
            }

            table.startHand()
            pushState()
            playOpponentTurns()

            for (frame in incoming) {
                if (frame !is Frame.Text) continue

                // A bad message is the sender's problem and must never take the
                // table down with it.
                try {
                    when (val message = wireJson.decodeFromString<ClientMessage>(frame.readText())) {
                        is ClientMessage.Act -> table.act(HUMAN_SEAT, message.toAction())
                        is ClientMessage.NextHand -> table.startHand()
                    }
                    pushState()
                    playOpponentTurns()
                } catch (problem: SerializationException) {
                    send(ServerMessage.Error("That message could not be read"))
                } catch (problem: IllegalArgumentException) {
                    send(ServerMessage.Error(problem.message ?: "That is not allowed"))
                } catch (problem: IllegalStateException) {
                    send(ServerMessage.Error(problem.message ?: "That is not possible right now"))
                }
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.send(message: ServerMessage) =
    send(Frame.Text(wireJson.encodeToString(ServerMessage.serializer(), message)))

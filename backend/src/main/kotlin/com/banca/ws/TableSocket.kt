package com.banca.ws

import com.banca.games.poker.Action
import com.banca.sessions.PassiveBot
import com.banca.sessions.PokerTable
import com.banca.sessions.SeatDriver
import com.banca.sessions.TraceEvent
import kotlinx.coroutines.delay
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
 * A private heads-up poker table: the person who connected against a driven
 * seat. Shared tables and identity arrive with the lobby.
 */
class PokerConnection(private val config: TableSocketConfig, private val send: Send) : GameConnection {

    private val table = PokerTable(
        names = mapOf(HUMAN_SEAT to "You", OPPONENT_SEAT to "Banca"),
        startingStack = 2_000,
        smallBlind = 10,
        bigBlind = 20,
        random = config.random(),
    )
    private val opponent = config.opponent()
    private val reasoning = mutableListOf<TraceEvent>()

    override suspend fun opened() {
        table.startHand()
        pushState()
        playOpponentTurns()
    }

    override suspend fun received(text: String) {
        when (val message = wireJson.decodeFromString<ClientMessage>(text)) {
            is ClientMessage.Act -> table.act(HUMAN_SEAT, message.toAction())
            is ClientMessage.NextHand -> table.startHand()
        }
        pushState()
        playOpponentTurns()
    }

    private suspend fun pushState() {
        emit(ServerMessage.State(table.view(HUMAN_SEAT)))
        // What the opponent was thinking would give its hand away mid-hand,
        // so the detail is held back until nothing rides on it.
        if (table.isHandComplete && reasoning.isNotEmpty()) {
            emit(ServerMessage.Reveal(table.handNumber, reasoning.toList()))
            reasoning.clear()
        }
    }

    private suspend fun playOpponentTurns() {
        while (table.actorSeat == OPPONENT_SEAT) {
            delay(config.opponentDelay)
            val action = opponent.decide(table.view(OPPONENT_SEAT)) { event ->
                reasoning += event
                emit(ServerMessage.Trace(table.handNumber, event.withoutDetail()))
            }
            try {
                table.act(OPPONENT_SEAT, action)
            } catch (illegal: IllegalArgumentException) {
                // The engine has the last word on what a driver may do.
                val legal = table.view(OPPONENT_SEAT).legal
                table.act(OPPONENT_SEAT, if (legal?.canCheck == true) Action.Check else Action.Fold)
            }
            pushState()
        }
    }

    private suspend fun emit(message: ServerMessage) =
        send(wireJson.encodeToString(ServerMessage.serializer(), message))
}

package com.banca.ws

import com.banca.games.poker.Action
import com.banca.sessions.TableView
import com.banca.sessions.TraceEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The wire format is described for client authors in docs/protocol.md. */
val wireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
sealed interface ClientMessage {

    @Serializable
    @SerialName("act")
    data class Act(val action: String, val amount: Long? = null) : ClientMessage {
        fun toAction(): Action = when (action) {
            "fold" -> Action.Fold
            "check" -> Action.Check
            "call" -> Action.Call
            "bet" -> Action.Bet(requireNotNull(amount) { "A bet needs an amount" })
            "raise" -> Action.Raise(requireNotNull(amount) { "A raise needs an amount" })
            else -> throw IllegalArgumentException("Unknown action '$action'")
        }
    }

    @Serializable
    @SerialName("next_hand")
    data object NextHand : ClientMessage
}

@Serializable
sealed interface ServerMessage {

    @Serializable
    @SerialName("state")
    data class State(val view: TableView) : ServerMessage

    /** A step the opponent took while deciding, with nothing private in it. */
    @Serializable
    @SerialName("trace")
    data class Trace(val handNumber: Int, val event: TraceEvent) : ServerMessage

    /** The opponent's full reasoning for a hand, sent only once it is over. */
    @Serializable
    @SerialName("reveal")
    data class Reveal(val handNumber: Int, val events: List<TraceEvent>) : ServerMessage
}

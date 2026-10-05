package com.banca.ws

import com.banca.games.poker.Action
import com.banca.sessions.TableView
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

    @Serializable
    @SerialName("error")
    data class Error(val message: String) : ServerMessage
}

package com.banca.ws

import com.banca.agents.PokerAdvice
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

    /** Asks the coach what it would do with the decision in front of the player. */
    @Serializable
    @SerialName("advise")
    data object Advise : ClientMessage
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

    /** Something the opponent has to say about a hand, once it is over. */
    @Serializable
    @SerialName("remark")
    data class Remark(val handNumber: Int, val text: String) : ServerMessage

    /** One step the coach took. Nothing is hidden: the coach sees only what the player sees. */
    @Serializable
    @SerialName("coach_trace")
    data class CoachTrace(val handNumber: Int, val event: TraceEvent) : ServerMessage

    @Serializable
    @SerialName("advice")
    data class Advised(val handNumber: Int, val advice: PokerAdvice) : ServerMessage
}

/** What any poker table at Banca holds to, private or shared. */
object PokerHouse {
    /** The decision a view is waiting on, as something two views of the same moment agree on. */
    fun decisionIn(view: TableView): String {
        val me = view.players.firstOrNull { it.seat == view.yourSeat }
        return "${view.handNumber}:${view.street}:${view.pot}:${me?.committed}:${view.actorSeat}"
    }
}

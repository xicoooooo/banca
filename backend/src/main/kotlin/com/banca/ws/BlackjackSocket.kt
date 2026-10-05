package com.banca.ws

import com.banca.games.blackjack.BlackjackAction
import com.banca.games.blackjack.Rules
import com.banca.sessions.BlackjackTable
import com.banca.sessions.BlackjackView
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
sealed interface BlackjackClientMessage {

    @Serializable
    @SerialName("bet")
    data class Bet(val amount: Long) : BlackjackClientMessage

    @Serializable
    @SerialName("act")
    data class Act(val action: String) : BlackjackClientMessage {
        fun toAction(): BlackjackAction = when (action) {
            "hit" -> BlackjackAction.Hit
            "stand" -> BlackjackAction.Stand
            "double" -> BlackjackAction.Double
            "split" -> BlackjackAction.Split
            "insure" -> BlackjackAction.Insure
            "decline_insurance" -> BlackjackAction.DeclineInsurance
            else -> throw IllegalArgumentException("Unknown action '$action'")
        }
    }
}

@Serializable
sealed interface BlackjackServerMessage {

    @Serializable
    @SerialName("state")
    data class State(val view: BlackjackView) : BlackjackServerMessage
}

class BlackjackSocketConfig(
    val rules: Rules = Rules(),
    val random: () -> Random = { Random.Default },
)

/** A private blackjack table: the person who connected against the house. */
class BlackjackConnection(config: BlackjackSocketConfig, private val send: Send) : GameConnection {

    private val table = BlackjackTable(
        startingStack = 2_000,
        minBet = 10,
        maxBet = 500,
        rules = config.rules,
        random = config.random(),
    )

    // Unlike poker, nothing is dealt until the player has put chips down.
    override suspend fun opened() = pushState()

    override suspend fun received(text: String) {
        when (val message = wireJson.decodeFromString<BlackjackClientMessage>(text)) {
            is BlackjackClientMessage.Bet -> table.bet(message.amount)
            is BlackjackClientMessage.Act -> table.act(message.toAction())
        }
        pushState()
    }

    private suspend fun pushState() =
        send(wireJson.encodeToString(BlackjackServerMessage.serializer(), BlackjackServerMessage.State(table.view())))
}

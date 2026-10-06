package com.banca.ws

import com.banca.games.blackjack.BlackjackAction
import com.banca.games.blackjack.Rules
import com.banca.games.blackjack.valueOf
import com.banca.games.cards.Rank
import com.banca.players.FinishedRound
import com.banca.players.Funding
import com.banca.players.Game
import com.banca.players.PlayerSession
import com.banca.players.RoundOutcome
import com.banca.sessions.BlackjackTable
import com.banca.sessions.BlackjackView
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
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

/**
 * A private blackjack table: the person who connected against the house, betting
 * from their bankroll. The balance is read before every bet and each round is
 * written to the ledger as it ends, so the table never holds chips of its own.
 */
class BlackjackConnection(
    private val config: BlackjackSocketConfig,
    private val send: Send,
    private val session: PlayerSession,
) : GameConnection {

    private val tableId = UUID.randomUUID().toString()
    private lateinit var table: BlackjackTable
    private var recordedRound = 0

    // Unlike poker, nothing is dealt until the player has put chips down.
    override suspend fun opened() {
        val funding = session.fund(MIN_BET)
        table = BlackjackTable(
            stack = funding.balance,
            minBet = MIN_BET,
            maxBet = MAX_BET,
            rules = config.rules,
            random = config.random(),
        )
        pushState()
        tell(funding)
    }

    /** Says what the house did, or would not do, for a player short of chips. */
    private suspend fun tell(funding: Funding) {
        when (funding) {
            is Funding.Staked -> send(stakedNotice(funding))
            is Funding.Broke -> send(brokeNotice(funding))
            is Funding.Ready -> Unit
        }
    }

    override suspend fun received(text: String) {
        when (val message = wireJson.decodeFromString<BlackjackClientMessage>(text)) {
            is BlackjackClientMessage.Bet -> {
                check(table.isBetting) { "The round is still being played" }
                // The bankroll may have moved since this table last looked: at
                // another table, or by a reward claimed in the meantime.
                val funding = session.fund(MIN_BET)
                table.fund(funding.balance, refilled = false)
                if (funding !is Funding.Ready) {
                    pushState()
                    tell(funding)
                    if (funding is Funding.Broke) return
                }
                table.bet(message.amount)
            }
            is BlackjackClientMessage.Act -> table.act(message.toAction())
        }
        val afterRound = record()
        pushState()
        // Only being unable to go on needs saying here; a stake is part of the result.
        if (afterRound is Funding.Broke) tell(afterRound)
    }

    /**
     * Writes a round to the ledger as it settles, once, and tells the table
     * what the player now has. Returns how they stand for the next round, or
     * null when no round has just ended.
     */
    private suspend fun record(): Funding? {
        val round = table.current?.takeIf { it.isSettled } ?: return null
        if (recordedRound == table.roundNumber) return null
        recordedRound = table.roundNumber

        val result = round.result ?: return null
        session.settle(
            FinishedRound(
                game = Game.BLACKJACK,
                tableId = tableId,
                staked = round.hands.sumOf { it.bet } + round.insurance,
                net = result.net,
                outcome = when {
                    result.net > 0 -> RoundOutcome.WIN
                    result.net < 0 -> RoundOutcome.LOSS
                    else -> RoundOutcome.PUSH
                },
                detail = buildJsonObject {
                    put("hands", round.hands.size)
                    put("total", round.hands.first().value.total)
                    put("dealerTotal", valueOf(round.dealer).total)
                    put("natural", round.hands.any { it.isBlackjack })
                    put("busts", round.hands.count { it.isBust })
                    put("doubled", round.hands.count { it.doubled })
                    put("split", round.hands.size > 1)
                    put("insuranceOffered", round.dealerUpCard.rank == Rank.ACE)
                    put("insured", round.insurance > 0)
                    putJsonArray("outcomes") { result.hands.forEach { add(JsonPrimitive(it.outcome.name.lowercase())) } }
                },
            ),
        )

        // Chips cannot be bought, so a player left unable to bet is staked by
        // the house, if it has not done so too recently.
        val funding = session.fund(MIN_BET)
        table.fund(funding.balance, refilled = funding is Funding.Staked)
        return funding
    }

    private suspend fun pushState() =
        send(wireJson.encodeToString(BlackjackServerMessage.serializer(), BlackjackServerMessage.State(table.view())))

    private companion object {
        const val MIN_BET = 10L
        const val MAX_BET = 500L
    }
}

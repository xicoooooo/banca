package com.banca.ws

import com.banca.agents.Advice
import com.banca.agents.BlackjackAdvisor
import com.banca.agents.BlackjackTools
import com.banca.agents.BookAdvisor
import com.banca.games.blackjack.BlackjackAction
import com.banca.games.blackjack.Strategy
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
import com.banca.sessions.TraceEvent
import org.slf4j.LoggerFactory
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

    /** Asks the coach what it would do with the decision in front of the player. */
    @Serializable
    @SerialName("advise")
    data object Advise : BlackjackClientMessage

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

    /** One step the coach took. Nothing is hidden from the player here: the coach sees only what they see. */
    @Serializable
    @SerialName("trace")
    data class Trace(val roundNumber: Int, val event: TraceEvent) : BlackjackServerMessage

    @Serializable
    @SerialName("advice")
    data class Advised(val roundNumber: Int, val hand: Int?, val advice: Advice) : BlackjackServerMessage
}

class BlackjackSocketConfig(
    val rules: Rules = Rules(),
    val random: () -> Random = { Random.Default },
    /** Who answers when the player asks what to do. The book, unless a model is given. */
    val advisor: BlackjackAdvisor = BookAdvisor(rules),
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

    private val log = LoggerFactory.getLogger(BlackjackConnection::class.java)
    private val tableId = UUID.randomUUID().toString()
    private lateinit var table: BlackjackTable
    private var recordedRound = 0

    private val strategy = Strategy(config.rules)

    /** The coach, asked about one decision at a time. */
    private val coaching = Consultation<Advice>()

    /** How the player's decisions this round compare with the best play, for their record. */
    private var decisions = 0
    private var byTheBook = 0
    private var advisedDecisions = 0
    private var followedAdvice = 0

    // Unlike poker, nothing is dealt until the player has put chips down.
    override suspend fun attached() {
        // Coming back to a table already set up, the round is as it was left.
        if (::table.isInitialized) {
            // Between rounds the bankroll may have moved while the player was away.
            if (table.isBetting) table.restock(session.balance())
            pushState()
            return
        }

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
                decisions = 0
                byTheBook = 0
                advisedDecisions = 0
                followedAdvice = 0
            }
            is BlackjackClientMessage.Act -> {
                val action = message.toAction()
                val before = table.view()
                table.act(action)
                // Advice for a decision that has been made is of no use to anyone.
                coaching.cancel()
                note(before, action)
            }
            is BlackjackClientMessage.Advise -> {
                advise()
                return
            }
        }
        val afterRound = record()
        pushState()
        // Only being unable to go on needs saying here; a stake is part of the result.
        if (afterRound is Funding.Broke) tell(afterRound)
    }

    /** The decision a view is waiting on, as something two views of the same moment agree on. */
    private fun decisionIn(view: BlackjackView): String =
        "${view.roundNumber}:${view.phase}:${view.activeHand}:${view.hands.getOrNull(view.activeHand ?: 0)?.cards}"

    /** Counts a decision the player has just made: whether it was the best play, and whether it was the coach's. */
    private fun note(before: BlackjackView, action: BlackjackAction) {
        val tools = runCatching { BlackjackTools(before, strategy) }.getOrNull() ?: return
        decisions++
        if (tools.isSound(action)) byTheBook++

        val given = coaching.answerTo(decisionIn(before)) ?: return
        advisedDecisions++
        if (given.action == BlackjackTools.nameOf(action)) followedAdvice++
    }

    /**
     * Sets the coach to work on the decision in front of the player. It answers
     * in its own time, on its own coroutine, so the player is never kept from
     * acting while it thinks; if they act first, it is stopped.
     */
    private suspend fun advise() {
        val view = table.view()
        check(view.phase == "player" || view.phase == "insurance") { "There is nothing to advise on right now" }

        coaching.ask(
            question = decisionIn(view),
            work = { config.advisor.advise(view) { event -> emit(BlackjackServerMessage.Trace(view.roundNumber, event)) } },
            deliver = { advice -> emit(BlackjackServerMessage.Advised(view.roundNumber, view.activeHand, advice)) },
            failed = { failure ->
                // An advisor that breaks must not take the table with it.
                log.warn("The coach failed", failure)
                send(refusal("The coach could not be reached. Try again."))
            },
        )
    }

    override fun detached() = coaching.cancel()

    /**
     * The player left and did not come back. A round still being played is
     * finished without risking another chip: insurance is declined and every
     * hand stands. It is then written down like any other.
     */
    override suspend fun abandoned() {
        if (!::table.isInitialized) return
        var guard = 0
        while (!table.isBetting && guard++ < 20) {
            val phase = table.view().phase
            table.act(if (phase == "insurance") BlackjackAction.DeclineInsurance else BlackjackAction.Stand)
        }
        record()
    }

    private suspend fun emit(message: BlackjackServerMessage) =
        send(wireJson.encodeToString(BlackjackServerMessage.serializer(), message))

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
                    put("decisions", decisions)
                    put("byTheBook", byTheBook)
                    put("advised", advisedDecisions)
                    put("followedAdvice", followedAdvice)
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

    private suspend fun pushState() = emit(BlackjackServerMessage.State(table.view()))

    private companion object {
        const val MIN_BET = 10L
        const val MAX_BET = 500L
    }
}

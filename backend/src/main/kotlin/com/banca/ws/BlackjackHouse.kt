package com.banca.ws

import com.banca.agents.Advice
import com.banca.agents.BlackjackTools
import com.banca.games.blackjack.BlackjackAction
import com.banca.games.blackjack.Round
import com.banca.games.blackjack.Strategy
import com.banca.games.blackjack.valueOf
import com.banca.games.cards.Rank
import com.banca.players.FinishedRound
import com.banca.players.Game
import com.banca.players.RoundOutcome
import com.banca.sessions.BlackjackView
import com.banca.sessions.DecisionReview
import com.banca.sessions.RoundReview
import kotlin.math.round
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * How a player's decisions in one round compare with the best play, and with
 * what the coach advised when it was asked. Kept for the player's record.
 */
class DecisionTally(private val strategy: Strategy) {
    var decisions = 0
        private set
    var byTheBook = 0
        private set
    var advised = 0
        private set
    var followedAdvice = 0
        private set

    private val reviews = mutableListOf<DecisionReview>()

    /** The round's decisions graded one by one, or null when the player had none to make. */
    fun review(): RoundReview? = reviews.takeIf { it.isNotEmpty() }?.let { graded ->
        RoundReview(decisions = graded.toList(), sound = byTheBook, cost = cost)
    }

    /** Chips the round's decisions gave up on average, against the best play each time. */
    val cost: Double get() = round(reviews.sumOf { it.cost } * 10) / 10

    fun reset() {
        reviews.clear()
        decisions = 0
        byTheBook = 0
        advised = 0
        followedAdvice = 0
    }

    /** Counts a decision just made from [before], and whether it was what the coach had said, if it had been asked. */
    fun note(before: BlackjackView, action: BlackjackAction, advice: Advice?) {
        val tools = runCatching { BlackjackTools(before, strategy) }.getOrNull() ?: return
        val graded = runCatching { tools.review(action, advice) }.getOrNull() ?: return
        reviews += graded
        decisions++
        if (graded.verdict == "best") byTheBook++

        if (advice == null) return
        advised++
        if (advice.action == BlackjackTools.nameOf(action)) followedAdvice++
    }
}

/** What any blackjack table at Banca holds to, private or shared. */
object BlackjackHouse {
    const val MIN_BET = 10L
    const val MAX_BET = 500L

    /** The decision a view is waiting on, as something two views of the same moment agree on. */
    fun decisionIn(view: BlackjackView): String =
        "${view.roundNumber}:${view.phase}:${view.activeHand}:${view.hands.getOrNull(view.activeHand ?: 0)?.cards}"

    /** A settled round as it is written to the player's record. */
    fun finished(round: Round, tableId: String, tally: DecisionTally): FinishedRound {
        val result = round.result ?: error("Only a settled round can be written down")
        return FinishedRound(
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
                put("decisions", tally.decisions)
                put("byTheBook", tally.byTheBook)
                put("givenUp", tally.cost)
                put("advised", tally.advised)
                put("followedAdvice", tally.followedAdvice)
                putJsonArray("outcomes") { result.hands.forEach { add(JsonPrimitive(it.outcome.name.lowercase())) } }
            },
        )
    }
}

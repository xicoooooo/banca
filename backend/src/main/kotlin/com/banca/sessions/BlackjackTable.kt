package com.banca.sessions

import com.banca.games.blackjack.BlackjackAction
import com.banca.games.blackjack.Phase
import com.banca.games.blackjack.PlayerHand
import com.banca.games.blackjack.Round
import com.banca.games.blackjack.Rules
import com.banca.games.blackjack.valueOf
import com.banca.games.cards.Card
import com.banca.games.cards.Deck
import kotlin.random.Random

/**
 * A live blackjack table for one player: their chips between rounds, the shoe
 * the cards come from, and the round in progress.
 *
 * Not thread safe. Each table is driven by a single coroutine.
 */
class BlackjackTable(
    private val startingStack: Long,
    private val minBet: Long,
    private val maxBet: Long,
    private val rules: Rules,
    private val random: Random,
) {
    private var stack: Long = startingStack
    private var shoe: List<Card> = emptyList()
    private var round: Round? = null
    private var lastBet: Long? = null
    private var refilled = false

    var roundNumber: Int = 0
        private set

    val isBetting: Boolean get() = round?.isSettled ?: true

    fun bet(amount: Long) {
        check(isBetting) { "The round is still being played" }
        require(amount in minBet..maxBet) { "A bet must be between $minBet and $maxBet" }
        require(amount <= stack) { "A bet of $amount is more than the $stack you have" }

        // A shoe is played most of the way down, then shuffled afresh.
        if (shoe.size < Round.CARDS_NEEDED) shoe = (1..rules.decks).flatMap { Deck.full() }.shuffled(random)

        refilled = false
        lastBet = amount
        roundNumber++
        keep(Round.deal(bet = amount, stack = stack, shoe = shoe, rules = rules))
    }

    fun act(action: BlackjackAction) {
        val current = round ?: error("No round has been dealt")
        check(!current.isSettled) { "The round is over, place a bet to play again" }
        keep(current.act(action))
    }

    private fun keep(next: Round) {
        round = next
        shoe = next.shoe
        stack = next.stack

        // Until the wallet exists, a player who cannot make the smallest bet
        // is simply staked again.
        if (next.isSettled && stack < minBet) {
            stack = startingStack
            refilled = true
        }
    }

    fun view(): BlackjackView {
        val current = round
        val settled = current?.isSettled ?: false
        val legal = current?.takeUnless { it.isSettled }?.legalActions()

        return BlackjackView(
            roundNumber = roundNumber,
            phase = when {
                current == null -> "betting"
                else -> current.phase.name.lowercase()
            },
            stack = stack,
            minBet = minBet,
            maxBet = maxBet,
            lastBet = lastBet,
            dealer = current?.let { dealerView(it) },
            hands = current?.hands.orEmpty().mapIndexed { index, hand -> handView(current!!, index, hand) },
            activeHand = current?.takeIf { it.phase == Phase.PLAYER }?.active,
            legal = BlackjackLegalView(
                bet = current == null || settled,
                hit = legal?.hit ?: false,
                stand = legal?.stand ?: false,
                double = legal?.double ?: false,
                split = legal?.split ?: false,
                insurance = legal?.insurance ?: false,
            ),
            insuranceCost = current?.takeIf { it.phase == Phase.INSURANCE }?.insuranceCost ?: 0,
            result = current?.result?.let {
                BlackjackResultView(net = it.net, insuranceReturned = it.insuranceReturned, refilled = refilled)
            },
        )
    }

    /** The hole card stays face down until the round is settled. */
    private fun dealerView(round: Round): DealerView {
        val shown = if (round.isSettled) round.dealer else round.dealer.take(1)
        val value = valueOf(shown)
        return DealerView(
            cards = round.dealer.mapIndexed { index, card -> if (index < shown.size) card.toString() else null },
            total = value.total,
            soft = value.soft,
        )
    }

    private fun handView(round: Round, index: Int, hand: PlayerHand): BlackjackHandView {
        val result = round.result?.hands?.get(index)
        return BlackjackHandView(
            cards = hand.cards.map { it.toString() },
            bet = hand.bet,
            total = hand.value.total,
            soft = hand.value.soft,
            status = when {
                hand.isBust -> "bust"
                hand.isBlackjack -> "blackjack"
                hand.doubled -> "doubled"
                hand.isFinished -> "stood"
                round.phase == Phase.PLAYER && index == round.active -> "playing"
                else -> "waiting"
            },
            outcome = result?.outcome?.name?.lowercase(),
            returned = result?.returned,
        )
    }
}

package com.banca.sessions

import com.banca.games.blackjack.BlackjackAction
import com.banca.games.blackjack.Round
import com.banca.games.blackjack.Rules
import com.banca.games.cards.Card
import com.banca.games.cards.Deck
import kotlin.random.Random

/**
 * A live blackjack table for one player: the shoe the cards come from and the
 * round in progress. The player's chips are their bankroll, held elsewhere;
 * the table is told what they have and reports what each round did to it.
 *
 * Not thread safe. Each table is driven by a single coroutine.
 */
class BlackjackTable(
    stack: Long,
    val minBet: Long,
    private val maxBet: Long,
    private val rules: Rules,
    private val random: Random,
) {
    private var stack: Long = stack
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
    }

    /** The round in progress or just finished, for whoever keeps the record. */
    val current: Round? get() = round

    /**
     * Sets what the player has to bet with, between rounds. The chips are the
     * bankroll's, not the table's, so the table is told rather than deciding.
     * [refilled] says the player had run out and has been staked again.
     */
    fun fund(stack: Long, refilled: Boolean) {
        check(isBetting) { "Chips cannot change hands mid-round" }
        this.stack = stack
        this.refilled = refilled
    }

    /** Brings the stack up to date between rounds without changing what the last round's result says. */
    fun restock(stack: Long) {
        check(isBetting) { "Chips cannot change hands mid-round" }
        this.stack = stack
    }

    fun view(): BlackjackView = blackjackViewOf(round, roundNumber, stack, minBet, maxBet, lastBet, refilled)
}

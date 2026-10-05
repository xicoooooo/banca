package com.banca.agents

import com.banca.games.cards.Card
import com.banca.games.cards.Deck
import com.banca.games.poker.HandEvaluator
import kotlin.random.Random

object PokerMath {

    /**
     * The share of the pot this hand wins on average against [opponents]
     * random hands, estimated by dealing out the unknown cards many times.
     * A tie counts as an equal share.
     */
    fun equity(
        hole: List<Card>,
        board: List<Card>,
        opponents: Int,
        iterations: Int,
        random: Random,
    ): Double {
        require(hole.size == 2) { "Hole cards are two cards" }
        require(board.size <= 5) { "A board is at most five cards" }
        require(opponents >= 1) { "Equity needs at least one opponent" }
        require(iterations > 0) { "Need at least one iteration" }

        val unseen = (Deck.full() - hole.toSet() - board.toSet()).toMutableList()
        val boardToCome = 5 - board.size
        val needed = boardToCome + opponents * 2
        var share = 0.0

        repeat(iterations) {
            // Only the cards that will be looked at are drawn, by shuffling just
            // the front of the deck. Shuffling all of it each time costs several
            // times more for the same result.
            for (i in 0 until needed) {
                val j = i + random.nextInt(unseen.size - i)
                val swap = unseen[i]
                unseen[i] = unseen[j]
                unseen[j] = swap
            }
            val dealt = unseen
            val fullBoard = board + dealt.subList(0, boardToCome)
            val mine = HandEvaluator.evaluate(hole + fullBoard)

            var beaten = false
            var tied = 0
            for (opponent in 0 until opponents) {
                val from = boardToCome + opponent * 2
                val theirs = HandEvaluator.evaluate(dealt.subList(from, from + 2) + fullBoard)
                val comparison = mine.compareTo(theirs)
                if (comparison < 0) {
                    beaten = true
                    break
                }
                if (comparison == 0) tied++
            }
            if (!beaten) share += 1.0 / (tied + 1)
        }

        return share / iterations
    }

    /**
     * The equity a call needs to break even: the cost of calling as a share of
     * the pot once the call is in it.
     */
    fun potOdds(pot: Long, callCost: Long): Double {
        require(pot >= 0 && callCost >= 0) { "Chips cannot be negative" }
        if (callCost == 0L) return 0.0
        return callCost.toDouble() / (pot + callCost)
    }
}

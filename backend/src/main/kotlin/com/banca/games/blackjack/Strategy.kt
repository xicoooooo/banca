package com.banca.games.blackjack

/** What an action is expected to return, in units of the hand's original bet. */
data class ActionValue(val action: BlackjackAction, val value: Double)

/** Where the dealer's hand ends up, as chances that sum to one. */
data class DealerOutcomes(val bust: Double, val totals: Map<Int, Double>)

/** The decision in front of a player, reduced to what the arithmetic needs. */
data class Decision(
    val total: Int,
    val soft: Boolean,
    /** The points of each card when the hand is a pair that may be split, an ace being one. */
    val pairOf: Int?,
    /** The points of the dealer's face-up card, an ace being one. */
    val dealerUp: Int,
    val canDouble: Boolean,
    val canSplit: Boolean,
)

/**
 * What each choice at a blackjack table is worth, worked out rather than
 * looked up.
 *
 * The right play is whichever action has the highest expected value, and the
 * expected value follows from the rules alone: how the dealer must play, what
 * a natural pays, whether a split hand may be doubled. So nothing here is a
 * memorised chart. Change a rule and the advice changes with it.
 *
 * Cards are drawn as if the shoe never ran down, each rank always as likely as
 * the last. With six decks that is a close approximation, and the best action
 * it finds matches the published basic strategy for these rules in all but a
 * few hands so close that neither choice is a mistake.
 *
 * The dealer has already looked for a natural by the time the player decides,
 * so every figure is for a dealer who does not have one.
 */
class Strategy(private val rules: Rules = Rules()) {

    private data class Hand(val total: Int, val soft: Boolean) {
        val isBust: Boolean get() = total > 21

        fun with(card: Int): Hand {
            var total = total + card
            var soft = soft
            if (card == ACE && !soft && total + 10 <= 21) {
                total += 10
                soft = true
            }
            if (total > 21 && soft) {
                total -= 10
                soft = false
            }
            return Hand(total, soft)
        }

        companion object {
            val EMPTY = Hand(0, false)
        }
    }

    private val dealerCache = mutableMapOf<Int, DealerOutcomes>()
    private val hitCache = mutableMapOf<Pair<Hand, Int>, Double>()

    /** Where the dealer finishes from a given face-up card, knowing there is no natural underneath. */
    fun dealerOutcomes(dealerUp: Int): DealerOutcomes = dealerCache.getOrPut(dealerUp) {
        val finals = mutableMapOf<Int, Double>()

        fun play(hand: Hand, chance: Double) {
            val mustDraw = hand.total < 17 || (hand.total == 17 && hand.soft && rules.dealerHitsSoft17)
            if (!mustDraw) {
                finals.merge(if (hand.isBust) BUST else hand.total, chance, Double::plus)
                return
            }
            for (card in ACE..TEN) play(hand.with(card), chance * CHANCE[card])
        }

        // The hole card cannot be the one that would make a natural.
        val ruledOut = when (dealerUp) {
            ACE -> TEN
            TEN -> ACE
            else -> null
        }
        val remaining = 1.0 - (ruledOut?.let { CHANCE[it] } ?: 0.0)
        val shown = Hand.EMPTY.with(dealerUp)
        for (card in ACE..TEN) {
            if (card != ruledOut) play(shown.with(card), CHANCE[card] / remaining)
        }

        DealerOutcomes(bust = finals[BUST] ?: 0.0, totals = finals.filterKeys { it != BUST }.toSortedMap())
    }

    /** The chance the next card takes a hand past twenty-one. */
    fun bustChance(total: Int, soft: Boolean): Double {
        val hand = Hand(total, soft)
        return (ACE..TEN).sumOf { card -> if (hand.with(card).isBust) CHANCE[card] else 0.0 }
    }

    private fun stand(hand: Hand, dealerUp: Int): Double {
        val dealer = dealerOutcomes(dealerUp)
        return dealer.bust + dealer.totals.entries.sumOf { (total, chance) ->
            chance * when {
                hand.total > total -> 1.0
                hand.total < total -> -1.0
                else -> 0.0
            }
        }
    }

    /** Taking a card, and then playing on as well as can be done without doubling. */
    private fun hit(hand: Hand, dealerUp: Int): Double = hitCache.getOrPut(hand to dealerUp) {
        (ACE..TEN).sumOf { card ->
            val next = hand.with(card)
            CHANCE[card] * if (next.isBust) -1.0 else maxOf(stand(next, dealerUp), hit(next, dealerUp))
        }
    }

    /** One more card for twice the stake. */
    private fun double(hand: Hand, dealerUp: Int): Double = 2 * (ACE..TEN).sumOf { card ->
        val next = hand.with(card)
        CHANCE[card] * if (next.isBust) -1.0 else stand(next, dealerUp)
    }

    /**
     * Two hands, each starting from one card of the pair. Split aces get one
     * card each and stop; any other hand is played as well as it can be,
     * doubling included. Splitting again is left out, which undervalues a split
     * slightly and never changes whether it is the best play.
     */
    private fun split(card: Int, dealerUp: Int): Double {
        val first = Hand.EMPTY.with(card)
        return 2 * (ACE..TEN).sumOf { next ->
            val hand = first.with(next)
            CHANCE[next] * if (card == ACE) {
                stand(hand, dealerUp)
            } else {
                maxOf(stand(hand, dealerUp), hit(hand, dealerUp), double(hand, dealerUp))
            }
        }
    }

    /** Every action open to the player with what it is worth, the best first. */
    fun evaluate(decision: Decision): List<ActionValue> {
        val hand = Hand(decision.total, decision.soft)
        return buildList {
            add(ActionValue(BlackjackAction.Stand, stand(hand, decision.dealerUp)))
            add(ActionValue(BlackjackAction.Hit, hit(hand, decision.dealerUp)))
            if (decision.canDouble) add(ActionValue(BlackjackAction.Double, double(hand, decision.dealerUp)))
            if (decision.canSplit && decision.pairOf != null) {
                add(ActionValue(BlackjackAction.Split, split(decision.pairOf, decision.dealerUp)))
            }
        }.sortedByDescending { it.value }
    }

    /**
     * Insurance is a side bet of half the stake, paid two to one, that the
     * dealer's hole card is worth ten. It loses more often than it pays, so
     * declining, which costs nothing, is always the better of the two.
     */
    fun insurance(): List<ActionValue> {
        val dealerHasNatural = CHANCE[TEN]
        val insured = dealerHasNatural * 1.0 + (1 - dealerHasNatural) * -0.5
        return listOf(ActionValue(BlackjackAction.DeclineInsurance, 0.0), ActionValue(BlackjackAction.Insure, insured))
    }

    companion object {
        const val ACE = 1
        const val TEN = 10
        private const val BUST = 0

        /** The chance of each card value, tens and faces together being four of every thirteen. */
        private val CHANCE = DoubleArray(11) { value ->
            when (value) {
                0 -> 0.0
                TEN -> 4.0 / 13
                else -> 1.0 / 13
            }
        }
    }
}

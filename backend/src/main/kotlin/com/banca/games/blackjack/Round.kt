package com.banca.games.blackjack

import com.banca.games.cards.Card
import com.banca.games.cards.Rank

data class Rules(
    val decks: Int = 6,
    /** Most tables have the dealer stand on every seventeen; some hit the soft one. */
    val dealerHitsSoft17: Boolean = false,
    /** How many hands splitting may leave a player holding. */
    val maxHands: Int = 4,
)

sealed interface BlackjackAction {
    data object Hit : BlackjackAction
    data object Stand : BlackjackAction
    data object Double : BlackjackAction
    data object Split : BlackjackAction
    data object Insure : BlackjackAction
    data object DeclineInsurance : BlackjackAction
}

enum class Phase {
    /** The dealer shows an ace and the player is asked about insurance. */
    INSURANCE,
    PLAYER,
    SETTLED,
}

enum class Outcome {
    BLACKJACK,
    WIN,
    PUSH,
    LOSE,
    BUST,
}

/** [returned] is every chip coming back for this hand, the stake included. */
data class HandResult(val outcome: Outcome, val returned: Long)

data class RoundResult(
    val hands: List<HandResult>,
    val insuranceReturned: Long,
    /** What the round did to the player's chips, all told. */
    val net: Long,
)

data class LegalActions(
    val hit: Boolean,
    val stand: Boolean,
    val double: Boolean,
    val split: Boolean,
    val insurance: Boolean,
)

/**
 * One round of blackjack for one player against the house, from the deal to
 * the payout. Immutable: every action returns a new round.
 *
 * [stack] is the player's chips that are not on the table. Bets leave it as
 * they are made and winnings return to it when the round settles, so at any
 * moment stack plus bets is everything the player has.
 *
 * The cards still to come are part of the round ([shoe]), which is what lets
 * it stay a value. Nothing outside may look at them; the session shows a
 * player only what is face up.
 */
data class Round(
    val rules: Rules,
    val shoe: List<Card>,
    val dealer: List<Card>,
    val hands: List<PlayerHand>,
    val active: Int,
    val phase: Phase,
    val stack: Long,
    val insurance: Long,
    val result: RoundResult?,
) {
    val isSettled: Boolean get() = phase == Phase.SETTLED

    val dealerUpCard: Card get() = dealer.first()

    val activeHand: PlayerHand? get() = if (phase == Phase.PLAYER) hands[active] else null

    fun legalActions(): LegalActions {
        val hand = activeHand
        return LegalActions(
            hit = hand != null,
            stand = hand != null,
            double = hand != null && hand.cards.size == 2 && stack >= hand.bet,
            split = hand != null && hand.isPair && hands.size < rules.maxHands && stack >= hand.bet,
            insurance = phase == Phase.INSURANCE && stack >= insuranceCost,
        )
    }

    /** Insurance is a side bet of half the stake that the dealer has blackjack. */
    val insuranceCost: Long get() = hands.first().bet / 2

    fun act(action: BlackjackAction): Round {
        check(!isSettled) { "The round is over" }
        val legal = legalActions()

        return when (action) {
            is BlackjackAction.Insure -> {
                require(phase == Phase.INSURANCE) { "Insurance is not on offer" }
                require(legal.insurance) { "Not enough chips for insurance" }
                copy(stack = stack - insuranceCost, insurance = insuranceCost).afterPeek()
            }

            is BlackjackAction.DeclineInsurance -> {
                require(phase == Phase.INSURANCE) { "Insurance is not on offer" }
                afterPeek()
            }

            is BlackjackAction.Hit -> {
                require(legal.hit) { "You cannot hit now" }
                draw(1) { cards, rest -> withActive(rest) { it.copy(cards = it.cards + cards) } }.advance()
            }

            is BlackjackAction.Stand -> {
                require(legal.stand) { "You cannot stand now" }
                withActive(shoe) { it.copy(stood = true) }.advance()
            }

            is BlackjackAction.Double -> {
                require(legal.double) { "You cannot double now" }
                val stake = hands[active].bet
                // Doubling buys exactly one more card.
                draw(1) { cards, rest ->
                    withActive(rest) { it.copy(cards = it.cards + cards, bet = stake * 2, doubled = true) }
                }.copy(stack = stack - stake).advance()
            }

            is BlackjackAction.Split -> {
                require(legal.split) { "You cannot split now" }
                val hand = hands[active]
                // Split aces get one card each and no more, by long custom.
                val aces = hand.cards[0].rank == Rank.ACE

                draw(2) { cards, rest ->
                    val pair = hand.cards.mapIndexed { index, card ->
                        PlayerHand(cards = listOf(card, cards[index]), bet = hand.bet, fromSplit = true, stood = aces)
                    }
                    copy(shoe = rest, hands = hands.take(active) + pair + hands.drop(active + 1))
                }.copy(stack = stack - hand.bet).advance()
            }
        }
    }

    private fun draw(count: Int, use: (List<Card>, List<Card>) -> Round): Round {
        check(shoe.size >= count) { "The shoe has run out" }
        return use(shoe.take(count), shoe.drop(count))
    }

    private fun withActive(rest: List<Card>, change: (PlayerHand) -> PlayerHand): Round =
        copy(shoe = rest, hands = hands.mapIndexed { index, hand -> if (index == active) change(hand) else hand })

    /** Moves to the next hand with a decision left, or plays the dealer when there is none. */
    private fun advance(): Round {
        val next = hands.indices.firstOrNull { it >= active && !hands[it].isFinished }
        return if (next != null) copy(active = next) else playDealer().settle()
    }

    /** The dealer looks at the hole card. A natural on either side ends the round here. */
    private fun afterPeek(): Round =
        if (isNatural(dealer) || hands.first().isBlackjack) settle() else copy(phase = Phase.PLAYER)

    private fun playDealer(): Round {
        // With every hand bust there is nothing left to beat, so the dealer does not draw.
        if (hands.all { it.isBust }) return this

        var cards = dealer
        var rest = shoe
        while (dealerDraws(valueOf(cards))) {
            check(rest.isNotEmpty()) { "The shoe has run out" }
            cards = cards + rest.first()
            rest = rest.drop(1)
        }
        return copy(dealer = cards, shoe = rest)
    }

    private fun dealerDraws(value: HandValue): Boolean =
        value.total < 17 || (value.total == 17 && value.soft && rules.dealerHitsSoft17)

    private fun settle(): Round {
        val dealerValue = valueOf(dealer)
        val dealerNatural = isNatural(dealer)

        val results = hands.map { hand ->
            when {
                hand.isBust -> HandResult(Outcome.BUST, 0)
                dealerNatural -> if (hand.isBlackjack) HandResult(Outcome.PUSH, hand.bet) else HandResult(Outcome.LOSE, 0)
                // A natural pays three to two.
                hand.isBlackjack -> HandResult(Outcome.BLACKJACK, hand.bet + hand.bet * 3 / 2)
                dealerValue.isBust || hand.value.total > dealerValue.total -> HandResult(Outcome.WIN, hand.bet * 2)
                hand.value.total == dealerValue.total -> HandResult(Outcome.PUSH, hand.bet)
                else -> HandResult(Outcome.LOSE, 0)
            }
        }

        // Insurance pays two to one, so the stake comes back with twice itself.
        val insuranceReturned = if (dealerNatural) insurance * 3 else 0
        val returned = results.sumOf { it.returned } + insuranceReturned
        val staked = hands.sumOf { it.bet } + insurance

        return copy(
            phase = Phase.SETTLED,
            stack = stack + returned,
            result = RoundResult(hands = results, insuranceReturned = insuranceReturned, net = returned - staked),
        )
    }

    companion object {
        /** The fewest cards a round can need: four hands drawing out and a dealer doing the same. */
        const val CARDS_NEEDED = 60

        fun deal(bet: Long, stack: Long, shoe: List<Card>, rules: Rules = Rules()): Round {
            require(bet > 0) { "A bet must be more than nothing" }
            require(bet <= stack) { "A bet of $bet is more than the $stack you have" }
            require(shoe.size >= CARDS_NEEDED) { "The shoe needs reshuffling before another round" }

            // Player, dealer, player, dealer: the dealer's second card is the hole card.
            val round = Round(
                rules = rules,
                shoe = shoe.drop(4),
                dealer = listOf(shoe[1], shoe[3]),
                hands = listOf(PlayerHand(cards = listOf(shoe[0], shoe[2]), bet = bet)),
                active = 0,
                phase = Phase.PLAYER,
                stack = stack - bet,
                insurance = 0,
                result = null,
            )

            return if (round.dealerUpCard.rank == Rank.ACE) round.copy(phase = Phase.INSURANCE) else round.afterPeek()
        }
    }
}

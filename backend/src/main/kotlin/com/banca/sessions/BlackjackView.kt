package com.banca.sessions

import com.banca.games.blackjack.Phase
import com.banca.games.blackjack.PlayerHand
import com.banca.games.blackjack.Round
import com.banca.games.blackjack.valueOf
import kotlinx.serialization.Serializable

/**
 * Everything the player may know about the blackjack table. The dealer's hole
 * card is not in it until the round is settled, and the cards still in the
 * shoe never are.
 */
@Serializable
data class BlackjackView(
    val roundNumber: Int,
    /** "betting", "insurance", "player", "waiting" (at a shared table, for the dealer) or "settled". */
    val phase: String,
    val stack: Long,
    val minBet: Long,
    val maxBet: Long,
    /** What was staked last round, offered again as the default. */
    val lastBet: Long?,
    val dealer: DealerView?,
    val hands: List<BlackjackHandView>,
    /** Which hand is being played, when one is. */
    val activeHand: Int?,
    val legal: BlackjackLegalView,
    val insuranceCost: Long,
    val result: BlackjackResultView?,
)

@Serializable
data class DealerView(
    /** In the order dealt. A card still face down is null. */
    val cards: List<String?>,
    /** The total of the cards that can be seen. */
    val total: Int,
    val soft: Boolean,
)

@Serializable
data class BlackjackHandView(
    val cards: List<String>,
    val bet: Long,
    val total: Int,
    val soft: Boolean,
    /** "playing", "waiting", "stood", "doubled", "bust" or "blackjack". */
    val status: String,
    /** Present once settled: "blackjack", "win", "push", "lose" or "bust". */
    val outcome: String?,
    /** Present once settled: every chip that came back for this hand. */
    val returned: Long?,
)

@Serializable
data class BlackjackLegalView(
    val bet: Boolean,
    val hit: Boolean,
    val stand: Boolean,
    val double: Boolean,
    val split: Boolean,
    val insurance: Boolean,
)

@Serializable
data class BlackjackResultView(
    /** What the round did to the player's chips, all told. */
    val net: Long,
    val insuranceReturned: Long,
    /** True when the player was out of chips and the house staked them again. */
    val refilled: Boolean,
)

/**
 * A round as its own player may see it, at a table alone or a shared one: their
 * hands, the dealer's face-up card, and what they may do. The hole card stays
 * out until the round is settled, and the shoe always.
 */
fun blackjackViewOf(
    round: Round?,
    roundNumber: Int,
    stack: Long,
    minBet: Long,
    maxBet: Long,
    lastBet: Long?,
    refilled: Boolean,
): BlackjackView {
    val settled = round?.isSettled ?: false
    val legal = round?.takeUnless { it.isSettled }?.legalActions()

    return BlackjackView(
        roundNumber = roundNumber,
        phase = round?.phase?.name?.lowercase() ?: "betting",
        stack = stack,
        minBet = minBet,
        maxBet = maxBet,
        lastBet = lastBet,
        dealer = round?.let(::dealerViewOf),
        hands = round?.hands.orEmpty().mapIndexed { index, hand -> handViewOf(round!!, index, hand) },
        activeHand = round?.takeIf { it.phase == Phase.PLAYER }?.active,
        legal = BlackjackLegalView(
            bet = round == null || settled,
            hit = legal?.hit ?: false,
            stand = legal?.stand ?: false,
            double = legal?.double ?: false,
            split = legal?.split ?: false,
            insurance = legal?.insurance ?: false,
        ),
        insuranceCost = round?.takeIf { it.phase == Phase.INSURANCE }?.insuranceCost ?: 0,
        result = round?.result?.let {
            BlackjackResultView(net = it.net, insuranceReturned = it.insuranceReturned, refilled = refilled)
        },
    )
}

/** The hole card stays face down until the round is settled. */
fun dealerViewOf(round: Round): DealerView {
    val shown = if (round.isSettled) round.dealer else round.dealer.take(1)
    val value = valueOf(shown)
    return DealerView(
        cards = round.dealer.mapIndexed { index, card -> if (index < shown.size) card.toString() else null },
        total = value.total,
        soft = value.soft,
    )
}

fun handViewOf(round: Round, index: Int, hand: PlayerHand): BlackjackHandView {
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

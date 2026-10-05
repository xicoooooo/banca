package com.banca.sessions

import kotlinx.serialization.Serializable

/**
 * Everything the player may know about the blackjack table. The dealer's hole
 * card is not in it until the round is settled, and the cards still in the
 * shoe never are.
 */
@Serializable
data class BlackjackView(
    val roundNumber: Int,
    /** "betting", "insurance", "player" or "settled". */
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

package com.banca.sessions

import kotlinx.serialization.Serializable

/**
 * Everything one seat is allowed to know about the table, and nothing else.
 *
 * This is the only shape that leaves the session: it is what a browser
 * receives and what a [SeatDriver] decides from. Because an agent is handed a
 * view rather than the hand itself, it cannot see hidden cards even by mistake.
 */
@Serializable
data class TableView(
    val handNumber: Int,
    val street: String,
    val board: List<String>,
    val pot: Long,
    val buttonSeat: Int,
    val smallBlind: Long,
    val bigBlind: Long,
    val yourSeat: Int,
    val actorSeat: Int?,
    val players: List<PlayerView>,
    /** Present only when it is this seat's turn. */
    val legal: LegalView?,
    /** Present only once the hand is over. */
    val result: ResultView?,
)

@Serializable
data class PlayerView(
    val seat: Int,
    val name: String,
    val stack: Long,
    val committed: Long,
    val status: String,
    /** Your own cards always; anyone else's only when shown at a showdown. */
    val cards: List<String>?,
)

@Serializable
data class LegalView(
    val canFold: Boolean,
    val canCheck: Boolean,
    val canCall: Boolean,
    val callCost: Long,
    val canBet: Boolean,
    val minBet: Long,
    val canRaise: Boolean,
    val minRaiseTo: Long,
    val maxTo: Long,
)

@Serializable
data class ResultView(
    val winnings: Map<Int, Long>,
    /** Seat to hand category, empty when the hand ended without a showdown. */
    val showdown: Map<Int, String>,
)

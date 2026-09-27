package com.banca.games.poker

enum class PlayerStatus {
    ACTIVE,
    FOLDED,
    ALL_IN,
}

/**
 * [committed] is chips put in during the current street; [contributed] is the
 * whole hand. The first decides what it costs to call, the second decides how
 * side pots are split.
 */
data class Player(
    val seat: Int,
    val stack: Long,
    val committed: Long = 0,
    val contributed: Long = 0,
    val status: PlayerStatus = PlayerStatus.ACTIVE,
) {
    init {
        require(stack >= 0) { "Seat $seat has a negative stack" }
    }

    /** Still in the hand, so still able to win chips. */
    val isContesting: Boolean get() = status != PlayerStatus.FOLDED

    /** Still has chips and a decision to make. */
    val canAct: Boolean get() = status == PlayerStatus.ACTIVE

    fun pay(amount: Long): Player {
        require(amount >= 0) { "Cannot pay a negative amount" }
        require(amount <= stack) { "Seat $seat cannot pay $amount from a stack of $stack" }

        val remaining = stack - amount
        return copy(
            stack = remaining,
            committed = committed + amount,
            contributed = contributed + amount,
            status = if (remaining == 0L) PlayerStatus.ALL_IN else status,
        )
    }

    /** Carries the player into the next street, where nothing is committed yet. */
    fun nextStreet(): Player = copy(committed = 0)
}

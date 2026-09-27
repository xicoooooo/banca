package com.banca.games.poker

sealed interface Action {
    data object Fold : Action
    data object Check : Action
    data object Call : Action

    /** [amount] is the total to have in front of you this street. */
    data class Bet(val amount: Long) : Action

    /** [to] is the total to have in front of you this street, not the increment. */
    data class Raise(val to: Long) : Action
}

data class LegalActions(
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

/**
 * One street of betting. Immutable: every action returns a new round.
 *
 * Two rules drive the design and are worth naming, because they are where
 * betting implementations usually go wrong:
 *
 *  - A street ends only when every player who can still act has acted at least
 *    once and has matched the current bet. That is why posting a blind does not
 *    count as acting, and why the big blind still gets their option.
 *  - Going all-in for less than a full raise increases the amount others must
 *    call, but does not reopen betting: a player who already acted can then only
 *    call or fold. [actedSinceFullRaise] tracks exactly that.
 */
data class BettingRound(
    val players: List<Player>,
    val bigBlind: Long,
    val currentBet: Long,
    val lastFullRaiseSize: Long,
    val acted: Set<Int>,
    val actedSinceFullRaise: Set<Int>,
    val actorSeat: Int?,
) {

    val isComplete: Boolean get() = actorSeat == null

    val actor: Player? get() = actorSeat?.let(::player)

    fun player(seat: Int): Player =
        players.firstOrNull { it.seat == seat } ?: error("No seat $seat at this table")

    fun legalActions(): LegalActions {
        val player = actor ?: error("Betting is complete, nobody is to act")

        val owed = currentBet - player.committed
        val callCost = owed.coerceAtMost(player.stack)
        val maxTo = player.committed + player.stack

        return LegalActions(
            canFold = true,
            canCheck = owed == 0L,
            canCall = owed > 0,
            callCost = callCost,
            canBet = currentBet == 0L,
            // Betting less than a big blind is only allowed when it is everything.
            minBet = bigBlind.coerceAtMost(maxTo),
            // No raise when calling already costs everything, or when a short
            // all-in raised the price without reopening the betting.
            canRaise = currentBet > 0 && player.stack > owed && player.seat !in actedSinceFullRaise,
            minRaiseTo = (currentBet + lastFullRaiseSize).coerceAtMost(maxTo),
            maxTo = maxTo,
        )
    }

    fun apply(action: Action): BettingRound {
        val player = actor ?: error("Betting is complete, nobody is to act")
        val legal = legalActions()

        return when (action) {
            is Action.Fold ->
                settle(player.copy(status = PlayerStatus.FOLDED), reopens = false)

            is Action.Check -> {
                require(legal.canCheck) { "Seat ${player.seat} cannot check facing a bet" }
                settle(player, reopens = false)
            }

            is Action.Call -> {
                require(legal.canCall) { "Seat ${player.seat} has nothing to call" }
                settle(player.pay(legal.callCost), reopens = false)
            }

            is Action.Bet -> {
                require(legal.canBet) { "Seat ${player.seat} cannot bet once a bet exists, raise instead" }
                require(action.amount <= legal.maxTo) {
                    "Seat ${player.seat} cannot bet ${action.amount}, only ${legal.maxTo} available"
                }
                require(action.amount >= legal.minBet || action.amount == legal.maxTo) {
                    "A bet of ${action.amount} is below the minimum of ${legal.minBet}"
                }
                settle(player.pay(action.amount - player.committed), reopens = true, newBet = action.amount, raiseSize = action.amount)
            }

            is Action.Raise -> {
                require(currentBet > 0) { "Seat ${player.seat} cannot raise without a bet to raise, bet instead" }
                require(player.seat !in actedSinceFullRaise) {
                    "Seat ${player.seat} cannot raise: the all-in did not reopen the betting"
                }
                require(action.to <= legal.maxTo) {
                    "Seat ${player.seat} cannot raise to ${action.to}, only ${legal.maxTo} available"
                }
                require(action.to > currentBet) { "A raise to ${action.to} does not beat the bet of $currentBet" }

                val isAllIn = action.to == legal.maxTo
                require(action.to >= legal.minRaiseTo || isAllIn) {
                    "A raise to ${action.to} is below the minimum of ${legal.minRaiseTo}"
                }

                val raiseSize = action.to - currentBet
                // A short all-in raises the price but does not reopen betting.
                val isFullRaise = raiseSize >= lastFullRaiseSize
                settle(
                    player.pay(action.to - player.committed),
                    reopens = isFullRaise,
                    newBet = action.to,
                    raiseSize = if (isFullRaise) raiseSize else lastFullRaiseSize,
                )
            }
        }
    }

    private fun settle(
        updated: Player,
        reopens: Boolean,
        newBet: Long = currentBet,
        raiseSize: Long = lastFullRaiseSize,
    ): BettingRound {
        val seat = updated.seat
        val next = copy(
            players = players.map { if (it.seat == seat) updated else it },
            currentBet = newBet,
            lastFullRaiseSize = raiseSize,
            acted = acted + seat,
            actedSinceFullRaise = if (reopens) setOf(seat) else actedSinceFullRaise + seat,
        )
        return next.copy(actorSeat = next.findNextActor(after = seat))
    }

    private fun findNextActor(after: Int): Int? {
        if (players.count { it.isContesting } <= 1) return null

        val order = players.sortedBy { it.seat }
        val from = order.indexOfFirst { it.seat == after }

        for (step in 1..order.size) {
            val candidate = order[(from + step) % order.size]
            if (candidate.owesAnAction()) return candidate.seat
        }
        return null
    }

    private fun Player.owesAnAction(): Boolean =
        canAct && (seat !in acted || committed < currentBet)

    companion object {
        /**
         * Postflop, where nothing is committed and the first bet must be at
         * least a big blind.
         */
        fun open(players: List<Player>, bigBlind: Long, firstToAct: Int): BettingRound =
            of(players, bigBlind, firstToAct, currentBet = 0, lastFullRaiseSize = bigBlind)

        /**
         * Preflop, after the blinds are posted. Blinds are chips, not actions,
         * so neither blind counts as having acted and the big blind keeps the
         * option to raise.
         */
        fun afterBlinds(players: List<Player>, bigBlind: Long, firstToAct: Int): BettingRound =
            of(players, bigBlind, firstToAct, currentBet = players.maxOf { it.committed }, lastFullRaiseSize = bigBlind)

        private fun of(
            players: List<Player>,
            bigBlind: Long,
            firstToAct: Int,
            currentBet: Long,
            lastFullRaiseSize: Long,
        ): BettingRound {
            require(players.size >= 2) { "A betting round needs at least two players" }
            require(players.distinctBy { it.seat }.size == players.size) { "Duplicate seats" }

            val round = BettingRound(
                players = players,
                bigBlind = bigBlind,
                currentBet = currentBet,
                lastFullRaiseSize = lastFullRaiseSize,
                acted = emptySet(),
                actedSinceFullRaise = emptySet(),
                actorSeat = null,
            )

            val start = players.firstOrNull { it.seat == firstToAct } ?: error("No seat $firstToAct")
            val actor = if (round.run { start.owesAnAction() }) start.seat else round.findNextActor(after = firstToAct)
            return round.copy(actorSeat = actor)
        }
    }
}

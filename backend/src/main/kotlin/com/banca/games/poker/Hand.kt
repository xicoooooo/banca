package com.banca.games.poker

/**
 * A single hand of no-limit Texas Hold'em, from blinds to payout.
 *
 * The whole board is dealt when the hand starts and revealed a street at a
 * time. Nothing can read the undealt cards except through [board], so this is
 * equivalent to dealing as you go, and it keeps the hand a value rather than
 * something holding a mutable deck. Burn cards are skipped: they change nothing
 * about a shuffle that is already fair.
 */
data class Hand(
    val players: List<Player>,
    val holeCards: Map<Int, List<Card>>,
    private val fullBoard: List<Card>,
    val street: Street,
    val betting: BettingRound?,
    val buttonSeat: Int,
    val smallBlind: Long,
    val bigBlind: Long,
    val result: HandResult?,
) {

    val isComplete: Boolean get() = result != null

    val board: List<Card> get() = fullBoard.take(street.boardSize)

    val actorSeat: Int? get() = betting?.actorSeat

    fun player(seat: Int): Player =
        players.firstOrNull { it.seat == seat } ?: error("No seat $seat in this hand")

    fun legalActions(): LegalActions =
        (betting ?: error("The hand is over")).legalActions()

    fun act(action: Action): Hand {
        val betting = betting ?: error("The hand is over")
        val next = betting.apply(action)
        return copy(players = next.players, betting = next).progress()
    }

    /**
     * Carries the hand forward for as long as nobody needs to decide anything:
     * through finished streets, past streets where everyone is already all-in,
     * and into the payout.
     */
    private fun progress(): Hand {
        val betting = betting
        if (betting != null && !betting.isComplete) return this

        if (players.count { it.isContesting } <= 1) return finish(showdown = false)
        if (street == Street.RIVER) return finish(showdown = true)

        val nextStreet = street.next()
        val carried = players.map { it.nextStreet() }
        val canStillBet = carried.count { it.canAct } >= 2

        val advanced = copy(
            players = carried,
            street = nextStreet,
            betting = if (canStillBet) {
                BettingRound.open(carried, bigBlind, firstToAct = firstLeftOf(buttonSeat, carried))
            } else {
                null
            },
        )

        // Someone has a decision, so stop. Otherwise deal the next street too.
        return if (canStillBet) advanced else advanced.progress()
    }

    private fun finish(showdown: Boolean): Hand {
        val pots = Pots.build(players)
        val ranks = if (showdown) {
            players.filter { it.isContesting }
                .associate { it.seat to HandEvaluator.evaluate(holeCards.getValue(it.seat) + fullBoard) }
        } else {
            emptyMap()
        }

        val awards = pots.map { pot -> award(pot, ranks) }
        val winnings = awards
            .flatMap { potAward -> potAward.winners.map { seat -> seat to share(potAward, seat) } }
            .groupBy({ (seat, _) -> seat }, { (_, amount) -> amount })
            .mapValues { (_, amounts) -> amounts.sum() }

        return copy(
            street = if (showdown) Street.SHOWDOWN else street,
            players = players.map { it.copy(stack = it.stack + (winnings[it.seat] ?: 0)) },
            betting = null,
            result = HandResult(awards = awards, winnings = winnings, showdown = ranks),
        )
    }

    private fun award(pot: Pot, ranks: Map<Int, HandRank>): PotAward {
        val winners = if (ranks.isEmpty()) {
            pot.eligibleSeats.toList()
        } else {
            val best = pot.eligibleSeats.mapNotNull { ranks[it] }.maxOrNull()
            pot.eligibleSeats.filter { ranks[it] == best }
        }

        val ordered = winners.sortedBy { seatsFromButton().indexOf(it) }
        // A pot that will not divide evenly gives the spare chips to the
        // winners nearest the button's left, which is the usual house rule.
        val remainder = (pot.amount % ordered.size).toInt()
        return PotAward(
            amount = pot.amount,
            winners = ordered,
            oddChipTo = ordered.take(remainder),
        )
    }

    private fun share(award: PotAward, seat: Int): Long =
        award.amount / award.winners.size + if (seat in award.oddChipTo) 1 else 0

    private fun seatsFromButton(): List<Int> = orderFrom(buttonSeat, players).map { it.seat }

    companion object {

        fun start(
            seats: List<Player>,
            buttonSeat: Int,
            smallBlind: Long,
            bigBlind: Long,
            deck: Deck,
        ): Hand {
            require(seats.size >= 2) { "A hand needs at least two players" }
            require(seats.distinctBy { it.seat }.size == seats.size) { "Duplicate seats" }
            require(seats.all { it.stack > 0 }) { "Every player needs chips to be dealt in" }
            require(smallBlind in 1..bigBlind) { "Blinds must be positive and the small no larger" }

            val heads = seats.size == 2
            // Heads up the button posts the small blind and acts first before
            // the flop; with three or more the blinds sit to the button's left.
            val smallBlindSeat = if (heads) buttonSeat else orderFrom(buttonSeat, seats).first().seat
            val bigBlindSeat = orderFrom(smallBlindSeat, seats).first().seat

            val posted = seats.map { player ->
                when (player.seat) {
                    smallBlindSeat -> player.pay(minOf(smallBlind, player.stack))
                    bigBlindSeat -> player.pay(minOf(bigBlind, player.stack))
                    else -> player
                }
            }

            val order = orderFrom(buttonSeat, posted)
            val hole = order.associate { it.seat to deck.deal(2) }
            val board = deck.deal(5)

            // Blinds can leave nobody with a decision, for instance heads up
            // when both are all-in after posting.
            val firstToAct = orderFrom(bigBlindSeat, posted).firstOrNull { it.canAct }?.seat

            val hand = Hand(
                players = posted,
                holeCards = hole,
                fullBoard = board,
                street = Street.PREFLOP,
                betting = firstToAct?.let {
                    BettingRound.afterBlinds(players = posted, bigBlind = bigBlind, firstToAct = it)
                },
                buttonSeat = buttonSeat,
                smallBlind = smallBlind,
                bigBlind = bigBlind,
                result = null,
            )

            // Blinds alone can settle the betting, for instance when they put
            // everyone all-in.
            return hand.progress()
        }

        /** Seats clockwise from [seat], not including it. */
        private fun orderFrom(seat: Int, players: List<Player>): List<Player> {
            val sorted = players.sortedBy { it.seat }
            val from = sorted.indexOfFirst { it.seat == seat }
            require(from >= 0) { "No seat $seat at this table" }
            return (1..sorted.size).map { sorted[(from + it) % sorted.size] }
        }

        /** The next player clockwise from [seat] who still has a decision to make. */
        private fun firstLeftOf(seat: Int, players: List<Player>): Int =
            orderFrom(seat, players).first { it.canAct }.seat
    }
}

package com.banca.sessions

import com.banca.games.poker.Action
import com.banca.games.cards.Deck
import com.banca.games.poker.Hand
import com.banca.games.poker.Player
import kotlin.random.Random

/**
 * A live poker table: who sits where, their stacks between hands, and the hand
 * in progress.
 *
 * Not thread safe. Each table is driven by a single coroutine, which is what
 * keeps actions in order without locks.
 */
class PokerTable(
    private val names: Map<Int, String>,
    private val startingStack: Long,
    private val smallBlind: Long,
    private val bigBlind: Long,
    private val random: Random,
) {
    init {
        require(names.size >= 2) { "A table needs at least two seats" }
    }

    private var stacks: Map<Int, Long> = names.keys.associateWith { startingStack }

    // Starts on the last seat so the first hand's button lands on the first.
    private var buttonSeat: Int = names.keys.max()
    private var hand: Hand? = null

    var handNumber: Int = 0
        private set

    val actorSeat: Int? get() = hand?.actorSeat

    val isHandComplete: Boolean get() = hand?.isComplete ?: true

    fun startHand() {
        check(isHandComplete) { "The current hand is still being played" }

        hand?.let { finished -> stacks = finished.players.associate { it.seat to it.stack } }
        // Until the wallet exists, a busted table simply starts over.
        if (stacks.values.any { it == 0L }) stacks = stacks.mapValues { startingStack }

        buttonSeat = seatAfter(buttonSeat)
        handNumber++
        hand = Hand.start(
            seats = stacks.map { (seat, stack) -> Player(seat, stack) },
            buttonSeat = buttonSeat,
            smallBlind = smallBlind,
            bigBlind = bigBlind,
            deck = Deck.shuffled(random),
        )
    }

    fun act(seat: Int, action: Action) {
        val current = hand ?: error("No hand has been dealt")
        check(!current.isComplete) { "The hand is over" }
        require(current.actorSeat == seat) { "It is not seat $seat's turn" }
        hand = current.act(action)
    }

    fun view(seat: Int): TableView {
        val current = hand ?: error("No hand has been dealt")
        require(seat in names) { "No seat $seat at this table" }
        val result = current.result

        return TableView(
            handNumber = handNumber,
            street = current.street.name.lowercase(),
            board = current.board.map { it.toString() },
            pot = current.players.sumOf { it.contributed },
            buttonSeat = buttonSeat,
            smallBlind = smallBlind,
            bigBlind = bigBlind,
            yourSeat = seat,
            actorSeat = current.actorSeat,
            players = current.players.sortedBy { it.seat }.map { player ->
                val shown = player.seat == seat ||
                    (result?.wentToShowdown == true && player.isContesting)
                PlayerView(
                    seat = player.seat,
                    name = names.getValue(player.seat),
                    stack = player.stack,
                    committed = player.committed,
                    status = player.status.name.lowercase(),
                    cards = if (shown) current.holeCards.getValue(player.seat).map { it.toString() } else null,
                )
            },
            legal = if (current.actorSeat == seat) current.legalActions().toView() else null,
            result = result?.let {
                ResultView(
                    winnings = it.winnings,
                    showdown = it.showdown.mapValues { (_, rank) -> rank.category.name.lowercase() },
                )
            },
        )
    }

    private fun seatAfter(seat: Int): Int {
        val ordered = names.keys.sorted()
        return ordered[(ordered.indexOf(seat) + 1) % ordered.size]
    }

    private fun com.banca.games.poker.LegalActions.toView() = LegalView(
        canFold = canFold,
        canCheck = canCheck,
        canCall = canCall,
        callCost = callCost,
        canBet = canBet,
        minBet = minBet,
        canRaise = canRaise,
        minRaiseTo = minRaiseTo,
        maxTo = maxTo,
    )
}

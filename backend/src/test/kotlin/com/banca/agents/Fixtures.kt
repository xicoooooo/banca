package com.banca.agents

import com.banca.sessions.LegalView
import com.banca.sessions.PlayerView
import com.banca.sessions.TableView

/** A flop decision for seat 1, facing a bet of 100 into a pot that is now 300. */
fun facingABet(
    cards: List<String> = listOf("Ah", "Ad"),
    board: List<String> = listOf("Ks", "7d", "2c"),
) = TableView(
    handNumber = 1,
    street = "flop",
    board = board,
    pot = 300,
    buttonSeat = 0,
    smallBlind = 10,
    bigBlind = 20,
    yourSeat = 1,
    actorSeat = 1,
    players = listOf(
        PlayerView(seat = 0, name = "You", stack = 1800, committed = 100, status = "active", cards = null),
        PlayerView(seat = 1, name = "Banca", stack = 1900, committed = 0, status = "active", cards = cards),
    ),
    legal = LegalView(
        canFold = true,
        canCheck = false,
        canCall = true,
        callCost = 100,
        canBet = false,
        minBet = 20,
        canRaise = true,
        minRaiseTo = 200,
        maxTo = 1900,
    ),
    result = null,
)

/** A flop decision for seat 1 with nothing to call. */
fun checkedTo() = facingABet().let { view ->
    view.copy(
        pot = 200,
        players = view.players.map { it.copy(committed = 0) },
        legal = view.legal!!.copy(
            canCheck = true,
            canCall = false,
            callCost = 0,
            canBet = true,
            canRaise = false,
            minRaiseTo = 20,
        ),
    )
}

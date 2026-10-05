package com.banca.sessions

import com.banca.games.poker.Action

/** Whatever decides for a seat that no human is sitting in. */
fun interface SeatDriver {
    suspend fun decide(view: TableView): Action
}

/**
 * Checks when it can and calls when it cannot. A stand-in that keeps the table
 * playable end to end until the agent takes this seat.
 */
class PassiveBot : SeatDriver {
    override suspend fun decide(view: TableView): Action {
        val legal = view.legal ?: error("Asked to act out of turn")
        return if (legal.canCheck) Action.Check else Action.Call
    }
}

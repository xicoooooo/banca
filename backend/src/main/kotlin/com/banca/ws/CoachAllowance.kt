package com.banca.ws

import com.banca.Allowance
import com.banca.Limit
import com.banca.agents.BlackjackAdvisor
import com.banca.agents.BookAdvisor
import com.banca.agents.BookAnalyst
import com.banca.agents.BookPokerAdvisor
import com.banca.agents.PokerAdvisor
import com.banca.agents.RouletteAdvisor
import com.banca.players.PlayerSession
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * How often one player may have Banca think for them.
 *
 * The model behind the coach is a free allowance shared by everyone, so one
 * player asking over and over could leave nothing for the rest. Each player
 * has a share of their own, across all three games. A player who has used it
 * is not refused: they are answered from the figures, which is the same answer
 * without the model's words, until their share comes round again.
 */
class CoachAllowance(
    private val each: Allowance = Allowance(Limit(15, 10.minutes), Limit(100, 24.hours)),
) {
    private fun mayAsk(session: PlayerSession): Boolean = each.take(session.player.id.toString())

    fun blackjack(coach: BlackjackAdvisor, session: PlayerSession, book: BlackjackAdvisor = BookAdvisor()) =
        BlackjackAdvisor { view, trace -> (if (mayAsk(session)) coach else book).advise(view, trace) }

    fun poker(coach: PokerAdvisor, session: PlayerSession, book: PokerAdvisor = BookPokerAdvisor()) =
        PokerAdvisor { view, trace -> (if (mayAsk(session)) coach else book).advise(view, trace) }

    fun roulette(analyst: RouletteAdvisor, session: PlayerSession, book: RouletteAdvisor = BookAnalyst()) =
        RouletteAdvisor { wagers, chips, trace -> (if (mayAsk(session)) analyst else book).read(wagers, chips, trace) }
}

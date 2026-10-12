package com.banca.players

import kotlinx.serialization.Serializable

/**
 * Something worth remembering that happened to a player at the tables. [kind]
 * says which sort of thing it was, for whatever draws it; [title] and [detail]
 * say it in words; [at] is when.
 */
@Serializable
data class MomentView(val kind: String, val title: String, val detail: String, val at: String)

/**
 * The moments in a player's record: the rare hands, the number that came in,
 * the run of wins. They are the same things the tables stop to celebrate as
 * they happen, found again afterwards in the rounds as they were written down,
 * so nothing extra is stored and none can be claimed that did not happen.
 */
object Moments {

    /** The Hold'em hands rare enough to stop the table for, by the name the table gives them. */
    val RARE_HANDS = mapOf(
        "four_of_a_kind" to "Four of a kind",
        "straight_flush" to "Straight flush",
        "royal_flush" to "Royal flush",
    )

    /** A spin is a big one when it wins this many times everything that was staked on it. */
    const val BIG_SPIN = 10

    /** Blackjack rounds won one after another before it counts as a run. */
    const val RUN = 5

    /**
     * @param rounds newest first
     * @return the newest moments first, and no more than [most] of them
     */
    fun of(rounds: List<RoundRecord>, most: Int = 12): List<MomentView> {
        val found = mutableListOf<MomentView>()
        var run = 0

        // Oldest first, so that a run of wins is counted in the order it was won.
        for (round in rounds.asReversed()) {
            val at = round.endedAt.toString()
            val won = round.outcome == RoundOutcome.WIN

            when (round.game) {
                Game.POKER -> RARE_HANDS[round.detail.text("hand")]?.takeIf { won }?.let { hand ->
                    val pot = round.detail.number("pot")
                    found += MomentView("poker_hand", hand, if (pot != null) "Took a pot of ${chips(pot)} at Hold'em" else "Won with it at Hold'em", at)
                }
                // A number is 35 to 1, so a spin that wins this much is one, two or three numbers backed and
                // one of them right. Whoever covers half the wheel hits a number most spins, for little.
                Game.ROULETTE -> if (round.detail.flag("straightHit") && round.net >= BIG_SPIN * round.staked) {
                    val pocket = round.detail.number("pocket")
                    val came = if (pocket != null) "Number $pocket came in" else "Your number came in"
                    found += MomentView("straight_up", "Straight up", "$came for +${chips(round.net)}", at)
                }
                Game.BLACKJACK -> {
                    run = if (won) run + 1 else 0
                    // Said once, as the run is reached, and not again for every win that adds to it.
                    if (run == RUN) found += MomentView("blackjack_run", "Five in a row", "Five rounds of blackjack won one after another", at)
                }
            }
        }
        return found.asReversed().take(most)
    }

    private fun chips(amount: Long): String = "%,d".format(java.util.Locale.US, amount)
}

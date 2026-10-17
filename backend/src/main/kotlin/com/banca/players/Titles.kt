package com.banca.players

import kotlinx.serialization.Serializable

/**
 * A title a player may wear beside their name. [how] is what earns it, said
 * for the player who has not yet; [earned] is whether this player has.
 */
@Serializable
data class TitleView(val name: String, val how: String, val earned: Boolean)

/**
 * The titles there are, and which of them a player has earned.
 *
 * Like everything else about a player's standing, they are worked out from
 * the record and not stored: a level reached, a place won in a league, or a
 * feat at the tables. Every one of them, once earned, stays earned, because
 * each counts something that only ever goes up. So a title a player has
 * chosen to wear can never stop being theirs.
 */
object Titles {

    /** What a player who has won no trophy is shown in place of the titles the leagues give. */
    private val A_LEAGUE_TITLE = TitleView("A league title", "Finish in the top three of your league", earned = false)

    /**
     * @param rounds newest first
     * @param trophies as they are shown, each with its own title
     * @param rivalry the record against Banca from the same rounds, when it has already been worked out
     */
    fun all(level: Int, rounds: List<RoundRecord>, trophies: List<TrophyView>, rivalry: Rivalry? = DashboardBuilder.rivalry(rounds)): List<TitleView> {
        fun count(test: (RoundRecord) -> Boolean) = rounds.count(test)

        val byLevel = Levels.TITLES.map { (from, name) ->
            TitleView(name, if (from <= 1) "Yours from the start" else "Reach level $from", earned = level >= from)
        }

        val feats = listOf(
            feat("Banca's Rival", "Play 100 hands of Hold'em against Banca alone", rivalry?.hands ?: 0, 100),
            feat("Bluff Catcher", "Call down and beat 5 of Banca's bluffs", rivalry?.bluffs?.caught ?: 0, 5),
            feat(
                "Showdown Artist", "Win 25 Hold'em showdowns",
                count { it.game == Game.POKER && it.outcome == RoundOutcome.WIN && it.detail.flag("showdown") }, 25,
            ),
            feat("By the Book", "Make 50 blackjack decisions that were the best play", rounds.sumOf { it.detail.count("byTheBook") }, 50),
            feat("Lucky Number", "Hit a single number at roulette 3 times", count { it.game == Game.ROULETTE && it.detail.flag("straightHit") }, 3),
        )

        // A trophy won twice is one title, and the higher places are listed first.
        val fromLeagues = trophies
            .sortedWith(compareByDescending<TrophyView> { Leagues.TIERS.indexOf(it.league) }.thenBy { it.position })
            .map { it.title }.distinct()
            .map { TitleView(it, "Won in the weekly leagues", earned = true) }

        return byLevel + feats + fromLeagues.ifEmpty { listOf(A_LEAGUE_TITLE) }
    }

    /** Whether [title] is one of those the player has earned, and so may wear. */
    fun mayWear(title: String, titles: List<TitleView>): Boolean = titles.any { it.earned && it.name == title }

    private fun feat(name: String, what: String, done: Int, needed: Int) =
        TitleView(name, if (done in 1 until needed) "$what ($done so far)" else what, earned = done >= needed)
}

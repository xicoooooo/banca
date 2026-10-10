package com.banca.agents

import com.banca.sessions.TableView
import kotlin.random.Random

/**
 * What Banca says to the table when a hand is over.
 *
 * The lines are written down here, not asked of a model. A free model has only
 * so much to give in a minute and every table shares it, so it is kept for
 * deciding hands. A remark is chosen by how the hand went for Banca and the
 * mood it was in, and most hands pass without one: an opponent that talks
 * after every hand is soon not listened to.
 *
 * Nothing is said that the table cannot already see. A remark comes only once
 * the hand is over, and one made after a fold says nothing of the cards that
 * were folded.
 */
object Banter {

    /** How a hand ended, from Banca's side of the table. */
    enum class Moment(val often: Double, val oftenWhenBig: Double = often) {
        /** Won at a showdown. */
        TOOK(often = 0.25, oftenWhenBig = 0.9),
        /** Everyone folded to a bet it made with nothing. */
        STOLE(often = 0.7),
        /** Everyone folded, and it was not bluffing. */
        UNCALLED(often = 0.15, oftenWhenBig = 0.3),
        /** Called while bluffing, and shown up. */
        CAUGHT(often = 0.9),
        /** Lost a showdown with a hand that usually wins one. */
        OUTDRAWN(often = 0.9),
        /** Lost a showdown. */
        BEATEN(often = 0.2, oftenWhenBig = 0.6),
        /** Folded, and the pot went to whoever made it fold. */
        FOLDED(often = 0.12, oftenWhenBig = 0.5),
        /** Folded, and watched the others play it out. */
        WATCHED(often = 0.0, oftenWhenBig = 0.3),
        SPLIT(often = 0.6),
    }

    /** A pot worth remarking on, in big blinds. */
    private const val BIG_POT = 20

    /** Hands Banca expects to win with, by the name the table is given for them. */
    private val STRONG = setOf("two_pair", "three_of_a_kind", "straight", "flush", "full_house", "four_of_a_kind", "straight_flush")
    private val WEAK = setOf("high_card", "pair")

    /** Stands in a line for the name of the player who won the hand. */
    private const val WINNER = "{winner}"

    /**
     * What Banca has to say about the finished hand in [view], which is its
     * own view of it, or null when it says nothing. [net] is what the hand
     * came to for it, [bluffed] whether it bet with nothing during the hand,
     * and [not] a line it is not to repeat.
     */
    fun after(view: TableView, net: Long, mood: Mood, bluffed: Boolean, random: Random, not: String? = null): String? {
        val moment = momentOf(view, net, bluffed) ?: return null
        val big = view.pot >= BIG_POT * view.bigBlind
        if (random.nextDouble() >= if (big) moment.oftenWhenBig else moment.often) return null

        // A winner is spoken to by name only when there is one, and the table knows them by one.
        val winner = soleWinner(view)?.takeIf { it != "You" }
        val lines = (GENERAL.getValue(moment) + (BY_MOOD[mood.name]?.get(moment) ?: emptyList()))
            .filter { winner != null || WINNER !in it }
            .map { it.replace(WINNER, winner ?: "") }
            .filter { it != not }
        return lines.randomOrNull(random)
    }

    /**
     * Which kind of ending [view] shows, or null while the hand is still being
     * played. It goes by [net] and not by the chips paid out, because a bet
     * nobody could match comes back to whoever made it and is no win.
     */
    fun momentOf(view: TableView, net: Long, bluffed: Boolean): Moment? {
        val result = view.result ?: return null
        val me = view.players.firstOrNull { it.seat == view.yourSeat } ?: return null
        val showdown = result.showdown.isNotEmpty()
        val mine = result.showdown[view.yourSeat]

        return when {
            me.status == "folded" -> if (showdown) Moment.WATCHED else Moment.FOLDED
            // A tie gives everyone their own chips back, give or take the odd one.
            showdown && net in -1..1 -> Moment.SPLIT
            net > 0 && showdown -> Moment.TOOK
            net > 0 -> if (bluffed) Moment.STOLE else Moment.UNCALLED
            !showdown -> null
            bluffed && mine in WEAK -> Moment.CAUGHT
            mine in STRONG -> Moment.OUTDRAWN
            else -> Moment.BEATEN
        }
    }

    /** The one other player the hand paid, by name, when there is exactly one. */
    private fun soleWinner(view: TableView): String? {
        val paid = view.result?.winnings?.filter { it.value > 0 && it.key != view.yourSeat }?.keys ?: return null
        val seat = paid.singleOrNull() ?: return null
        return view.players.firstOrNull { it.seat == seat }?.name
    }

    private val GENERAL: Map<Moment, List<String>> = mapOf(
        Moment.TOOK to listOf(
            "That will do nicely.",
            "I'll look after those for you.",
            "The house thanks you.",
            "I did wonder if you'd pay to see it.",
            "A pleasure, as always.",
            "Mine, I think.",
        ),
        Moment.STOLE to listOf(
            "You'll never know.",
            "Good fold. Probably.",
            "I'd tell you what I had, but where's the fun in that.",
            "A wise fold. Or was it?",
            "Thank you. Don't think about it too long.",
        ),
        Moment.UNCALLED to listOf(
            "No takers?",
            "I'll take that, then.",
            "Nobody? Very well.",
            "Thank you. Next.",
        ),
        Moment.CAUGHT to listOf(
            "Caught. It happens.",
            "Ah. You looked.",
            "Worth a try.",
            "Fine. That one was air.",
            "You weren't supposed to call that.",
            "Good call, $WINNER. Don't make a habit of it.",
        ),
        Moment.OUTDRAWN to listOf(
            "Well. That's unkind.",
            "I had it. Then I didn't.",
            "The cards owe me one.",
            "Noted.",
            "Enjoy it, $WINNER. I'm counting.",
        ),
        Moment.BEATEN to listOf(
            "Yours. Well played.",
            "Fair enough.",
            "Nicely done.",
            "Keep them warm for me.",
            "Well played, $WINNER.",
        ),
        Moment.FOLDED to listOf(
            "Not this one.",
            "Yours.",
            "I'll pass.",
            "I know when I'm beaten. Sometimes.",
            "Take it. I'll find a better spot.",
            "Go on then, $WINNER.",
        ),
        Moment.WATCHED to listOf(
            "I was better off out of that.",
            "An expensive hand. Not for me.",
            "Glad I only watched.",
        ),
        Moment.SPLIT to listOf(
            "We share it. How civilised.",
            "Half each. Nobody's happy.",
            "Call it a draw.",
        ),
    )

    /** What each mood adds to the lines above, in its own voice. */
    private val BY_MOOD: Map<String, Map<Moment, List<String>>> = mapOf(
        Moods.STEADY.name to mapOf(
            Moment.TOOK to listOf("Nothing clever. Just the better hand."),
            Moment.BEATEN to listOf("The better hand won. I can live with that."),
        ),
        Moods.PATIENT.name to mapOf(
            Moment.TOOK to listOf("Worth the wait.", "I told you I was in no hurry."),
            Moment.UNCALLED to listOf("I can wait for a bigger one."),
            Moment.BEATEN to listOf("I should have waited."),
            Moment.FOLDED to listOf("I can wait.", "There will be other hands."),
        ),
        Moods.PRESSING.name to mapOf(
            Moment.TOOK to listOf("It costs to stay in. I did say."),
            Moment.STOLE to listOf("Somebody had to want it."),
            Moment.CAUGHT to listOf("I'll try it again, you know."),
            Moment.BEATEN to listOf("It was never going to work every time."),
            Moment.FOLDED to listOf("Enjoy it while it lasts."),
        ),
        Moods.SLY.name to mapOf(
            Moment.TOOK to listOf("I looked weak, didn't I.", "You were meant to bet into that."),
            Moment.STOLE to listOf("Sleep well."),
            Moment.CAUGHT to listOf("Don't get used to it."),
            Moment.FOLDED to listOf("Or perhaps I had it. You'll never know."),
        ),
    )
}

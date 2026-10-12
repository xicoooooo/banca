package com.banca.agents

import com.banca.games.blackjack.BlackjackAction
import com.banca.games.blackjack.Rules
import com.banca.games.blackjack.Strategy
import com.banca.games.blackjack.valueOf
import com.banca.games.cards.Card
import com.banca.games.cards.Rank
import com.banca.games.cards.Suit
import com.banca.sessions.BlackjackHandView
import com.banca.sessions.BlackjackLegalView
import com.banca.sessions.BlackjackView
import com.banca.sessions.DealerView
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * One way a drill's hand could be played, graded. [verdict] is "best", "slip"
 * for a play that gives up a little, or "mistake". [cost] is the chips it
 * gives up on average for every hundred staked, nought for the best.
 */
@Serializable
data class DrillPlay(val action: String, val verdict: String, val cost: Double)

/**
 * A hand to decide on: the player's two cards, the dealer's face-up card, and
 * every play open with how good it is. [reason] is why the [best] one is best.
 */
@Serializable
data class DrillQuestion(
    val cards: List<String>,
    val total: Int,
    val soft: Boolean,
    val dealer: String,
    val plays: List<DrillPlay>,
    val best: String,
    val reason: String,
)

/** A drill: some hands to decide on, and how many seconds each is given. */
@Serializable
data class Drill(val questions: List<DrillQuestion>, val secondsEach: Int)

/**
 * Practice at blackjack decisions, away from a table and for no chips.
 *
 * A drill is dealt from the hands worth practising: the stiff totals that are
 * hit against some dealer cards and stood on against others, the totals to
 * double on, the soft hands, and the pairs. The hands nobody gets wrong, a
 * hard twenty or a hard six, are left out.
 *
 * Every play is graded by the same arithmetic as the coach and the review
 * after a round, so a drill teaches what the table will later hold a player
 * to. The answers travel with the questions: nothing rides on a drill, so
 * there is nothing to be gained by peeking, and being told at once is the
 * whole point.
 */
class BlackjackDrills(rules: Rules = Rules()) {

    private val strategy = Strategy(rules)

    /** A kind of hand, with the pairs of card points that make it. An ace is one. */
    private class Kind(val share: Int, val hands: List<Pair<Int, Int>>)

    private val kinds = listOf(
        // Stiff hands, twelve to sixteen, made without an ace and not as a pair.
        Kind(share = 4, hands = pairsOf(2..10).filter { (a, b) -> a != b && a + b in 12..16 }),
        // Nine, ten and eleven, where doubling is in question.
        Kind(share = 2, hands = pairsOf(2..9).filter { (a, b) -> a != b && a + b in 9..11 }),
        // An ace with a small card: soft thirteen to soft eighteen.
        Kind(share = 2, hands = (2..7).map { ACE to it }),
        Kind(share = 2, hands = (ACE..10).map { it to it }),
    )

    /** Deals a drill of [size] hands, no two of them the same hand against the same card. */
    fun deal(random: Random, size: Int = SIZE): Drill {
        val dealt = LinkedHashSet<Triple<Int, Int, Int>>()
        val wanted = kinds.flatMap { kind -> List(kind.share) { kind } }.shuffled(random)

        var at = 0
        while (dealt.size < size) {
            val (first, second) = wanted[at++ % wanted.size].hands.random(random)
            dealt += Triple(first, second, random.nextInt(ACE, 11))
        }
        return Drill(questions = dealt.map { (first, second, dealer) -> question(first, second, dealer, random) }, secondsEach = SECONDS_EACH)
    }

    /** The hand of [first] and [second] against a dealer showing [dealer], all as card points, with every play graded. */
    fun question(first: Int, second: Int, dealer: Int, random: Random): DrillQuestion {
        val cards = listOf(card(first, random), card(second, random))
        val shows = card(dealer, random)
        val value = valueOf(cards)
        val seen = valueOf(listOf(shows))

        // The hand as a player at a table would see it at their first decision, staking a hundred.
        val view = BlackjackView(
            roundNumber = 0,
            phase = "player",
            stack = 0,
            minBet = STAKE,
            maxBet = STAKE,
            lastBet = null,
            dealer = DealerView(cards = listOf(shows.toString(), null), total = seen.total, soft = seen.soft),
            hands = listOf(BlackjackHandView(cards.map { it.toString() }, STAKE, value.total, value.soft, status = "playing", outcome = null, returned = null)),
            activeHand = 0,
            legal = BlackjackLegalView(bet = false, hit = true, stand = true, double = true, split = first == second, insurance = false),
            insuranceCost = 0,
            result = null,
        )
        val tools = BlackjackTools(view, strategy)

        return DrillQuestion(
            cards = view.hands.single().cards,
            total = value.total,
            soft = value.soft,
            dealer = shows.toString(),
            plays = PLAYS.filter { play -> tools.values.any { it.action == play } }.map { play ->
                val review = tools.review(play, advice = null)
                DrillPlay(BlackjackTools.nameOf(play), review.verdict, review.cost)
            },
            best = BlackjackTools.nameOf(tools.best.action),
            reason = tools.bookAdvice().reason,
        )
    }

    /** A card worth [points], an ace being one and a ten being any of the four ranks that count ten. */
    private fun card(points: Int, random: Random): Card {
        val rank = when (points) {
            ACE -> Rank.ACE
            10 -> listOf(Rank.TEN, Rank.JACK, Rank.QUEEN, Rank.KING).random(random)
            else -> Rank.entries.first { it.value == points }
        }
        return Card(rank, Suit.entries.random(random))
    }

    private fun pairsOf(points: IntRange): List<Pair<Int, Int>> = points.flatMap { a -> points.filter { it >= a }.map { b -> a to b } }

    companion object {
        const val SIZE = 10
        const val SECONDS_EACH = 8
        private const val ACE = 1
        private const val STAKE = 100L

        /** The order the plays are offered in, which is the order of the buttons at a table. */
        private val PLAYS = listOf(BlackjackAction.Hit, BlackjackAction.Stand, BlackjackAction.Double, BlackjackAction.Split)
    }
}

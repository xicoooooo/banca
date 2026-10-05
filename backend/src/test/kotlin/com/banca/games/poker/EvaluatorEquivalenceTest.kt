package com.banca.games.poker

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fast evaluator is only trusted because it gives the same answer as the
 * slow, plainly correct one on a great many hands.
 */
class EvaluatorEquivalenceTest {

    private fun randomHands(size: Int, count: Int, seed: Int): Sequence<List<Card>> {
        val random = Random(seed)
        val deck = Deck.full()
        return generateSequence { deck.shuffled(random).take(size) }.take(count)
    }

    @Test
    fun `agrees with the reference on random five, six and seven card hands`() {
        for (size in 5..7) {
            randomHands(size, count = 60_000, seed = size).forEach { hand ->
                assertEquals(ReferenceEvaluator.evaluate(hand), HandEvaluator.evaluate(hand), "disagreement on $hand")
            }
        }
    }

    @Test
    fun `agrees with the reference on hands crowded into few ranks and suits`() {
        // Random deals rarely produce quads, two sets of trips, three pairs or
        // six to a flush, so these are dealt from short decks that force them.
        val random = Random(99)
        val fewRanks = Deck.full().filter { it.rank.value >= 11 }
        val oneSuitHeavy = Deck.full().filter { it.suit == Suit.SPADES || it.rank.value >= 12 }
        val lowRun = Deck.full().filter { it.rank.value <= 7 || it.rank == Rank.ACE }

        for (deck in listOf(fewRanks, oneSuitHeavy, lowRun)) {
            repeat(40_000) {
                val hand = deck.shuffled(random).take(random.nextInt(5, 8))
                assertEquals(ReferenceEvaluator.evaluate(hand), HandEvaluator.evaluate(hand), "disagreement on $hand")
            }
        }
    }

    @Test
    fun `every category turns up in the hands compared`() {
        val seen = randomHands(7, 60_000, seed = 7)
            .map { HandEvaluator.evaluate(it).category }
            .toSet()
        // A straight flush is too rare to count on in a random sample; it is
        // forced by the short-deck test above and by the hand-written tests.
        assertTrue(seen.containsAll(HandCategory.entries - HandCategory.STRAIGHT_FLUSH), "saw only $seen")
    }

    @Test
    fun `is much faster than the reference`() {
        val hands = randomHands(7, 20_000, seed = 1).toList()
        // Let both be compiled before timing either.
        repeat(3) { hands.forEach { HandEvaluator.evaluate(it); ReferenceEvaluator.evaluate(it) } }

        val fast = measure { hands.forEach { HandEvaluator.evaluate(it) } }
        val slow = measure { hands.forEach { ReferenceEvaluator.evaluate(it) } }

        println("evaluating 20,000 seven-card hands: fast ${fast / 1_000_000} ms, reference ${slow / 1_000_000} ms")
        assertTrue(fast * 3 < slow, "expected at least three times faster, got ${slow.toDouble() / fast}x")
    }

    private fun measure(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }
}

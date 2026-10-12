package com.banca.players

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The moments found in a player's record: which rounds count, and how they are told. */
class MomentsTest {

    private val start = Instant.parse("2026-10-06T12:00:00Z")

    /** Rounds in the order they were played, each a minute after the last, handed over newest first as a store does. */
    private fun played(vararg rounds: Triple<Game, Long, JsonObject>): List<RoundRecord> =
        rounds.mapIndexed { index, (game, net, detail) ->
            RoundRecord(
                game = game,
                endedAt = start.plusSeconds(index * 60L),
                staked = 100,
                net = net,
                outcome = if (net > 0) RoundOutcome.WIN else if (net < 0) RoundOutcome.LOSS else RoundOutcome.PUSH,
                detail = detail,
            )
        }.reversed()

    private fun hand(name: String, pot: Long = 800) = buildJsonObject { put("hand", name); put("pot", pot); put("showdown", true) }
    private val plain = buildJsonObject { }
    private fun blackjack(net: Long) = Triple(Game.BLACKJACK, net, plain)

    @Test
    fun `an ordinary record has no moments`() {
        val rounds = played(
            Triple(Game.POKER, 200, hand("full_house")),
            Triple(Game.ROULETTE, 100, buildJsonObject { put("straightHit", false); put("pocket", 4) }),
            blackjack(100), blackjack(-100), blackjack(100),
        )

        assertTrue(Moments.of(rounds).isEmpty())
    }

    @Test
    fun `a rare hand is a moment when it wins, told with the pot it took`() {
        val rounds = played(
            Triple(Game.POKER, 620, hand("four_of_a_kind", pot = 1_240)),
            Triple(Game.POKER, -300, hand("straight_flush")),
            Triple(Game.POKER, 2_000, hand("royal_flush", pot = 4_000)),
        )
        val moments = Moments.of(rounds)

        assertEquals(listOf("Royal flush", "Four of a kind"), moments.map { it.title }, "newest first, and not the one that lost")
        assertEquals("Took a pot of 1,240 at Hold'em", moments.last().detail)
        assertEquals("poker_hand", moments.last().kind)
        assertEquals(start.toString(), moments.last().at)
    }

    @Test
    fun `a number that came in is a moment, with the number and what it paid`() {
        // A hundred chips on one number, which pays 35 to 1.
        val moments = Moments.of(played(Triple(Game.ROULETTE, 3_500, buildJsonObject { put("straightHit", true); put("pocket", 17) })))

        assertEquals(listOf(MomentView("straight_up", "Straight up", "Number 17 came in for +3,500", start.toString())), moments)
    }

    @Test
    fun `a number that came in is no moment for someone who covered much of the wheel`() {
        fun hit(net: Long) = Triple(Game.ROULETTE, net, buildJsonObject { put("straightHit", true); put("pocket", 0) })

        // Each of these staked a hundred chips in all.
        assertTrue(Moments.of(played(hit(-40))).isEmpty(), "it lost chips all told")
        assertTrue(Moments.of(played(hit(500))).isEmpty(), "it won, but not many times the stake")
        assertEquals(1, Moments.of(played(hit(1_000))).size, "ten times the stake is where a big spin begins")
    }

    @Test
    fun `five blackjack rounds won running is a moment, told once however long the run goes on`() {
        val rounds = played(*Array(8) { blackjack(100) })
        val moments = Moments.of(rounds)

        assertEquals(1, moments.size)
        assertEquals("Five in a row", moments.single().title)
        assertEquals(start.plusSeconds(4 * 60).toString(), moments.single().at, "at the fifth win, not the eighth")
    }

    @Test
    fun `a loss or a push ends a run, and other games played in between do not`() {
        val broken = played(blackjack(100), blackjack(100), blackjack(100), blackjack(100), blackjack(0), blackjack(100))
        assertTrue(Moments.of(broken).isEmpty())

        val between = played(
            blackjack(100), blackjack(100), Triple(Game.POKER, -50, plain), blackjack(100),
            Triple(Game.ROULETTE, -10, plain), blackjack(100), blackjack(100),
        )
        assertEquals(1, Moments.of(between).size)
    }

    @Test
    fun `a second run after the first was broken is a second moment`() {
        val rounds = played(*Array(5) { blackjack(100) }, blackjack(-100), *Array(5) { blackjack(100) })

        assertEquals(2, Moments.of(rounds).size)
    }

    @Test
    fun `only the newest are kept`() {
        val rounds = played(*Array(20) { Triple(Game.POKER, 100L + it, hand("four_of_a_kind", pot = 1_000L + it)) })
        val moments = Moments.of(rounds, most = 12)

        assertEquals(12, moments.size)
        assertEquals("Took a pot of 1,019 at Hold'em", moments.first().detail)
    }
}

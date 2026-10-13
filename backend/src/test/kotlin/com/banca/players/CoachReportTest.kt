package com.banca.players

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Banca's report on a week: which week it reports on, what it says of each game, and the one thing it picks out. */
class CoachReportTest {

    /** A Thursday. The week it falls in began on Monday the 5th. */
    private val now = Instant.parse("2026-10-08T12:00:00Z")
    private val thisWeek = Instant.parse("2026-10-06T10:00:00Z")
    private val lastWeek = Instant.parse("2026-09-30T10:00:00Z")

    private fun round(game: Game, net: Long, detail: JsonObject = buildJsonObject { }, at: Instant = thisWeek, staked: Long = 100) = RoundRecord(
        game = game,
        endedAt = at,
        staked = staked,
        net = net,
        outcome = if (net > 0) RoundOutcome.WIN else if (net < 0) RoundOutcome.LOSS else RoundOutcome.PUSH,
        detail = detail,
    )

    /** A round of blackjack with [decisions] in it, [sound] of them the best play. */
    private fun blackjack(decisions: Int, sound: Int, net: Long = 100, givenUp: Double = 0.0, insured: Boolean = false, at: Instant = thisWeek) =
        round(Game.BLACKJACK, net, buildJsonObject {
            put("decisions", decisions); put("byTheBook", sound); put("givenUp", givenUp); put("insured", insured)
        }, at)

    private fun poker(net: Long = 0, folded: Boolean = false, showdown: Boolean = false, calls: Int = 0, raises: Int = 0, bets: Int = 0, at: Instant = thisWeek) =
        round(Game.POKER, net, buildJsonObject {
            put("folded", folded); put("showdown", showdown); put("calls", calls); put("raises", raises); put("bets", bets)
        }, at)

    private fun report(vararg rounds: RoundRecord) = CoachReport.of(rounds.toList(), now)

    @Test
    fun `there is no report on a week with too little in it`() {
        assertNull(report())
        assertNull(report(*Array(9) { blackjack(2, 2) }))
    }

    @Test
    fun `the report is on the week in play once there is enough of it`() {
        val report = assertNotNull(report(*Array(10) { blackjack(2, 2, net = 50) }, *Array(20) { blackjack(2, 1, at = lastWeek) }))

        assertEquals("2026-10-05", report.week)
        assertTrue(report.current)
        assertEquals(10, report.rounds)
        assertEquals(500, report.net)
    }

    @Test
    fun `until then it is on the week before`() {
        val report = assertNotNull(report(*Array(3) { blackjack(2, 2) }, *Array(12) { blackjack(2, 2, net = -100, at = lastWeek) }))

        assertEquals("2026-09-28", report.week)
        assertEquals(false, report.current)
        assertEquals(12, report.rounds)
        assertEquals(-1_200, report.net)
    }

    @Test
    fun `rounds from longer ago are no part of it`() {
        val longAgo = Instant.parse("2026-09-01T10:00:00Z")

        assertNull(report(*Array(40) { blackjack(2, 2, at = longAgo) }))
    }

    @Test
    fun `blackjack is reported by how many decisions were the best play, set against the week before`() {
        val report = assertNotNull(
            report(
                *Array(10) { blackjack(decisions = 2, sound = 2, net = 100) },
                *Array(2) { blackjack(decisions = 2, sound = 0, net = -100, givenUp = 12.4) },
                *Array(10) { blackjack(decisions = 2, sound = 1, at = lastWeek) },
            ),
        )
        val note = report.notes.single { it.game == "blackjack" }

        assertEquals("Blackjack · 83% by the book", note.title)
        assertEquals(
            "20 of your 24 decisions were the best play, up from 50% the week before. The others gave up about 25 chips between them. You came out at +800 over 12 rounds.",
            note.text,
        )
    }

    @Test
    fun `Hold'em is reported by what was folded, shown down, called and raised`() {
        val report = assertNotNull(
            report(
                *Array(6) { poker(net = -20, folded = true) },
                *Array(3) { poker(net = 200, showdown = true, calls = 2, raises = 1) },
                poker(net = -300, showdown = true, calls = 1, bets = 1),
            ),
        )
        val note = report.notes.single { it.game == "poker" }

        assertEquals("Hold'em · +180 over 10 hands", note.title)
        assertEquals("You folded 6 of them and won 3 of 4 showdowns. You called 7 times and bet or raised 4 times.", note.text)
    }

    @Test
    fun `roulette is reported against what the wheel keeps`() {
        val report = assertNotNull(report(*Array(10) { round(Game.ROULETTE, net = -37, staked = 370) }))
        val note = report.notes.single { it.game == "roulette" }

        assertEquals("Roulette · −370 over 10 spins", note.title)
        assertTrue("On the 3,700 you staked that comes to about 100" in note.text, note.text)
        assertTrue(note.text.endsWith("this week against you."), note.text)
    }

    @Test
    fun `a game hardly played that week is left out`() {
        val report = assertNotNull(report(*Array(10) { blackjack(2, 2) }, *Array(4) { poker() }, round(Game.ROULETTE, 10)))

        assertEquals(listOf("blackjack"), report.notes.map { it.game })
    }

    @Test
    fun `insurance taken more than once is the first thing picked out`() {
        val report = assertNotNull(report(*Array(8) { blackjack(2, 1) }, *Array(2) { blackjack(2, 2, insured = true) }))

        assertEquals("Leave insurance alone", report.focus?.title)
        assertEquals("blackjack", report.focus?.go)
        assertTrue("You took insurance twice." in report.focus!!.text)
    }

    @Test
    fun `loose blackjack decisions are sent to the drill`() {
        val report = assertNotNull(report(*Array(10) { blackjack(decisions = 2, sound = 1) }))

        assertEquals("Tighten up at blackjack", report.focus?.title)
        assertEquals("blackjack/drill", report.focus?.go)
        assertTrue(report.focus!!.text.startsWith("50% of your blackjack decisions"))
    }

    @Test
    fun `calling far more than betting is picked out at Hold'em`() {
        val report = assertNotNull(report(*Array(10) { poker(showdown = true, calls = 3) }, poker(raises = 2)))

        assertEquals("Bet more, call less", report.focus?.title)
        assertEquals("poker", report.focus?.go)
        assertTrue("you called 30 times and bet or raised twice" in report.focus!!.text)
    }

    @Test
    fun `folding nearly everything, or nearly nothing, is picked out`() {
        val tight = assertNotNull(report(*Array(12) { poker(folded = true) }, *Array(4) { poker(showdown = true, raises = 1) }))
        assertEquals("Give up fewer hands", tight.focus?.title)

        val loose = assertNotNull(report(*Array(2) { poker(folded = true) }, *Array(14) { poker(showdown = true, raises = 1) }))
        assertEquals("Let more hands go", loose.focus?.title)
    }

    @Test
    fun `a week lost at the wheel is pointed to a game where the play matters`() {
        val report = assertNotNull(report(*Array(10) { round(Game.ROULETTE, -50) }, *Array(3) { poker() }))

        assertEquals("Try a game where the play matters", report.focus?.title)
    }

    @Test
    fun `blackjack played by the book is said to be`() {
        val report = assertNotNull(report(*Array(10) { blackjack(2, 2) }))

        assertEquals("Nothing to correct at blackjack", report.focus?.title)
        assertNull(report.focus?.go)
    }

    @Test
    fun `an unremarkable week has nothing picked out`() {
        val report = assertNotNull(report(*Array(10) { blackjack(decisions = 10, sound = 9) }))

        assertNull(report.focus)
    }
}

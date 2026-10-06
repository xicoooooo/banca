package com.banca.players

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardBuilderTest {

    private val now = Instant.parse("2026-10-06T12:00:00Z")
    private val player = Player(UUID.randomUUID(), "Ana", Instant.parse("2026-10-01T09:00:00Z"))

    private fun blackjack(net: Long, daysAgo: Long = 0, detail: JsonObject = buildJsonObject { put("total", 19); put("dealerTotal", 18) }) =
        RoundRecord(
            game = Game.BLACKJACK,
            endedAt = now.minusSeconds(daysAgo * 86_400),
            staked = 100,
            net = net,
            outcome = if (net > 0) RoundOutcome.WIN else if (net < 0) RoundOutcome.LOSS else RoundOutcome.PUSH,
            detail = detail,
        )

    private fun poker(net: Long, detail: JsonObject = buildJsonObject { }) =
        blackjack(net, detail = detail).copy(game = Game.POKER)

    /** The ledger a player would have after these rounds, oldest first, from a 2,000 grant. */
    private fun ledgerFor(rounds: List<RoundRecord>) =
        listOf(LedgerEntry(2_000, LedgerReason.SIGNUP_GRANT, player.createdAt)) +
            rounds.reversed().map { LedgerEntry(it.net, LedgerReason.ROUND, it.endedAt) }

    /** [rounds] is newest first, as the store hands them over. */
    private fun build(vararg rounds: RoundRecord): Dashboard {
        val list = rounds.toList()
        val ledger = ledgerFor(list)
        return DashboardBuilder.build(player, ledger.sumOf { it.amount }, list, ledger, now)
    }

    @Test
    fun `a player who has not played has a dashboard with nothing made up`() {
        val dashboard = build()

        assertEquals(2_000, dashboard.bankroll.balance)
        assertEquals(0, dashboard.bankroll.net)
        assertEquals(2_000, dashboard.bankroll.granted)
        assertEquals(0, dashboard.totals.rounds)
        assertNull(dashboard.totals.winRate, "no rate without rounds")
        assertNull(dashboard.totals.biggestWin)
        assertNull(dashboard.totals.averageResult)
        assertNull(dashboard.streaks.currentKind)
        assertEquals(1, dashboard.player.level)
        assertTrue(dashboard.recent.isEmpty())
        assertTrue(dashboard.achievements.none { it.earned })
        assertTrue(dashboard.games.all { it.tendencies.isEmpty() })
    }

    @Test
    fun `totals count wins, losses and pushes and what they added up to`() {
        val dashboard = build(blackjack(150), blackjack(-100), blackjack(0), blackjack(-100), blackjack(300))

        with(dashboard.totals) {
            assertEquals(5, rounds)
            assertEquals(2, wins)
            assertEquals(2, losses)
            assertEquals(1, pushes)
            assertEquals(0.4, winRate)
            assertEquals(300, biggestWin)
            assertEquals(-100, biggestLoss)
            assertEquals(50.0, averageResult)
            assertEquals(500, staked)
        }
        assertEquals(250, dashboard.bankroll.net)
        assertEquals(2_250, dashboard.bankroll.balance)
    }

    @Test
    fun `a player who has only lost has no biggest win`() {
        assertNull(build(blackjack(-100), blackjack(-50)).totals.biggestWin)
    }

    @Test
    fun `the current streak runs back from the latest round and a push does not end it`() {
        // Newest first: win, push, win, win, then a loss before them.
        val dashboard = build(blackjack(100), blackjack(0), blackjack(100), blackjack(100), blackjack(-100), blackjack(100))

        assertEquals("win", dashboard.streaks.currentKind)
        assertEquals(3, dashboard.streaks.current)
        assertEquals(3, dashboard.streaks.bestWin)
        assertEquals(1, dashboard.streaks.worstLoss)
    }

    @Test
    fun `a losing run is reported as one`() {
        val dashboard = build(blackjack(-100), blackjack(-100), blackjack(100))

        assertEquals("loss", dashboard.streaks.currentKind)
        assertEquals(2, dashboard.streaks.current)
        assertEquals(2, dashboard.streaks.worstLoss)
    }

    @Test
    fun `each game is broken down on its own`() {
        val dashboard = build(blackjack(100), poker(-200), poker(400), blackjack(-100))
        val byGame = dashboard.games.associateBy { it.game }

        assertEquals(setOf("poker", "blackjack", "roulette"), byGame.keys, "every game appears, played or not")
        assertEquals(0, byGame.getValue("roulette").rounds)
        assertEquals(2, byGame.getValue("poker").rounds)
        assertEquals(200, byGame.getValue("poker").net)
        assertEquals(0.5, byGame.getValue("poker").winRate)
        assertEquals(0, byGame.getValue("blackjack").net)
    }

    @Test
    fun `the bankroll history follows the ledger and ends at the balance`() {
        val dashboard = build(blackjack(300), blackjack(-100), blackjack(150))

        assertEquals(listOf(2_000L, 2_150L, 2_050L, 2_350L), dashboard.bankroll.history.map { it.balance })
        assertEquals(2_350, dashboard.bankroll.peak)
        assertEquals(dashboard.bankroll.balance, dashboard.bankroll.history.last().balance)
    }

    @Test
    fun `a long history is thinned but keeps its first and last points`() {
        val rounds = (1..500).map { blackjack(if (it % 2 == 0) 10 else -10) }
        val dashboard = build(*rounds.toTypedArray())

        assertEquals(60, dashboard.bankroll.history.size)
        assertEquals(2_000, dashboard.bankroll.history.first().balance)
        assertEquals(dashboard.bankroll.balance, dashboard.bankroll.history.last().balance)
    }

    @Test
    fun `history still ends at the balance when the ledger read does not reach the start`() {
        val recent = listOf(LedgerEntry(100, LedgerReason.ROUND, now), LedgerEntry(-50, LedgerReason.ROUND, now))
        val dashboard = DashboardBuilder.build(player, balance = 9_000, rounds = emptyList(), ledger = recent, now = now)

        assertEquals(listOf(9_050L, 9_000L), dashboard.bankroll.history.map { it.balance })
    }

    @Test
    fun `activity covers the last fourteen days, quiet ones included`() {
        val dashboard = build(blackjack(100), blackjack(-50), blackjack(200, daysAgo = 3), blackjack(5, daysAgo = 40))

        assertEquals(14, dashboard.activity.size)
        assertEquals("2026-10-06", dashboard.activity.last().date)
        assertEquals(2, dashboard.activity.last().rounds)
        assertEquals(50, dashboard.activity.last().net)
        assertEquals(1, dashboard.activity.single { it.date == "2026-10-03" }.rounds)
        assertEquals(3, dashboard.activity.sumOf { it.rounds }, "a round from forty days ago is outside the window")
    }

    @Test
    fun `experience rewards playing, winning and the moments worth remembering`() {
        assertEquals(10, DashboardBuilder.experienceFor(blackjack(-100)))
        assertEquals(25, DashboardBuilder.experienceFor(blackjack(100)))
        assertEquals(35, DashboardBuilder.experienceFor(blackjack(150, detail = buildJsonObject { put("natural", true) })))
        assertEquals(30, DashboardBuilder.experienceFor(poker(100, buildJsonObject { put("showdown", true) })))
    }

    @Test
    fun `levels begin at the experience they say they do`() {
        assertEquals(1, Levels.levelAt(0))
        assertEquals(1, Levels.levelAt(99))
        assertEquals(2, Levels.levelAt(100))
        assertEquals(3, Levels.levelAt(300))
        assertEquals(3, Levels.levelAt(599))

        val card = build(*List(8) { blackjack(100) }.toTypedArray()).player
        assertEquals(200, card.xp)
        assertEquals(2, card.level)
        assertEquals(100, card.levelStart)
        assertEquals(300, card.nextLevelAt)
    }

    @Test
    fun `tendencies wait for enough rounds, then say what they are based on`() {
        val showdown = buildJsonObject { put("showdown", true); put("calls", 2); put("bets", 1) }
        val fold = buildJsonObject { put("folded", true); put("calls", 1) }

        assertTrue(build(*List(9) { poker(100, showdown) }.toTypedArray()).games.first { it.game == "poker" }.tendencies.isEmpty())

        val rounds = List(6) { poker(100, showdown) } + List(4) { poker(-50, fold) }
        val tendencies = build(*rounds.toTypedArray()).games.first { it.game == "poker" }.tendencies.associateBy { it.label }

        assertEquals("60%", tendencies.getValue("Sees a showdown").value)
        assertEquals("6 of 10 hands", tendencies.getValue("Sees a showdown").basis)
        assertEquals("100%", tendencies.getValue("Wins at showdown").value)
        assertEquals("40%", tendencies.getValue("Folds").value)
        // Six bets against sixteen calls.
        assertEquals("27%", tendencies.getValue("Bets or raises rather than calls").value)
    }

    @Test
    fun `blackjack tendencies leave insurance out until it has been offered enough`() {
        val plain = buildJsonObject { put("total", 19); put("dealerTotal", 18) }
        val bust = buildJsonObject { put("busts", 1); put("total", 24) }
        val rounds = List(8) { blackjack(100, detail = plain) } + List(2) { blackjack(-100, detail = bust) }

        val labels = build(*rounds.toTypedArray()).games.first { it.game == "blackjack" }.tendencies.associateBy { it.label }

        assertEquals("20%", labels.getValue("Busts").value)
        assertFalse("Takes insurance" in labels)
    }

    private fun roulette(net: Long, detail: JsonObject) = blackjack(net, detail = detail).copy(game = Game.ROULETTE)

    private fun spin(pocket: Int, color: String, bets: Int, won: Int, inside: Long, outside: Long, straightHit: Boolean = false) =
        buildJsonObject {
            put("pocket", pocket); put("color", color); put("bets", bets); put("betsWon", won)
            put("insideStake", inside); put("outsideStake", outside)
            put("straightBets", if (inside > 0) 1 else 0); put("straightHit", straightHit)
        }

    @Test
    fun `roulette has a place of its own, with where the chips went`() {
        val rounds = List(6) { roulette(-50, spin(8, "black", bets = 2, won = 0, inside = 10, outside = 40)) } +
            List(4) { roulette(60, spin(17, "black", bets = 2, won = 1, inside = 10, outside = 40)) }

        val game = build(*rounds.toTypedArray()).games.first { it.game == "roulette" }
        val labels = game.tendencies.associateBy { it.label }

        assertEquals(10, game.rounds)
        assertEquals(4, game.wins)
        assertEquals("20%", labels.getValue("Chips on the numbers").value)
        assertEquals("100 of 500 chips", labels.getValue("Chips on the numbers").basis)
        assertEquals("20%", labels.getValue("Bets that win").value)
        assertEquals("0%", labels.getValue("Hits a single number").value)
    }

    @Test
    fun `a spin is summed up by where the ball landed`() {
        val recent = build(
            roulette(350, spin(17, "black", bets = 1, won = 1, inside = 10, outside = 0, straightHit = true)),
            roulette(-30, spin(0, "green", bets = 3, won = 0, inside = 0, outside = 30)),
            roulette(10, spin(32, "red", bets = 1, won = 1, inside = 0, outside = 10)),
            roulette(20, spin(5, "red", bets = 3, won = 2, inside = 0, outside = 30)),
        ).recent.map { it.summary }

        assertEquals(listOf("17 black, straight up", "Zero, 0 of 3 bets won", "32 red, your bet won", "5 red, 2 of 3 bets won"), recent)
    }

    @Test
    fun `playing all three games and hitting a number are marked`() {
        val hit = roulette(350, spin(17, "black", bets = 1, won = 1, inside = 10, outside = 0, straightHit = true))
        val two = build(blackjack(100), poker(-50)).achievements.associateBy { it.id }
        val three = build(hit, blackjack(100), poker(-50)).achievements.associateBy { it.id }

        assertTrue(two.getValue("both_tables").earned)
        assertFalse(two.getValue("every_table").earned)
        assertEquals(2, two.getValue("every_table").progress)
        assertFalse(two.getValue("straight_up").earned)

        assertTrue(three.getValue("every_table").earned)
        assertTrue(three.getValue("straight_up").earned)
    }

    @Test
    fun `achievements are earned by the record and show how far along the rest are`() {
        val natural = buildJsonObject { put("natural", true); put("total", 21) }
        val dashboard = build(blackjack(150, detail = natural), blackjack(100), blackjack(100), poker(-50))
        val byId = dashboard.achievements.associateBy { it.id }

        assertTrue(byId.getValue("first_round").earned)
        assertTrue(byId.getValue("first_win").earned)
        assertTrue(byId.getValue("natural").earned)
        assertTrue(byId.getValue("both_tables").earned)
        assertTrue(byId.getValue("streak_3").earned)

        val heater = byId.getValue("streak_5")
        assertFalse(heater.earned)
        assertEquals(3, heater.progress)
        assertEquals(5, heater.target)
        assertEquals(4, byId.getValue("rounds_25").progress)
        assertFalse(byId.getValue("showdown_win").earned)
    }

    @Test
    fun `the biggest pot counts only poker pots that were won`() {
        val won = buildJsonObject { put("pot", 1_200); put("showdown", true) }
        val lost = buildJsonObject { put("pot", 5_000); put("showdown", true) }
        val dashboard = build(poker(600, won), poker(-2_500, lost))

        assertEquals(1_200, dashboard.totals.biggestPot)
        assertTrue(dashboard.achievements.first { it.id == "big_pot" }.earned)
    }

    @Test
    fun `recent rounds read as a sentence about what happened`() {
        val dashboard = build(
            blackjack(150, detail = buildJsonObject { put("natural", true); put("total", 21) }),
            blackjack(-100, detail = buildJsonObject { put("busts", 1); put("total", 24) }),
            blackjack(200, detail = buildJsonObject { put("doubled", 1); put("total", 20); put("dealerTotal", 18) }),
            blackjack(0, detail = buildJsonObject { put("total", 18); put("dealerTotal", 18) }),
            blackjack(0, detail = buildJsonObject { putJsonArray("outcomes") { add(kotlinx.serialization.json.JsonPrimitive("win")); add(kotlinx.serialization.json.JsonPrimitive("lose")) } }),
            poker(300, buildJsonObject { put("showdown", true); put("hand", "two_pair") }),
            poker(-20, buildJsonObject { put("folded", true) }),
            poker(30),
        )

        assertEquals(
            listOf(
                "Blackjack",
                "Bust on 24",
                "Won, 20 against 18, doubled",
                "Push at 18",
                "Split into 2 hands: win, lose",
                "Won at showdown with two pair",
                "Folded",
                "Banca folded",
            ),
            dashboard.recent.map { it.summary },
        )
    }
}

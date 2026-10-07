package com.banca.players

import com.banca.module
import com.banca.ws.TestClock
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The leagues as players meet them: who is in them, and what happens when a week ends. */
class LeaderboardsTest {

    // A Tuesday at noon, in the week beginning Monday 5 October 2026.
    private val clock = TestClock(Instant.parse("2026-10-06T12:00:00Z"))
    private val store = InMemoryPlayerStore(clock)
    private val players = Players(store, clock)
    private val boards = players.leaderboards

    // The leagues open the first time anyone looks at one, which here is before anybody plays.
    init {
        runBlocking { boards.catchUp() }
    }

    /** A player who has signed in, known by [name]. */
    private fun member(name: String): Pair<Player, String> = runBlocking {
        val (guest, token) = players.createGuest()
        val saved = players.signIn(guest, token, Account(UUID.randomUUID().toString(), null)).player
        players.rename(saved, name) to token
    }

    /** Plays [rounds] rounds at [game] that come to [net] in all. */
    private fun play(player: Player, net: Long, rounds: Int = 10, game: Game = Game.BLACKJACK) = runBlocking {
        repeat(rounds) { index ->
            val each = if (index == 0) net - (net / rounds) * (rounds - 1) else net / rounds
            players.settle(player, FinishedRound(game, "t", 10, each, if (each > 0) RoundOutcome.WIN else RoundOutcome.LOSS, JsonObject(emptyMap())))
        }
    }

    private fun nextWeek() = clock.advance(Duration.ofDays(7))

    /** The player as the store has them now, with whatever league they are in. */
    private fun fresh(token: String): Player = runBlocking { assertNotNull(players.authenticate(token)) }

    @Test
    fun `only players who have signed in are in a league`() = runBlocking {
        val (ana, _) = member("Ana")
        val (guest, _) = players.createGuest()
        play(ana, 300)
        play(guest, 5_000)

        val league = boards.league(ana)

        assertEquals(listOf("Ana"), league.rows.map { it.name }, "the guest won more, and is not there")
        assertTrue(league.signedIn)
        assertEquals("Bronze", league.tierName)
        assertEquals(1, league.rows.single().position)
        assertTrue(league.rows.single().you)
    }

    @Test
    fun `a guest can look at the lowest league without being in it`() = runBlocking {
        val (ana, _) = member("Ana")
        play(ana, 300)
        val (guest, _) = players.createGuest()

        for (viewer in listOf(guest, null)) {
            val league = boards.league(viewer)
            assertFalse(league.signedIn)
            assertEquals("Bronze", league.tierName)
            assertEquals(listOf("Ana"), league.rows.map { it.name })
            assertTrue(league.rows.none { it.you })
            assertNull(league.lastWeek)
        }
    }

    @Test
    fun `the standings show where each player would end up if the week finished now`() = runBlocking {
        val (ana, _) = member("Ana")
        val (rui, _) = member("Rui")
        val (idle, _) = member("Idle")
        play(ana, 400, rounds = 12)
        play(rui, -150)

        val league = boards.league(idle)

        assertEquals(listOf("Ana", "Rui", "Idle"), league.rows.map { it.name })
        assertEquals(listOf("promotion", "safe", "safe"), league.rows.map { it.zone })
        assertEquals(listOf(1, 2, 0), league.rows.map { it.position }, "someone who has not played has no position yet")
        assertEquals(3, league.players)
        assertEquals("2026-10-12T00:00:00Z", league.endsAt)
        assertEquals(listOf(1_000L, 500L, 250L), league.rules.prizes)
    }

    @Test
    fun `when the week ends the winners go up, are paid, and are told so`() = runBlocking {
        val (ana, anaToken) = member("Ana")
        val (rui, ruiToken) = member("Rui")
        play(ana, 400, rounds = 12)
        play(rui, -150)
        val before = players.balance(ana)

        nextWeek()
        val league = boards.league(fresh(anaToken))

        assertEquals("Silver", league.tierName, "promoted")
        assertEquals(before + 1_000, players.balance(ana), "and paid the prize for first")
        val last = assertNotNull(league.lastWeek)
        assertEquals("Bronze", last.tier)
        assertEquals(1, last.position)
        assertEquals("promoted", last.outcome)
        assertEquals(1_000, last.prize)
        assertEquals(400, last.net)

        assertEquals(LedgerReason.LEAGUE_PRIZE, store.ledger(ana.id, 100).last().reason, "the prize is in the ledger, as a prize")
        assertTrue(league.rows.isEmpty() || league.rows.all { it.rounds == 0 }, "a new week starts from nothing")

        val ruis = boards.league(fresh(ruiToken))
        assertEquals("Bronze", ruis.tierName)
        assertEquals("stayed", assertNotNull(ruis.lastWeek).outcome)
    }

    @Test
    fun `a week is settled once, however many people look`() = runBlocking {
        val (ana, token) = member("Ana")
        play(ana, 400, rounds = 12)
        val before = players.balance(ana)

        nextWeek()
        repeat(5) { boards.league(fresh(token)) }
        boards.top(thisWeek = true, game = null, player = null)

        assertEquals(before + 1_000, players.balance(ana), "one prize")
        assertEquals(1, fresh(token).leagueTier, "one promotion")
    }

    @Test
    fun `a player who stays away drops a league, a week at a time`() = runBlocking {
        val (ana, token) = member("Ana")
        play(ana, 400, rounds = 12)
        nextWeek()
        boards.league(fresh(token))
        play(fresh(token), 400, rounds = 12)
        nextWeek()
        assertEquals("Gold", boards.league(fresh(token)).tierName)

        // Three weeks pass with nobody looking, and Ana does not play in any of them.
        clock.advance(Duration.ofDays(21))
        val league = boards.league(fresh(token))

        assertEquals("Bronze", league.tierName, "two leagues lost in the weeks away, and no further than the bottom")
        assertEquals("stayed", assertNotNull(league.lastWeek).outcome)
    }

    @Test
    fun `the leagues begin with the week they are first looked at in, and nothing before it counts`() = runBlocking {
        // A server where nobody has looked at a league yet, though people have been playing for weeks.
        val quiet = TestClock(Instant.parse("2026-10-06T12:00:00Z"))
        val unopened = Players(InMemoryPlayerStore(quiet), quiet)
        val (guest, token) = unopened.createGuest()
        val ana = unopened.signIn(guest, token, Account(UUID.randomUUID().toString(), null)).player
        repeat(12) { unopened.settle(ana, FinishedRound(Game.BLACKJACK, "t", 10, 50, RoundOutcome.WIN, JsonObject(emptyMap()))) }
        quiet.advance(Duration.ofDays(7))
        repeat(12) { unopened.settle(ana, FinishedRound(Game.BLACKJACK, "t", 10, 10, RoundOutcome.WIN, JsonObject(emptyMap()))) }

        val league = unopened.leaderboards.league(assertNotNull(unopened.authenticate(token)))

        assertEquals("Bronze", league.tierName, "last week's winnings promoted nobody: there was no league then")
        assertNull(league.lastWeek)
        assertEquals(120, league.rows.single().net, "and this week's play counts from its start")
        assertEquals(2_000 + 600 + 120, unopened.balance(ana), "no prize was paid for a week before the leagues")
    }

    @Test
    fun `the winners' list is this week's or all time's, at one game or all`() = runBlocking {
        val (ana, _) = member("Ana")
        val (rui, _) = member("Rui")
        play(ana, 100, game = Game.POKER)
        play(rui, 900, game = Game.ROULETTE)
        nextWeek()
        play(ana, 300, game = Game.BLACKJACK)

        val week = boards.top(thisWeek = true, game = null, player = ana)
        assertEquals(listOf("Ana"), week.rows.map { it.name }, "Rui has not played this week")
        assertEquals(300, week.rows.single().net)
        assertTrue(week.rows.single().you)

        val all = boards.top(thisWeek = false, game = null, player = ana)
        assertEquals(listOf("Rui" to 900L, "Ana" to 400L), all.rows.map { it.name to it.net })
        assertEquals(listOf(1, 2), all.rows.map { it.position })

        val poker = boards.top(thisWeek = false, game = Game.POKER, player = null)
        assertEquals(listOf("Ana" to 100L), poker.rows.map { it.name to it.net })
        assertEquals("poker", poker.game)
    }

    // ---------------------------------------------------------- over the wire

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the league and the winners' list are served to anyone, and know the caller when told who it is`() = testApplication {
        application { module(players = players) }
        val (ana, token) = member("Ana")
        play(ana, 400, rounds = 12)

        val open = json(client.get("/league").bodyAsText())
        assertFalse(open.getValue("signedIn").jsonPrimitive.boolean)
        assertEquals("Ana", open.getValue("rows").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue(open.getValue("lastWeek") is JsonNull)

        val mine = json(client.get("/league") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText())
        assertTrue(mine.getValue("signedIn").jsonPrimitive.boolean)
        assertTrue(mine.getValue("rows").jsonArray.single().jsonObject.getValue("you").jsonPrimitive.boolean)
        assertEquals(5, mine.getValue("tiers").jsonArray.size)

        val top = json(client.get("/leaderboard?period=all&game=blackjack").bodyAsText())
        assertEquals("all", top.getValue("period").jsonPrimitive.content)
        val row = top.getValue("rows").jsonArray.single().jsonObject
        assertEquals(400, row.getValue("net").jsonPrimitive.long)
        assertEquals(1, row.getValue("position").jsonPrimitive.int)
        assertEquals("Bronze", row.getValue("league").jsonPrimitive.content)

        assertTrue(json(client.get("/leaderboard?game=poker").bodyAsText()).getValue("rows").jsonArray.isEmpty())
    }
}

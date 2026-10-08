package com.banca.players

import com.banca.module
import com.banca.ws.TestPlayers
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MissionsTest {

    private val day = LocalDate.of(2026, 10, 8)
    private val noon = Instant.parse("2026-10-08T12:00:00Z")
    private val ana = UUID.fromString("00000000-0000-0000-0000-00000000a11a")

    private fun round(game: Game, net: Long = 0, detail: JsonObject = buildJsonObject { }) = RoundRecord(
        game = game,
        endedAt = noon,
        staked = 50,
        net = net,
        outcome = if (net > 0) RoundOutcome.WIN else if (net < 0) RoundOutcome.LOSS else RoundOutcome.PUSH,
        detail = detail,
    )

    private fun mission(id: String) = Missions.ALL.first { it.id == id }

    // ------------------------------------------------------------- which three

    @Test
    fun `a player has the same three missions all day, one of each kind`() {
        val today = Missions.forDay(ana, day)

        assertEquals(3, today.size)
        assertEquals(today.map { it.id }, Missions.forDay(ana, day).map { it.id })
        assertEquals(listOf(75L, 100L, 150L), today.map { it.reward }, "one to play, one to win and one feat")
    }

    @Test
    fun `tomorrow's are not always today's, and one player's are not always another's`() {
        val overAMonth = (0L until 30).map { Missions.forDay(ana, day.plusDays(it)).map { mission -> mission.id } }
        assertTrue(overAMonth.toSet().size > 10, "the days differ")

        val acrossPlayers = (1..30).map { Missions.forDay(UUID.randomUUID(), day).map { mission -> mission.id } }
        assertTrue(acrossPlayers.toSet().size > 10, "and so do the players")
    }

    @Test
    fun `every mission turns up for somebody`() {
        val seen = (1..400).flatMap { Missions.forDay(UUID.randomUUID(), day) }.map { it.id }.toSet()
        assertEquals(Missions.ALL.map { it.id }.toSet(), seen)
    }

    @Test
    fun `every mission has a name of its own, something to do and something for doing it`() {
        assertEquals(Missions.ALL.size, Missions.ALL.map { it.id }.toSet().size)
        assertEquals(Missions.ALL.size, Missions.ALL.map { it.title }.toSet().size)
        assertTrue(Missions.ALL.all { it.target >= 1 && it.reward > 0 && it.detail.isNotBlank() })
        assertTrue(Missions.ALL.all { it.progress(emptyList()) == 0L }, "and nobody has done any of it before playing")
    }

    // ---------------------------------------------------------------- progress

    @Test
    fun `missions are counted from the rounds a player has played`() {
        val poker = listOf(round(Game.POKER, net = 60), round(Game.POKER, net = -20), round(Game.POKER, net = 400, detail = buildJsonObject { put("showdown", true); put("raises", 2); put("bets", 1) }))
        val blackjack = listOf(round(Game.BLACKJACK, net = 75, detail = buildJsonObject { put("natural", true); put("byTheBook", 2); put("advised", 1) }))
        val all = poker + blackjack + round(Game.ROULETTE, net = 350, detail = buildJsonObject { put("straightHit", true) })

        assertEquals(3, mission("play_poker").progress(all))
        assertEquals(2, mission("win_poker").progress(all))
        assertEquals(5, mission("play_any").progress(all))
        assertEquals(3, mission("play_all").progress(all))
        assertEquals(4, mission("win_any").progress(all))
        assertEquals(1, mission("win_big").progress(all))
        assertEquals(1, mission("showdown").progress(all))
        assertEquals(3, mission("aggressor").progress(all))
        assertEquals(1, mission("natural").progress(all))
        assertEquals(2, mission("by_the_book").progress(all))
        assertEquals(1, mission("ask_coach").progress(all))
        assertEquals(1, mission("straight_up").progress(all))
        assertEquals(1, mission("win_roulette").progress(all))
    }

    @Test
    fun `a mission is ready when it is done, and stays done however much more is played`() {
        val first = Missions.forDay(ana, day).first()
        val enough = List((first.target + 5).toInt()) { round(Game.POKER, net = 10) } + List(20) { round(Game.BLACKJACK, net = 10) } + List(20) { round(Game.ROULETTE, net = 10) }

        val before = Missions.status(ana, noon, emptyList(), emptySet())
        assertTrue(before.missions.none { it.ready || it.claimed })
        assertEquals(0, before.missions.first().progress)
        assertFalse(before.bonus.ready)

        val after = Missions.status(ana, noon, enough, emptySet()).missions.first()
        assertEquals(first.target, after.progress, "progress stops at the target")
        assertTrue(after.ready)
        assertEquals("2026-10-09T00:00:00Z", before.resetsAt)
    }

    @Test
    fun `what has been paid for is no longer there to claim, and the bonus waits for all three`() {
        val everything =
            List(20) { round(Game.POKER, net = 400, detail = buildJsonObject { put("showdown", true); put("raises", 2) }) } +
            List(20) { round(Game.BLACKJACK, net = 400, detail = buildJsonObject { put("natural", true); put("byTheBook", 2); put("advised", 1) }) } +
            List(20) { round(Game.ROULETTE, net = 400, detail = buildJsonObject { put("straightHit", true) }) }

        val done = Missions.status(ana, noon, everything, emptySet())
        assertTrue(done.missions.all { it.ready })
        assertTrue(done.bonus.ready)
        assertEquals(75, Missions.worth(done, 0))
        assertEquals(Missions.BONUS, Missions.worth(done, Missions.BONUS_SLOT))
        assertNull(Missions.worth(done, 7), "there is no such mission")

        val partPaid = Missions.status(ana, noon, everything, setOf(0, Missions.BONUS_SLOT))
        assertTrue(partPaid.missions[0].claimed)
        assertFalse(partPaid.missions[0].ready)
        assertNull(Missions.worth(partPaid, 0))
        assertNull(Missions.worth(partPaid, Missions.BONUS_SLOT))
        assertEquals(100, Missions.worth(partPaid, 1))

        assertNull(Missions.worth(Missions.status(ana, noon, emptyList(), emptySet()), 0), "nor is one that is not done")
    }

    @Test
    fun `a payment is marked with its day and its mission, and read back for that day alone`() {
        assertEquals("2026-10-08:2", Missions.reference(day, 2))
        assertEquals(setOf(0, 3), Missions.claimedSlots(day, listOf("2026-10-08:0", "2026-10-08:3", "2026-10-07:1", "nonsense")))
    }

    // ------------------------------------------------------------ for a player

    /** Plays [player] through enough of everything to finish any three missions. */
    private fun playEverything(guest: TestPlayers) = runBlocking {
        fun finished(game: Game, detail: JsonObject) = FinishedRound(game, "table-1", staked = 50, net = 400, outcome = RoundOutcome.WIN, detail = detail)
        repeat(16) { guest.players.settle(guest.player, finished(Game.POKER, buildJsonObject { put("showdown", true); put("raises", 2) })) }
        repeat(16) { guest.players.settle(guest.player, finished(Game.BLACKJACK, buildJsonObject { put("natural", true); put("byTheBook", 2); put("advised", 1) })) }
        repeat(16) { guest.players.settle(guest.player, finished(Game.ROULETTE, buildJsonObject { put("straightHit", true) })) }
    }

    @Test
    fun `a mission done is paid for once, and all three earn the bonus`() = runBlocking {
        val guest = TestPlayers()
        assertNull(guest.players.claimMission(guest.player, 0), "nothing has been done yet")

        playEverything(guest)
        val before = guest.players.balance(guest.player)

        assertEquals(75, guest.players.claimMission(guest.player, 0))
        assertNull(guest.players.claimMission(guest.player, 0), "not twice")
        assertEquals(100, guest.players.claimMission(guest.player, 1))
        assertEquals(150, guest.players.claimMission(guest.player, 2))
        assertEquals(Missions.BONUS, guest.players.claimMission(guest.player, Missions.BONUS_SLOT))
        assertNull(guest.players.claimMission(guest.player, Missions.BONUS_SLOT))

        assertEquals(before + 75 + 100 + 150 + Missions.BONUS, guest.players.balance(guest.player))
        val status = guest.players.missions(guest.player)
        assertTrue(status.missions.all { it.claimed } && status.bonus.claimed)
    }

    @Test
    fun `tomorrow starts afresh, and what was played and claimed yesterday counts for nothing`() = runBlocking {
        val guest = TestPlayers()
        playEverything(guest)
        guest.players.claimMission(guest.player, 0)

        guest.clock.advance(Duration.ofHours(25))
        val tomorrow = guest.players.missions(guest.player)

        assertTrue(tomorrow.missions.all { it.progress == 0L && !it.claimed && !it.ready })
        assertFalse(tomorrow.bonus.ready)
        assertNull(guest.players.claimMission(guest.player, 0))
    }

    @Test
    fun `missions are on the dashboard and claimed over the wire`() = testApplication {
        val guest = TestPlayers()
        application { module(players = guest.players) }
        playEverything(guest)
        fun json(text: String) = Json.parseToJsonElement(text).jsonObject

        val dashboard = json(client.get("/players/me/dashboard") { header(HttpHeaders.Authorization, "Bearer ${guest.token}") }.bodyAsText())
        val missions = dashboard.getValue("missions").jsonObject
        assertEquals(3, missions.getValue("missions").jsonArray.size)
        assertTrue(missions.getValue("missions").jsonArray.all { it.jsonObject.getValue("ready").jsonPrimitive.boolean })
        assertTrue(missions.getValue("bonus").jsonObject.getValue("ready").jsonPrimitive.boolean)

        val claim = client.post("/players/me/missions/1") { header(HttpHeaders.Authorization, "Bearer ${guest.token}") }
        assertEquals(HttpStatusCode.OK, claim.status)
        val paid = json(claim.bodyAsText())
        assertEquals(100, paid.getValue("granted").jsonPrimitive.long)
        assertEquals(guest.players.balance(guest.player), paid.getValue("balance").jsonPrimitive.long)
        assertTrue(paid.getValue("missions").jsonObject.getValue("missions").jsonArray[1].jsonObject.getValue("claimed").jsonPrimitive.boolean)

        assertEquals(HttpStatusCode.Conflict, client.post("/players/me/missions/1") { header(HttpHeaders.Authorization, "Bearer ${guest.token}") }.status)
        assertEquals(HttpStatusCode.Conflict, client.post("/players/me/missions/nine") { header(HttpHeaders.Authorization, "Bearer ${guest.token}") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/players/me/missions/0").status)
        assertNotEquals(0, paid.getValue("missions").jsonObject.getValue("missions").jsonArray[0].jsonObject.getValue("target").jsonPrimitive.int)
    }
}

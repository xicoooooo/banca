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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Chips given rather than won: the daily reward and the house's stake. */
class RewardsTest {

    private val noon = Instant.parse("2026-10-06T12:00:00Z")

    private fun daysAgo(days: Long) = noon.minus(Duration.ofDays(days))

    // ------------------------------------------------------------- the rules

    @Test
    fun `someone who has never claimed is offered the first day`() {
        val daily = Rewards.status(emptyList(), null, noon).daily

        assertTrue(daily.available)
        assertEquals(1, daily.day)
        assertEquals(200, daily.amount)
        assertEquals(0, daily.streak)
        assertNull(daily.nextAt)
    }

    @Test
    fun `each day claimed in a row is worth more`() {
        val daily = Rewards.status(listOf(daysAgo(1), daysAgo(2), daysAgo(3)), null, noon).daily

        assertTrue(daily.available)
        assertEquals(3, daily.streak)
        assertEquals(4, daily.day)
        assertEquals(500, daily.amount)
    }

    @Test
    fun `once claimed, the next opens at midnight and is already known`() {
        val daily = Rewards.status(listOf(noon.minusSeconds(60), daysAgo(1)), null, noon).daily

        assertFalse(daily.available)
        assertEquals(2, daily.streak, "today counts")
        assertEquals(3, daily.day)
        assertEquals(400, daily.amount)
        assertEquals("2026-10-07T00:00:00Z", daily.nextAt)
    }

    @Test
    fun `a missed day starts the week again`() {
        val daily = Rewards.status(listOf(daysAgo(2), daysAgo(3), daysAgo(4)), null, noon).daily

        assertEquals(0, daily.streak)
        assertEquals(1, daily.day)
        assertEquals(200, daily.amount)
    }

    @Test
    fun `the seventh day pays the most and the eighth starts over`() {
        val six = (1L..6L).map(::daysAgo)
        assertEquals(2_000, Rewards.status(six, null, noon).daily.amount)

        val seven = (1L..7L).map(::daysAgo)
        val daily = Rewards.status(seven, null, noon).daily
        assertEquals(7, daily.streak, "the streak itself carries on")
        assertEquals(1, daily.day)
        assertEquals(200, daily.amount)
    }

    @Test
    fun `days turn over at midnight UTC, not a day after the last claim`() {
        val lateLastNight = Instant.parse("2026-10-05T23:59:00Z")
        val justAfterMidnight = Instant.parse("2026-10-06T00:01:00Z")

        val daily = Rewards.status(listOf(lateLastNight), null, justAfterMidnight).daily
        assertTrue(daily.available)
        assertEquals(1, daily.streak)
    }

    @Test
    fun `the house waits four hours before staking the same player again`() {
        assertNull(Rewards.status(emptyList(), null, noon).rescue.nextAt)
        assertEquals(
            "2026-10-06T15:00:00Z",
            Rewards.status(emptyList(), noon.minus(Duration.ofHours(1)), noon).rescue.nextAt,
        )
        assertNull(Rewards.status(emptyList(), noon.minus(Duration.ofHours(4)), noon).rescue.nextAt)
    }

    // ------------------------------------------------------------ for a player

    private fun TestPlayers.leaveWith(chips: Long) = runBlocking {
        val balance = players.balance(player)
        players.settle(player, FinishedRound(Game.BLACKJACK, "t", balance - chips, chips - balance, RoundOutcome.LOSS, JsonObject(emptyMap())))
    }

    @Test
    fun `the daily reward is paid once a day and grows with the streak`() = runBlocking {
        val guest = TestPlayers()

        assertEquals(200, guest.players.claimDaily(guest.player))
        assertNull(guest.players.claimDaily(guest.player), "not twice in a day")
        assertEquals(2_200, guest.players.balance(guest.player))

        guest.clock.advance(Duration.ofDays(1))
        assertEquals(300, guest.players.claimDaily(guest.player))

        guest.clock.advance(Duration.ofDays(2))
        assertEquals(200, guest.players.claimDaily(guest.player), "a day was missed")
        assertEquals(2_700, guest.players.balance(guest.player))
    }

    @Test
    fun `a player with enough is left alone`() = runBlocking {
        val guest = TestPlayers()
        assertEquals(Funding.Ready(2_000), guest.players.fund(guest.player, 20))
        assertEquals(1, guest.store.ledger(guest.player.id, 10).size)
    }

    @Test
    fun `a broke player is staked once, then has to wait or claim`() = runBlocking {
        val guest = TestPlayers()
        guest.leaveWith(0)

        assertEquals(Funding.Staked(500, 500), guest.players.fund(guest.player, 10))

        guest.leaveWith(0)
        val broke = assertIs<Funding.Broke>(guest.players.fund(guest.player, 10))
        assertTrue(broke.dailyReady)
        assertEquals(guest.clock.now, broke.nextChipsAt, "the daily reward is there now")

        guest.players.claimDaily(guest.player)
        assertIs<Funding.Ready>(guest.players.fund(guest.player, 10))

        guest.leaveWith(0)
        val waiting = assertIs<Funding.Broke>(guest.players.fund(guest.player, 10))
        assertFalse(waiting.dailyReady)
        assertEquals(guest.clock.now.plus(Duration.ofHours(4)), waiting.nextChipsAt, "the house's stake comes round before midnight does")
        assertEquals(0, guest.players.balance(guest.player), "and nothing is given in the meantime")
    }

    // ---------------------------------------------------------- over the wire

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the dashboard says what can be claimed, and claiming pays it`() = testApplication {
        val guest = TestPlayers()
        application { module(players = guest.players) }
        val auth = "Bearer ${guest.token}"

        val before = json(client.get("/players/me/dashboard") { header(HttpHeaders.Authorization, auth) }.bodyAsText())
        val daily = before.getValue("rewards").jsonObject.getValue("daily").jsonObject
        assertTrue(daily.getValue("available").jsonPrimitive.boolean)
        assertEquals(200, daily.getValue("amount").jsonPrimitive.long)

        val claim = client.post("/players/me/rewards/daily") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, claim.status)
        val paid = json(claim.bodyAsText())
        assertEquals(200, paid.getValue("granted").jsonPrimitive.long)
        assertEquals(2_200, paid.getValue("balance").jsonPrimitive.long)
        val after = paid.getValue("rewards").jsonObject.getValue("daily").jsonObject
        assertFalse(after.getValue("available").jsonPrimitive.boolean)
        assertEquals(2, after.getValue("day").jsonPrimitive.int)

        assertEquals(HttpStatusCode.Conflict, client.post("/players/me/rewards/daily") { header(HttpHeaders.Authorization, auth) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/players/me/rewards/daily").status)
        assertEquals(2_200, guest.players.balance(guest.player))
    }
}

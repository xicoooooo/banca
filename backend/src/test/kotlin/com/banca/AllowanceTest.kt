package com.banca

import com.banca.agents.BlackjackAdvisor
import com.banca.agents.BookAdvisor
import com.banca.agents.blackjackDecision
import com.banca.players.PlayerSession
import com.banca.ws.CoachAllowance
import com.banca.ws.TestPlayers
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class AllowanceTest {

    private var now = 1_000_000L
    private fun allowance(vararg limits: Limit) = Allowance(*limits, now = { now })

    @Test
    fun `a caller may take up to the limit and no more`() {
        val each = allowance(Limit(3, 10.minutes))

        assertEquals(listOf(true, true, true, false, false), List(5) { each.take("a") })
    }

    @Test
    fun `one caller's share is no part of another's`() {
        val each = allowance(Limit(1, 10.minutes))

        assertTrue(each.take("a"))
        assertTrue(each.take("b"))
        assertFalse(each.take("a"))
    }

    @Test
    fun `a share comes round again as time passes`() {
        val each = allowance(Limit(2, 10.minutes))
        each.take("a")
        now += 6.minutes.inWholeMilliseconds
        each.take("a")
        assertFalse(each.take("a"))

        now += 4.minutes.inWholeMilliseconds
        assertTrue(each.take("a"), "the first is ten minutes old and no longer counts")
        assertFalse(each.take("a"), "the second still does")
    }

    @Test
    fun `every limit must have room`() {
        val each = allowance(Limit(2, 10.minutes), Limit(3, 24.hours))

        each.take("a")
        each.take("a")
        now += 11.minutes.inWholeMilliseconds
        assertTrue(each.take("a"))
        now += 11.minutes.inWholeMilliseconds
        assertFalse(each.take("a"), "the short limit has room again, the long one has not")
    }

    @Test
    fun `a refusal is not counted against the caller`() {
        val each = allowance(Limit(1, 10.minutes))
        each.take("a")
        repeat(50) { each.take("a") }

        now += 10.minutes.inWholeMilliseconds
        assertTrue(each.take("a"))
    }

    // ------------------------------------------------------------- new guests

    @Test
    fun `one address is given only so many new guests`() = testApplication {
        application { module(players = TestPlayers().players, newGuests = Allowance(Limit(3, 1.hours))) }

        val answers = List(5) { client.post("/players").status }

        assertEquals(List(3) { HttpStatusCode.Created } + List(2) { HttpStatusCode.TooManyRequests }, answers)
    }

    @Test
    fun `addresses are told apart by what the host's proxy says, and a caller cannot pass as another`() = testApplication {
        application { module(players = TestPlayers().players, newGuests = Allowance(Limit(1, 1.hours))) }

        suspend fun from(address: String, forged: String? = null) = client.post("/players") {
            header("CF-Connecting-IP", address)
            if (forged != null) header("X-Forwarded-For", forged)
        }.status

        assertEquals(HttpStatusCode.Created, from("203.0.113.7"))
        assertEquals(HttpStatusCode.Created, from("203.0.113.8"), "a different address has its own share")
        assertEquals(HttpStatusCode.TooManyRequests, from("203.0.113.7"))
        assertEquals(HttpStatusCode.TooManyRequests, from("203.0.113.7", forged = "198.51.100.1"), "claiming to be forwarded for someone else changes nothing")
    }

    // --------------------------------------------------------------- the coach

    @Test
    fun `a player who has used their share of the coach is answered from the figures instead of refused`() = runBlocking {
        val guest = TestPlayers()
        val session = PlayerSession(guest.player, guest.players)
        var askedOfModel = 0
        val model = BlackjackAdvisor { view, trace -> askedOfModel++; BookAdvisor().advise(view, trace).copy(source = "banca") }
        val coach = CoachAllowance(Allowance(Limit(2, 10.minutes))).blackjack(model, session)
        val decision = blackjackDecision(listOf("Ts", "6d"), total = 16, dealerShows = "Kh")

        val sources = List(4) { coach.advise(decision) {}.source }

        assertEquals(listOf("banca", "banca", "book", "book"), sources)
        assertEquals(2, askedOfModel)
    }

    @Test
    fun `one player's asking does not use up another's share`() = runBlocking {
        val guest = TestPlayers()
        val (other, _) = guest.players.createGuest()
        val allowance = CoachAllowance(Allowance(Limit(1, 10.minutes)))
        val model = BlackjackAdvisor { view, trace -> BookAdvisor().advise(view, trace).copy(source = "banca") }
        val decision = blackjackDecision(listOf("Ts", "6d"), total = 16, dealerShows = "Kh")

        val mine = allowance.blackjack(model, PlayerSession(guest.player, guest.players))
        val theirs = allowance.blackjack(model, PlayerSession(other, guest.players))

        assertEquals("banca", mine.advise(decision) {}.source)
        assertEquals("book", mine.advise(decision) {}.source)
        assertEquals("banca", theirs.advise(decision) {}.source)
    }
}

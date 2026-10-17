package com.banca.players

import com.banca.ws.TestClock
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How a challenge's standings are worked out: whole goes, goes cut short, and goes cut off. */
class ChallengesTest {

    private val clock = TestClock()
    private val store = InMemoryPlayerStore(clock)
    private val challenges = Challenges(store, clock)

    /** A player who has signed in, as only they may take challenges. */
    private fun player(name: String) = runBlocking { store.linkAccount(store.create(name, name, 2_000).id, "account-$name") }

    @Test
    fun `a go cut short is placed below every whole one, however many chips it kept`() = runBlocking {
        val ana = player("Ana")
        val rui = player("Rui")
        val marta = player("Marta")
        val run = store.challengeByCode(challenges.set(ana))!!

        listOf(ana, rui, marta).forEach { assertTrue(challenges.enter(run, it)) }
        challenges.finish(run, ana, roundsPlayed = 10, chips = 1_150)
        challenges.finish(run, rui, roundsPlayed = 2, chips = 1_200)
        challenges.finish(run, marta, roundsPlayed = 10, chips = 900)

        val standings = challenges.view(run.code, ana)!!.standings
        assertEquals(listOf("Ana", "Marta", "Rui"), standings.map { it.name })
        assertEquals(listOf(1, 2, 3), standings.map { it.place })
        assertEquals(listOf(true, true, false), standings.map { it.complete })
    }

    @Test
    fun `a go that ran out of chips is a whole one`() = runBlocking {
        val ana = player("Ana")
        val rui = player("Rui")
        val run = store.challengeByCode(challenges.set(ana))!!
        challenges.enter(run, ana)
        challenges.enter(run, rui)
        challenges.finish(run, ana, roundsPlayed = 6, chips = 0)
        challenges.finish(run, rui, roundsPlayed = 1, chips = 1_100)

        val standings = challenges.view(run.code, null)!!.standings
        assertEquals(listOf("Ana", "Rui"), standings.map { it.name }, "busted is finished; leaving after one round is not")
        assertTrue(standings.first().complete)
    }

    @Test
    fun `a go cut off and never closed is closed where it stood, once it has sat long enough`() = runBlocking {
        val ana = player("Ana")
        val run = store.challengeByCode(challenges.set(ana))!!
        challenges.enter(run, ana)
        challenges.played(run, ana, roundsPlayed = 3, chips = 1_250)

        val fresh = challenges.view(run.code, ana)!!.standings.single()
        assertNull(fresh.place, "still being played, as far as anyone knows")

        clock.advance(Duration.ofHours(3))
        val closed = challenges.view(run.code, ana)!!.standings.single()
        assertEquals(1_250L, closed.chips)
        assertEquals(1, closed.place)
        assertFalse(closed.complete)
    }

    @Test
    fun `coming back to a go cut off closes it, and it counts as taken`() = runBlocking {
        val ana = player("Ana")
        val run = store.challengeByCode(challenges.set(ana))!!
        challenges.enter(run, ana)
        challenges.played(run, ana, roundsPlayed = 2, chips = 980)

        assertTrue(challenges.hasTaken(run, ana))
        val standing = challenges.view(run.code, ana)!!.standings.single()
        assertEquals(980L, standing.chips)
        assertFalse(standing.complete)
    }

    @Test
    fun `a guest can neither set a challenge nor take one`() = runBlocking {
        val ana = player("Ana")
        val guest = store.create("Guest 1", "guest", 2_000)
        val run = store.challengeByCode(challenges.set(ana))!!

        kotlin.test.assertFailsWith<IllegalArgumentException> { challenges.set(guest) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { challenges.enter(run, guest) }
    }
}

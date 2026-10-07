package com.banca.players

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The rules of the weekly leagues, on their own. */
class LeaguesTest {

    private fun standing(name: String, net: Long, rounds: Int = 20, tier: Int = 0) =
        Standing(UUID.nameUUIDFromBytes(name.toByteArray()), name, tier, net, rounds)

    private fun outcomes(vararg standings: Standing) = Leagues.settle(standings.toList()).associateBy { result ->
        standings.first { it.playerId == result.playerId }.name
    }

    @Test
    fun `a week begins on Monday at midnight UTC`() {
        assertEquals(LocalDate.parse("2026-10-05"), Leagues.weekOf(Instant.parse("2026-10-07T15:00:00Z")))
        assertEquals(LocalDate.parse("2026-10-05"), Leagues.weekOf(Instant.parse("2026-10-05T00:00:00Z")), "Monday itself")
        assertEquals(LocalDate.parse("2026-09-28"), Leagues.weekOf(Instant.parse("2026-10-04T23:59:59Z")), "Sunday night is still last week")
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), Leagues.endOf(LocalDate.parse("2026-10-05")))
    }

    @Test
    fun `players are ranked by what they won, and those who did not play come last`() {
        val order = Leagues.ranked(
            listOf(standing("Idle", 0, rounds = 0), standing("Down", -300), standing("Up", 500), standing("Even", 0), standing("Further up", 900)),
        ).map { it.name }

        assertEquals(listOf("Further up", "Up", "Even", "Down", "Idle"), order)
    }

    @Test
    fun `a tie goes to whoever played more`() {
        val order = Leagues.ranked(listOf(standing("Few", 400, rounds = 12), standing("Many", 400, rounds = 40))).map { it.name }
        assertEquals(listOf("Many", "Few"), order)
    }

    @Test
    fun `the top three who finished ahead go up, and are paid`() {
        val week = outcomes(standing("Ana", 900), standing("Rui", 500), standing("Marta", 200), standing("Tiago", 100), standing("Ines", -50))

        assertEquals(listOf(LeagueOutcome.PROMOTED, LeagueOutcome.PROMOTED, LeagueOutcome.PROMOTED), listOf("Ana", "Rui", "Marta").map { week.getValue(it).outcome })
        assertEquals(listOf(1_000L, 500L, 250L), listOf("Ana", "Rui", "Marta").map { week.getValue(it).prize })
        assertEquals(LeagueOutcome.STAYED, week.getValue("Tiago").outcome, "fourth is not enough")
        assertEquals(0, week.getValue("Tiago").prize)
        assertEquals(listOf(1, 2, 3, 4, 5), listOf("Ana", "Rui", "Marta", "Tiago", "Ines").map { week.getValue(it).position })
        assertEquals(1, week.getValue("Ana").nextTier)
    }

    @Test
    fun `finishing top while behind, or after a handful of rounds, is not a promotion`() {
        val losing = outcomes(standing("Least bad", -10), standing("Worse", -200))
        assertEquals(LeagueOutcome.STAYED, losing.getValue("Least bad").outcome)
        assertEquals(0, losing.getValue("Least bad").prize, "nobody is paid for losing least")

        val lucky = outcomes(standing("One big hand", 2_000, rounds = 3), standing("Grinder", 300, rounds = 60))
        assertEquals(LeagueOutcome.STAYED, lucky.getValue("One big hand").outcome)
        assertEquals(0, lucky.getValue("One big hand").prize)
        assertEquals(1, lucky.getValue("One big hand").position, "they still finished first")
        assertEquals(LeagueOutcome.PROMOTED, lucky.getValue("Grinder").outcome)
    }

    @Test
    fun `prizes are bigger in the higher leagues`() {
        assertEquals(listOf(1_000L, 500L, 250L, 0L), (1..4).map { Leagues.prizeFor(0, it) })
        assertEquals(listOf(3_000L, 1_500L, 750L), (1..3).map { Leagues.prizeFor(2, it) })
        assertEquals(0, Leagues.prizeFor(4, 0), "nothing for someone who did not play")
    }

    @Test
    fun `nobody goes down from the lowest league or up from the highest`() {
        val bottom = outcomes(standing("Idle", 0, rounds = 0, tier = 0), standing("Loser", -900, tier = 0))
        assertTrue(bottom.values.all { it.outcome == LeagueOutcome.STAYED })

        val top = outcomes(standing("Champion", 5_000, tier = 4))
        assertEquals(LeagueOutcome.STAYED, top.getValue("Champion").outcome)
        assertEquals(5_000, top.getValue("Champion").prize, "but the best of the best is still paid")
    }

    @Test
    fun `a week without playing costs a league`() {
        val week = outcomes(standing("Away", 0, rounds = 0, tier = 2), standing("Here", -100, tier = 2))

        assertEquals(LeagueOutcome.DEMOTED, week.getValue("Away").outcome)
        assertEquals(1, week.getValue("Away").nextTier)
        assertEquals(0, week.getValue("Away").position)
        assertEquals(LeagueOutcome.STAYED, week.getValue("Here").outcome, "losing is not the same as not turning up")
    }

    @Test
    fun `the bottom three go down only from a league with players to spare`() {
        val few = (1..9).map { standing("P$it", 1_000L - it * 100, tier = 1) }
        assertTrue(Leagues.settle(few).none { it.outcome == LeagueOutcome.DEMOTED }, "nine is too few to send anyone down")

        val enough = (1..10).map { standing("P$it", 1_000L - it * 100, tier = 1) }
        val results = Leagues.settle(enough).associateBy { it.position }
        assertEquals(listOf(LeagueOutcome.DEMOTED, LeagueOutcome.DEMOTED, LeagueOutcome.DEMOTED), (8..10).map { results.getValue(it).outcome })
        assertEquals(LeagueOutcome.STAYED, results.getValue(7).outcome)
        assertEquals(LeagueOutcome.PROMOTED, results.getValue(1).outcome)
    }

    @Test
    fun `each league is settled on its own`() {
        val week = outcomes(
            standing("Bronze best", 100, tier = 0),
            standing("Silver best", 50, tier = 1),
            standing("Silver second", 20, tier = 1),
        )

        assertEquals(1, week.getValue("Bronze best").position)
        assertEquals(1, week.getValue("Silver best").position, "first in their own league, though they won less")
        assertEquals(2_000, week.getValue("Silver best").prize)
        assertEquals(2, week.getValue("Silver second").position)
    }

    @Test
    fun `where a player stands is told the same way during the week as at its end`() {
        val tier = listOf(standing("Ana", 900), standing("Rui", -100), standing("Idle", 0, rounds = 0, tier = 0))
        val zones = Leagues.zones(tier).associate { (standing, zone) -> standing.name to zone }

        assertEquals(Zone.PROMOTION, zones.getValue("Ana"))
        assertEquals(Zone.SAFE, zones.getValue("Rui"))
        assertEquals(Zone.SAFE, zones.getValue("Idle"), "nobody goes down from the lowest league")
    }
}

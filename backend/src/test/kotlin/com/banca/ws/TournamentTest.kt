package com.banca.ws

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A tournament's score: stacks, blinds that rise, who goes out, and where everyone finishes. */
class TournamentTest {

    private val ana = UUID.randomUUID()
    private val rui = UUID.randomUUID()
    private val marta = UUID.randomUUID()

    private fun three() = Tournament(stack = 1_500, handsPerLevel = 3).apply {
        begin(listOf(ana to "Ana", rui to "Rui", Tournament.BANCA to "Banca"))
    }

    @Test
    fun `everyone starts with the same stack and the first blinds`() {
        val tournament = three()

        assertTrue(tournament.running)
        assertEquals(listOf(1_500L, 1_500L, 1_500L), listOf(ana, rui, Tournament.BANCA).map(tournament::chipsOf))
        assertEquals(Tournament.Level(10, 20), tournament.level)
        assertEquals(3, tournament.handsToNextLevel)
    }

    @Test
    fun `it takes two to begin`() {
        assertFailsWith<IllegalArgumentException> { Tournament().begin(listOf(ana to "Ana")) }
    }

    @Test
    fun `the blinds rise every few hands, and stop rising at the last level`() {
        val tournament = three()
        val level = { tournament.level.big }

        repeat(3) { tournament.handPlayed(mapOf(ana to 1_500L, rui to 1_500L)) }
        assertEquals(30, level())
        repeat(3) { tournament.handPlayed(mapOf(ana to 1_500L, rui to 1_500L)) }
        assertEquals(50, level())

        repeat(100) { tournament.handPlayed(mapOf(ana to 1_500L, rui to 1_500L)) }
        assertEquals(Tournament.LEVELS.last(), tournament.level)
        assertEquals(0, tournament.handsToNextLevel)
    }

    @Test
    fun `a player left with nothing is out, and the last with chips wins`() {
        val tournament = three()

        tournament.handPlayed(mapOf(ana to 3_000L, rui to 0L))
        assertFalse(tournament.isIn(rui))
        assertEquals(3, tournament.placeOf(rui))
        assertNull(tournament.placeOf(ana), "still playing")
        assertTrue(tournament.running)

        tournament.handPlayed(mapOf(ana to 4_500L, Tournament.BANCA to 0L))
        assertFalse(tournament.running)
        assertTrue(tournament.finished)
        assertEquals(listOf(1, 2, 3), listOf(ana, Tournament.BANCA, rui).map(tournament::placeOf))
    }

    @Test
    fun `of two who go out in the same hand, the one who began it with more finishes higher`() {
        val tournament = Tournament(stack = 1_000).apply { begin(listOf(ana to "Ana", rui to "Rui", marta to "Marta")) }
        tournament.handPlayed(mapOf(ana to 1_400L, rui to 600L))

        tournament.handPlayed(mapOf(ana to 3_000L, rui to 0L, marta to 0L))

        assertEquals(1, tournament.placeOf(ana))
        assertEquals(2, tournament.placeOf(marta), "Marta began the hand with 1,000")
        assertEquals(3, tournament.placeOf(rui), "and Rui with 600")
    }

    @Test
    fun `someone who leaves goes out where they stand`() {
        val tournament = three()
        tournament.forfeit(rui)

        assertEquals(3, tournament.placeOf(rui))
        assertTrue(tournament.running)

        tournament.forfeit(ana)
        assertFalse(tournament.running)
        assertEquals(1, tournament.placeOf(Tournament.BANCA))
    }

    @Test
    fun `the standings put those playing first by their chips, then those out by where they finished`() {
        val tournament = three()
        tournament.handPlayed(mapOf(ana to 2_100L, rui to 900L))
        tournament.handPlayed(mapOf(rui to 0L, Tournament.BANCA to 2_400L))

        val view = tournament.view(viewer = ana)

        assertEquals(listOf("Banca", "Ana", "Rui"), view.standings.map { it.name })
        assertEquals(listOf(null, null, 3), view.standings.map { it.place })
        assertEquals(listOf(false, true, false), view.standings.map { it.you })
        assertEquals(1, view.level)
        assertEquals(1, view.handsToNextLevel)
    }

    @Test
    fun `a new tournament forgets the last`() {
        val tournament = three()
        tournament.handPlayed(mapOf(ana to 4_500L, rui to 0L, Tournament.BANCA to 0L))
        assertTrue(tournament.finished)

        tournament.begin(listOf(rui to "Rui", marta to "Marta"))

        assertTrue(tournament.running)
        assertEquals(1_500, tournament.chipsOf(rui))
        assertEquals(0, tournament.chipsOf(ana))
        assertEquals(listOf("Rui", "Marta"), tournament.view(null).standings.map { it.name }.sorted().reversed())
    }
}

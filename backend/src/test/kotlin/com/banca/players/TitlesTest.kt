package com.banca.players

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The titles a player may wear, and what earns each. */
class TitlesTest {

    private fun round(game: Game, net: Long, detail: JsonObject = buildJsonObject { }) = RoundRecord(
        game = game,
        endedAt = Instant.parse("2026-10-06T12:00:00Z"),
        staked = 100,
        net = net,
        outcome = if (net > 0) RoundOutcome.WIN else if (net < 0) RoundOutcome.LOSS else RoundOutcome.PUSH,
        detail = detail,
    )

    private fun earned(titles: List<TitleView>) = titles.filter { it.earned }.map { it.name }

    @Test
    fun `a newcomer has one title, and is told what the others ask for`() {
        val titles = Titles.all(level = 1, rounds = emptyList(), trophies = emptyList())

        assertEquals(listOf("Newcomer"), earned(titles))
        assertEquals("Reach level 8", titles.first { it.name == "Card Sharp" }.how)
        assertEquals("Call down and beat 5 of Banca's bluffs", titles.first { it.name == "Bluff Catcher" }.how)
        assertTrue(titles.any { it.name == "A league title" && !it.earned }, "the leagues' titles are spoken of even before one is won")
    }

    @Test
    fun `the titles of every level reached stay with the player`() {
        val titles = Titles.all(level = 9, rounds = emptyList(), trophies = emptyList())

        assertEquals(listOf("Newcomer", "Regular", "Player", "Card Sharp"), earned(titles))
    }

    @Test
    fun `the level titles are the ones the levels are named by`() {
        for (level in 1..20) {
            val highest = Titles.all(level, emptyList(), emptyList()).filter { it.earned }.last { it.how.startsWith("Reach") || it.how.startsWith("Yours") }
            assertEquals(Levels.titleOf(level), highest.name)
        }
    }

    @Test
    fun `catching five of Banca's bluffs makes a bluff catcher`() {
        val caught = buildJsonObject { put("vsBanca", true); put("bancaBluffed", true); put("showdown", true); put("pot", 400) }
        fun after(times: Int) = Titles.all(1, List(times) { round(Game.POKER, 200, caught) }, emptyList())

        assertFalse("Bluff Catcher" in earned(after(4)))
        assertEquals("Call down and beat 5 of Banca's bluffs (4 so far)", after(4).first { it.name == "Bluff Catcher" }.how)
        assertTrue("Bluff Catcher" in earned(after(5)))
    }

    @Test
    fun `the other feats are counted from the record`() {
        val solo = buildJsonObject { put("vsBanca", true); put("bancaBluffed", false) }
        val showdown = buildJsonObject { put("showdown", true); put("players", 4) }
        val sound = buildJsonObject { put("byTheBook", 5) }
        val hit = buildJsonObject { put("straightHit", true) }

        val rounds = List(100) { round(Game.POKER, -20, solo) } +
            List(25) { round(Game.POKER, 300, showdown) } +
            List(10) { round(Game.BLACKJACK, 100, sound) } +
            List(3) { round(Game.ROULETTE, 350, hit) }

        assertEquals(
            listOf("Newcomer", "Banca's Rival", "Showdown Artist", "By the Book", "Lucky Number"),
            earned(Titles.all(1, rounds, emptyList())),
        )
        assertEquals(listOf("Newcomer"), earned(Titles.all(1, rounds.drop(1).filterIndexed { index, _ -> index % 2 == 0 }, emptyList())), "and not from half of it")
    }

    @Test
    fun `a trophy is a title, once however often it was won, with the highest first`() {
        val trophies = listOf(
            TrophyView("Bronze", 1, "Bronze Champion", "2026-09-07", 500),
            TrophyView("Silver", 2, "Silver Runner-up", "2026-09-14", 400),
            TrophyView("Bronze", 1, "Bronze Champion", "2026-09-21", 500),
        )
        val titles = Titles.all(1, emptyList(), trophies)

        assertEquals(listOf("Newcomer", "Silver Runner-up", "Bronze Champion"), earned(titles))
        assertFalse(titles.any { it.name == "A league title" })
    }

    @Test
    fun `only an earned title may be worn`() {
        val titles = Titles.all(level = 3, rounds = emptyList(), trophies = emptyList())

        assertTrue(Titles.mayWear("Regular", titles))
        assertFalse(Titles.mayWear("High Roller", titles), "not one still to be earned")
        assertFalse(Titles.mayWear("A league title", titles), "nor the stand-in for the leagues' titles")
        assertFalse(Titles.mayWear("King of Everything", titles), "nor one made up")
    }
}

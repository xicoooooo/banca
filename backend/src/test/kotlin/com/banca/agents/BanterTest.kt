package com.banca.agents

import com.banca.agents.Banter.Moment
import com.banca.sessions.PlayerView
import com.banca.sessions.ResultView
import com.banca.sessions.TableView
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What Banca says when a hand is over: which ending it takes it for, how often it speaks, and what it will not say. */
class BanterTest {

    /** A finished hand as Banca, in seat 1, sees it. */
    private fun ended(
        pot: Long = 800,
        folded: Boolean = false,
        winnings: Map<Int, Long> = mapOf(1 to pot),
        showdown: Map<Int, String> = emptyMap(),
        opponent: String = "You",
        opponentFolded: Boolean = false,
    ) = TableView(
        handNumber = 4,
        street = if (showdown.isEmpty()) "flop" else "showdown",
        board = listOf("Ks", "7d", "2c"),
        pot = pot,
        buttonSeat = 0,
        smallBlind = 10,
        bigBlind = 20,
        yourSeat = 1,
        actorSeat = null,
        players = listOf(
            PlayerView(seat = 0, name = opponent, stack = 1600, committed = 0, status = if (opponentFolded) "folded" else "active", cards = null),
            PlayerView(seat = 1, name = "Banca", stack = 2400, committed = 0, status = if (folded) "folded" else "active", cards = listOf("Ah", "Ad")),
        ),
        legal = null,
        result = ResultView(winnings = winnings, showdown = showdown),
    )

    /** Everything Banca might say about [view], given enough hands to say it all. */
    private fun everythingSaid(view: TableView, net: Long, mood: Mood = Moods.STEADY, bluffed: Boolean = false): Set<String> {
        val random = Random(5)
        return List(600) { Banter.after(view, net, mood, bluffed, random) }.filterNotNull().toSet()
    }

    @Test
    fun `a hand still being played is not remarked on`() {
        assertNull(Banter.momentOf(facingABet(), net = 0, bluffed = false))
        assertNull(Banter.after(facingABet(), net = 0, mood = Moods.STEADY, bluffed = true, random = Random(1)))
    }

    @Test
    fun `it knows how each hand ended for it`() {
        val won = mapOf(0 to "pair", 1 to "two_pair")
        assertEquals(Moment.TOOK, Banter.momentOf(ended(showdown = won), net = 400, bluffed = false))
        assertEquals(Moment.UNCALLED, Banter.momentOf(ended(opponentFolded = true), net = 400, bluffed = false))
        assertEquals(Moment.STOLE, Banter.momentOf(ended(opponentFolded = true), net = 400, bluffed = true))
        assertEquals(Moment.FOLDED, Banter.momentOf(ended(folded = true, winnings = mapOf(0 to 800)), net = -100, bluffed = false))

        val lost = mapOf(0 to 800L)
        assertEquals(Moment.CAUGHT, Banter.momentOf(ended(winnings = lost, showdown = mapOf(0 to "pair", 1 to "high_card")), net = -400, bluffed = true))
        assertEquals(Moment.OUTDRAWN, Banter.momentOf(ended(winnings = lost, showdown = mapOf(0 to "flush", 1 to "three_of_a_kind")), net = -400, bluffed = false))
        assertEquals(Moment.BEATEN, Banter.momentOf(ended(winnings = lost, showdown = mapOf(0 to "two_pair", 1 to "pair")), net = -400, bluffed = false))
        assertEquals(Moment.SPLIT, Banter.momentOf(ended(winnings = mapOf(0 to 400, 1 to 400), showdown = mapOf(0 to "straight", 1 to "straight")), net = 0, bluffed = false))
    }

    @Test
    fun `a bet that came back because nobody could match it is not taken for a win`() {
        // The player was all in for less. Banca lost the pot, and was handed back what went unmatched.
        val view = ended(pot = 2_200, winnings = mapOf(0 to 400, 1 to 1_800), showdown = mapOf(0 to "two_pair", 1 to "pair"))

        assertEquals(Moment.BEATEN, Banter.momentOf(view, net = -200, bluffed = false))
    }

    @Test
    fun `having folded while others played on, it only watched`() {
        val view = ended(folded = true, winnings = mapOf(0 to 800), showdown = mapOf(0 to "pair", 2 to "high_card"))

        assertEquals(Moment.WATCHED, Banter.momentOf(view, net = -40, bluffed = false))
    }

    @Test
    fun `most small hands pass without a word, and a big one it won seldom does`() {
        val random = Random(9)
        val showdown = mapOf(0 to "pair", 1 to "two_pair")
        val small = List(400) { Banter.after(ended(pot = 80, showdown = showdown), 40, Moods.STEADY, false, random) }.count { it != null }
        val big = List(400) { Banter.after(ended(pot = 1_600, showdown = showdown), 800, Moods.STEADY, false, random) }.count { it != null }

        assertTrue(small < 160, "it spoke after $small of 400 small pots")
        assertTrue(big > 320, "it spoke after $big of 400 big ones")
    }

    @Test
    fun `it never says the same thing twice running`() {
        val random = Random(2)
        val view = ended(opponentFolded = true)
        var last: String? = null
        repeat(300) {
            val said = Banter.after(view, 400, Moods.PRESSING, bluffed = true, random = random, not = last) ?: return@repeat
            assertNotEquals(last, said)
            last = said
        }
    }

    @Test
    fun `a mood has lines of its own`() {
        val view = ended(folded = true, winnings = mapOf(0 to 800))

        assertTrue("I can wait." in everythingSaid(view, -100, Moods.PATIENT))
        assertFalse("I can wait." in everythingSaid(view, -100, Moods.PRESSING))
    }

    @Test
    fun `a winner is named only when the table knows them by a name`() {
        val lost = mapOf(0 to 800L)
        val showdown = mapOf(0 to "two_pair", 1 to "pair")

        val toRui = everythingSaid(ended(winnings = lost, showdown = showdown, opponent = "Rui"), -400)
        assertTrue("Well played, Rui." in toRui)

        val alone = everythingSaid(ended(winnings = lost, showdown = showdown), -400)
        assertTrue(alone.isNotEmpty())
        assertTrue(alone.none { "You." in it || "{" in it }, "at a table for one the player has no name to be called by: $alone")
    }

    @Test
    fun `every line is short enough to be read at a glance`() {
        val endings = listOf(
            ended(showdown = mapOf(0 to "pair", 1 to "two_pair")) to 400L,
            ended(opponentFolded = true) to 400L,
            ended(folded = true, winnings = mapOf(0 to 800)) to -100L,
            ended(folded = true, winnings = mapOf(0 to 800), showdown = mapOf(0 to "pair", 2 to "high_card")) to -40L,
            ended(winnings = mapOf(0 to 800), showdown = mapOf(0 to "pair", 1 to "high_card"), opponent = "Rui") to -400L,
            ended(winnings = mapOf(0 to 800), showdown = mapOf(0 to "flush", 1 to "straight"), opponent = "Rui") to -400L,
            ended(winnings = mapOf(0 to 400, 1 to 400), showdown = mapOf(0 to "straight", 1 to "straight")) to 0L,
        )
        val said = endings.flatMap { (view, net) ->
            Moods.ALL.flatMap { mood -> listOf(true, false).flatMap { bluffed -> everythingSaid(view, net, mood, bluffed) } }
        }.toSet()

        assertTrue(said.size > 40, "there is a good deal it can say: ${said.size}")
        assertTrue(said.all { it.length <= 60 }, "too long: ${said.filter { it.length > 60 }}")
    }
}

package com.banca.ws

import com.banca.agents.Advice
import com.banca.agents.BlackjackTools
import com.banca.agents.blackjackDecision
import com.banca.games.blackjack.BlackjackAction
import com.banca.games.blackjack.Strategy
import com.banca.module
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A round's decisions, graded once it is over. */
class ReviewTest {

    private val strategy = Strategy()
    private val sixteenAgainstTen = blackjackDecision(listOf("Ts", "6d"), total = 16, dealerShows = "Kh")
    private val elevenAgainstSix = blackjackDecision(listOf("6s", "5d"), total = 11, dealerShows = "6h")

    private fun grade(view: com.banca.sessions.BlackjackView, action: BlackjackAction, advice: Advice? = null) =
        BlackjackTools(view, strategy).review(action, advice)

    @Test
    fun `the best play costs nothing and needs no explaining`() {
        val review = grade(elevenAgainstSix, BlackjackAction.Double)

        assertEquals("best", review.verdict)
        assertEquals("double", review.played)
        assertEquals("double", review.best)
        assertEquals(0.0, review.cost)
        assertNull(review.reason)
        assertEquals(listOf("6s", "5d"), review.cards)
        assertEquals("6h", review.dealer)
    }

    @Test
    fun `a mistake is priced in chips and says what was better and why`() {
        val review = grade(elevenAgainstSix, BlackjackAction.Stand)

        assertEquals("mistake", review.verdict)
        assertEquals("stand", review.played)
        assertEquals("double", review.best)
        assertTrue(review.bestValue > review.playedValue)
        // The gap per chip, on the hundred staked.
        assertEquals((review.bestValue - review.playedValue) * 100, review.cost, 0.2)
        assertTrue(review.cost > 5)
        assertNotNull(review.reason)
    }

    @Test
    fun `plays within a whisker of each other are both right`() {
        // Sixteen against a ten is the closest call in the game.
        val values = BlackjackTools(sixteenAgainstTen, strategy).values
        val gap = values[0].value - values.first { it.action == BlackjackAction.Stand }.value
        val review = grade(sixteenAgainstTen, BlackjackAction.Stand)

        if (gap <= BlackjackTools.CLOSE_ENOUGH) {
            assertEquals("best", review.verdict)
            assertEquals("stand", review.best, "the play made is not second-guessed")
        } else {
            assertEquals("slip", review.verdict, "a little given up is not called a mistake")
            assertTrue(review.cost < 5)
        }
    }

    @Test
    fun `taking insurance is a slip that costs a few chips`() {
        val offered = blackjackDecision(listOf("Ts", "9d"), total = 19, dealerShows = "Ah", phase = "insurance")

        assertEquals("best", grade(offered, BlackjackAction.DeclineInsurance).verdict)
        val insured = grade(offered, BlackjackAction.Insure)
        assertEquals("decline_insurance", insured.best)
        assertEquals(3.8, insured.cost, 0.1)
    }

    @Test
    fun `it is noted whether the coach was asked and listened to`() {
        val advice = BlackjackTools(elevenAgainstSix, strategy).bookAdvice()

        assertNull(grade(elevenAgainstSix, BlackjackAction.Double).coach)
        assertEquals("followed", grade(elevenAgainstSix, BlackjackAction.Double, advice).coach)
        assertEquals("ignored", grade(elevenAgainstSix, BlackjackAction.Hit, advice).coach)
    }

    @Test
    fun `a round's decisions are kept in order and added up, and a new round starts clean`() {
        val tally = DecisionTally(strategy)
        assertNull(tally.review(), "no decisions, nothing to review")

        tally.note(elevenAgainstSix, BlackjackAction.Stand, advice = null)
        tally.note(elevenAgainstSix, BlackjackAction.Double, advice = null)

        val review = assertNotNull(tally.review())
        assertEquals(listOf("stand", "double"), review.decisions.map { it.played })
        assertEquals(1, review.sound)
        assertEquals(review.decisions.first().cost, review.cost)
        assertEquals(2, tally.decisions)
        assertEquals(1, tally.byTheBook)

        tally.reset()
        assertNull(tally.review())
    }

    // ---------------------------------------------------------------- at the table

    private suspend fun DefaultClientWebSocketSession.say(json: String) = send(Frame.Text(json))

    private suspend fun DefaultClientWebSocketSession.nextView(): JsonObject {
        while (true) {
            val message = receiveJson()
            if (message.getValue("type").jsonPrimitive.content == "state") return message.getValue("view").jsonObject
        }
    }

    private fun JsonObject.phase() = getValue("phase").jsonPrimitive.content

    @Test
    fun `a settled round arrives with its decisions graded, and a round with none arrives without`() = testApplication {
        val guest = TestPlayers()
        application { module(blackjack = BlackjackSocketConfig(random = { Random(11) }), players = guest.players) }

        createClient { install(WebSockets) }.webSocket("/ws/blackjack") {
            sayHello(guest.token)
            assertTrue(nextView().getValue("review") is JsonNull, "nothing to review before a round")

            var reviewed = 0
            repeat(12) {
                say("""{"type":"bet","amount":50}""")
                var view = nextView()
                var made = 0
                while (view.phase() != "settled") {
                    assertTrue(view.getValue("review") is JsonNull, "the review waits for the round to end")
                    say("""{"type":"act","action":"${if (view.phase() == "insurance") "decline_insurance" else "stand"}"}""")
                    made++
                    view = nextView()
                }

                val review = view.getValue("review")
                if (made == 0) {
                    assertTrue(review is JsonNull, "a natural leaves nothing to grade")
                } else {
                    val decisions = review.jsonObject.getValue("decisions").jsonArray
                    assertEquals(made, decisions.size)
                    assertEquals(decisions.count { it.jsonObject.getValue("verdict").jsonPrimitive.content == "best" }, review.jsonObject.getValue("sound").jsonPrimitive.int)
                    assertEquals(decisions.sumOf { it.jsonObject.getValue("cost").jsonPrimitive.double }, review.jsonObject.getValue("cost").jsonPrimitive.double, 0.11)
                    reviewed++
                }
            }
            assertTrue(reviewed > 0)
        }
    }
}

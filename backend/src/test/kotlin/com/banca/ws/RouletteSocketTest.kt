package com.banca.ws

import com.banca.games.roulette.Wheel
import com.banca.module
import com.banca.players.FinishedRound
import com.banca.players.Game
import com.banca.players.RoundOutcome
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouletteSocketTest {

    private fun table(guest: TestPlayers = TestPlayers(), seed: Int = 5, test: suspend DefaultClientWebSocketSession.() -> Unit) =
        testApplication {
            application { module(roulette = RouletteSocketConfig(random = { Random(seed) }), players = guest.players) }
            createClient { install(WebSockets) }.webSocket("/ws/roulette") {
                sayHello(guest.token)
                test()
            }
        }

    private suspend fun DefaultClientWebSocketSession.say(json: String) = send(Frame.Text(json))

    private suspend fun DefaultClientWebSocketSession.view(): JsonObject =
        receiveJson().also { assertEquals("state", it.getValue("type").jsonPrimitive.content) }.getValue("view").jsonObject

    /** Where the wheel seeded with [seed] lands on its first spin. */
    private fun firstPocket(seed: Int) = Wheel.spin(Random(seed))

    @Test
    fun `sitting down shows a table waiting for bets`() = table {
        val view = view()

        assertEquals(0, view.getValue("roundNumber").jsonPrimitive.int)
        assertEquals(2_000, view.getValue("stack").jsonPrimitive.long)
        assertEquals(10, view.getValue("minBet").jsonPrimitive.long)
        assertTrue(view.getValue("history").jsonArray.isEmpty())
        assertTrue(view.getValue("result") is JsonNull)
    }

    @Test
    fun `a spin answers with where the ball landed and what each bet came to`() {
        val pocket = firstPocket(5)
        val guest = TestPlayers()

        table(guest) {
            view()
            say("""{"type":"spin","bets":[{"kind":"straight","number":$pocket,"amount":10},{"kind":"straight","number":${(pocket + 1) % 37},"amount":10}]}""")

            val view = view()
            val result = view.getValue("result").jsonObject
            assertEquals(pocket, result.getValue("pocket").jsonPrimitive.int)
            assertEquals(Wheel.colorOf(pocket).name.lowercase(), result.getValue("color").jsonPrimitive.content)
            assertEquals(listOf(360L, 0L), result.getValue("wagers").jsonArray.map { it.jsonObject.getValue("returned").jsonPrimitive.long })
            assertEquals(20, result.getValue("staked").jsonPrimitive.long)
            assertEquals(340, result.getValue("net").jsonPrimitive.long)

            assertEquals(1, view.getValue("roundNumber").jsonPrimitive.int)
            assertEquals(2_340, view.getValue("stack").jsonPrimitive.long)
            assertEquals(listOf(pocket), view.getValue("history").jsonArray.map { it.jsonPrimitive.int })
        }

        assertEquals(2_340, runBlocking { guest.players.balance(guest.player) })
    }

    @Test
    fun `the spin is written to the player's record`() {
        val pocket = firstPocket(5)
        val guest = TestPlayers()

        table(guest) {
            view()
            say("""{"type":"spin","bets":[{"kind":"straight","number":$pocket,"amount":10},{"kind":"red","amount":50},{"kind":"black","amount":50}]}""")
            view()
        }

        val round = runBlocking { guest.store.rounds(guest.player.id, 1) }.single()
        assertEquals(Game.ROULETTE, round.game)
        assertEquals(110, round.staked)
        assertEquals(RoundOutcome.WIN, round.outcome)
        assertEquals(pocket, round.detail.getValue("pocket").jsonPrimitive.int)
        assertEquals(3, round.detail.getValue("bets").jsonPrimitive.int)
        assertEquals(10, round.detail.getValue("insideStake").jsonPrimitive.long)
        assertEquals(100, round.detail.getValue("outsideStake").jsonPrimitive.long)
        assertTrue(round.detail.getValue("straightHit").jsonPrimitive.boolean)
    }

    @Test
    fun `chips on the same bet are counted together`() = table {
        view()
        say("""{"type":"spin","bets":[{"kind":"red","amount":25},{"kind":"red","amount":25}]}""")

        val result = view().getValue("result").jsonObject
        val wager = result.getValue("wagers").jsonArray.single().jsonObject
        assertEquals(50, wager.getValue("amount").jsonPrimitive.long)
        assertEquals(50, result.getValue("staked").jsonPrimitive.long)
    }

    @Test
    fun `bets the table does not take are refused whole, and nothing is spun`() {
        val guest = TestPlayers()

        table(guest) {
            view()
            suspend fun refused(bets: String) {
                say("""{"type":"spin","bets":$bets}""")
                assertEquals("error", receiveJson().getValue("type").jsonPrimitive.content, bets)
            }

            refused("[]")
            refused("""[{"kind":"red","amount":5}]""")
            refused("""[{"kind":"red","amount":501}]""")
            refused("""[{"kind":"straight","number":17,"amount":101}]""")
            refused("""[{"kind":"straight","number":40,"amount":10}]""")
            refused("""[{"kind":"straight","amount":10}]""")
            refused("""[{"kind":"split","number":1,"other":5,"amount":10}]""")
            refused("""[{"kind":"lucky","amount":10}]""")
            refused("""[{"kind":"red","amount":50},{"kind":"black","amount":-50}]""")
            // Affordable one at a time, not together.
            refused("""[{"kind":"red","amount":500},{"kind":"black","amount":500},{"kind":"even","amount":500},{"kind":"odd","amount":500},{"kind":"low","amount":10}]""")

            say("""{"type":"spin","bets":[{"kind":"red","amount":10}]}""")
            assertEquals(1, view().getValue("roundNumber").jsonPrimitive.int, "the table carries on")
        }

        assertEquals(1, runBlocking { guest.store.rounds(guest.player.id, 10) }.size)
    }

    @Test
    fun `every kind of bet on the layout can be placed`() = table {
        view()
        say(
            """{"type":"spin","bets":[
              {"kind":"straight","number":0,"amount":10},{"kind":"split","number":17,"other":20,"amount":10},
              {"kind":"street","number":16,"amount":10},{"kind":"corner","number":16,"amount":10},
              {"kind":"six_line","number":16,"amount":10},{"kind":"dozen","number":2,"amount":10},
              {"kind":"column","number":2,"amount":10},{"kind":"red","amount":10},{"kind":"black","amount":10},
              {"kind":"even","amount":10},{"kind":"odd","amount":10},{"kind":"low","amount":10},{"kind":"high","amount":10}]}""",
        )

        val result = view().getValue("result").jsonObject
        assertEquals(13, result.getValue("wagers").jsonArray.size)
        assertEquals(130, result.getValue("staked").jsonPrimitive.long)
    }

    @Test
    fun `the ball's recent landings are remembered, newest first`() = table {
        view()
        val landed = mutableListOf<Int>()
        repeat(14) {
            say("""{"type":"spin","bets":[{"kind":"red","amount":10},{"kind":"black","amount":10}]}""")
            val view = view()
            landed.add(0, view.getValue("result").jsonObject.getValue("pocket").jsonPrimitive.int)
            assertEquals(landed.take(12), view.getValue("history").jsonArray.map { it.jsonPrimitive.int })
        }
    }

    private suspend fun DefaultClientWebSocketSession.nextOf(type: String): JsonObject {
        while (true) {
            val message = receiveJson()
            if (message.getValue("type").jsonPrimitive.content == type) return message
        }
    }

    @Test
    fun `asked about a layout, Banca shows its working and gives a read with the figures`() = table {
        view()
        say("""{"type":"analyse","bets":[{"kind":"straight","number":17,"amount":10},{"kind":"red","amount":50}]}""")

        val steps = mutableListOf<String>()
        var message = receiveJson()
        while (message.getValue("type").jsonPrimitive.content == "trace") {
            steps += message.getValue("event").jsonObject.getValue("label").jsonPrimitive.content
            message = receiveJson()
        }

        assertEquals("read", message.getValue("type").jsonPrimitive.content)
        val read = message.getValue("read").jsonObject
        assertTrue(read.getValue("text").jsonPrimitive.content.isNotBlank())
        assertEquals(60, read.getValue("figures").jsonObject.getValue("staked").jsonPrimitive.long)
        assertEquals(listOf("Worked out your chances", "Worked out what it costs", "Gave its read"), steps)

        // Nothing was spun or charged by asking.
        say("""{"type":"spin","bets":[{"kind":"red","amount":10}]}""")
        val view = nextOf("state").getValue("view").jsonObject
        assertEquals(1, view.getValue("roundNumber").jsonPrimitive.int)
    }

    @Test
    fun `the same layout asked about twice is read once`() {
        var asked = 0
        val counting = com.banca.agents.RouletteAdvisor { wagers, chips, trace -> asked++; com.banca.agents.BookAnalyst().read(wagers, chips, trace) }

        testApplication {
            val guest = TestPlayers()
            application { module(roulette = RouletteSocketConfig(random = { Random(5) }, analyst = counting), players = guest.players) }
            createClient { install(WebSockets) }.webSocket("/ws/roulette") {
                sayHello(guest.token)
                view()
                say("""{"type":"analyse","bets":[{"kind":"red","amount":50},{"kind":"straight","number":17,"amount":10}]}""")
                val first = nextOf("read").getValue("read")
                // The same chips, put down in another order.
                say("""{"type":"analyse","bets":[{"kind":"straight","number":17,"amount":10},{"kind":"red","amount":25},{"kind":"red","amount":25}]}""")
                assertEquals(first, nextOf("read").getValue("read"))
                assertEquals(1, asked)

                say("""{"type":"analyse","bets":[{"kind":"black","amount":50}]}""")
                nextOf("read")
                assertEquals(2, asked, "a different layout is a different question")
            }
        }
    }

    @Test
    fun `a layout the table would not take is not read either`() = table {
        view()
        say("""{"type":"analyse","bets":[]}""")
        assertEquals("error", receiveJson().getValue("type").jsonPrimitive.content)
        say("""{"type":"analyse","bets":[{"kind":"straight","number":17,"amount":500}]}""")
        assertEquals("error", receiveJson().getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `a player who spins while Banca is thinking is not kept waiting`() {
        val slow = com.banca.agents.RouletteAdvisor { wagers, chips, trace ->
            kotlinx.coroutines.delay(30_000)
            com.banca.agents.BookAnalyst().read(wagers, chips, trace)
        }

        testApplication {
            val guest = TestPlayers()
            application { module(roulette = RouletteSocketConfig(random = { Random(5) }, analyst = slow), players = guest.players) }
            createClient { install(WebSockets) }.webSocket("/ws/roulette") {
                sayHello(guest.token)
                view()
                say("""{"type":"analyse","bets":[{"kind":"red","amount":50}]}""")
                say("""{"type":"spin","bets":[{"kind":"red","amount":50}]}""")

                val next = kotlinx.coroutines.withTimeout(3_000) { receiveJson() }
                assertEquals("state", next.getValue("type").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `a player left without chips is staked by the house, and one staked too lately is not dealt in`() {
        val guest = TestPlayers()
        runBlocking {
            guest.players.claimDaily(guest.player)
            guest.players.settle(guest.player, FinishedRound(Game.BLACKJACK, "t", 2_190, -2_190, RoundOutcome.LOSS, JsonObject(emptyMap())))
        }
        // Ten chips on a number that will not come up, twice over.
        val miss = (firstPocket(5) + 1) % 37

        table(guest) {
            assertEquals(10, view().getValue("stack").jsonPrimitive.long)

            say("""{"type":"spin","bets":[{"kind":"straight","number":$miss,"amount":10}]}""")
            val staked = view()
            assertEquals(500, staked.getValue("stack").jsonPrimitive.long)
            assertTrue(staked.getValue("result").jsonObject.getValue("refilled").jsonPrimitive.boolean)

            runBlocking {
                guest.players.settle(guest.player, FinishedRound(Game.BLACKJACK, "t", 500, -500, RoundOutcome.LOSS, JsonObject(emptyMap())))
            }
            say("""{"type":"spin","bets":[{"kind":"red","amount":10}]}""")
            assertEquals(0, view().getValue("stack").jsonPrimitive.long)
            assertEquals("broke", receiveJson().getValue("type").jsonPrimitive.content)
        }

        assertEquals(1, runBlocking { guest.store.rounds(guest.player.id, 10) }.count { it.game == Game.ROULETTE }, "nothing was spun for the broke player")
    }
}

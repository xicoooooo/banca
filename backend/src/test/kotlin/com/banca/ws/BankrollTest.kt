package com.banca.ws

import com.banca.module
import com.banca.players.FinishedRound
import com.banca.players.Game
import com.banca.players.LedgerReason
import com.banca.players.RoundOutcome
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** That chips belong to the player and follow them from table to table. */
class BankrollTest {

    private fun ApplicationTestBuilder.serve(guest: TestPlayers) {
        application {
            module(
                tableSocket = TableSocketConfig(opponentDelay = Duration.ZERO, random = { Random(3) }),
                blackjack = BlackjackSocketConfig(random = { Random(4) }),
                players = guest.players,
            )
        }
    }

    private fun ApplicationTestBuilder.sockets() = createClient { install(WebSockets) }

    private suspend fun DefaultClientWebSocketSession.say(json: String) = send(Frame.Text(json))

    private suspend fun DefaultClientWebSocketSession.view(): JsonObject = receiveJson().getValue("view").jsonObject

    /** Plays one round of blackjack to its end and returns the settled view. */
    private suspend fun DefaultClientWebSocketSession.playBlackjackRound(bet: Int = 100): JsonObject {
        say("""{"type":"bet","amount":$bet}""")
        var view = view()
        while (view.getValue("phase").jsonPrimitive.content != "settled") {
            val insurance = view.getValue("phase").jsonPrimitive.content == "insurance"
            say("""{"type":"act","action":"${if (insurance) "decline_insurance" else "stand"}"}""")
            view = view()
        }
        return view
    }

    @Test
    fun `a stranger is turned away before anything is dealt`() = testApplication {
        serve(TestPlayers())

        sockets().webSocket("/ws/blackjack") {
            send(Frame.Text("""{"type":"hello","token":"not-a-real-token"}"""))

            val answer = receiveJson()
            assertEquals("error", answer.getValue("type").jsonPrimitive.content)
            assertEquals("unknown_player", answer.getValue("code").jsonPrimitive.content)
            assertNull(incoming.receiveCatching().getOrNull(), "the connection is closed")
        }
    }

    @Test
    fun `the welcome says who the player is and what they have`() = testApplication {
        val guest = TestPlayers()
        serve(guest)

        sockets().webSocket("/ws/blackjack") {
            val player = sayHello(guest.token).getValue("player").jsonObject
            assertEquals(guest.player.name, player.getValue("name").jsonPrimitive.content)
            assertEquals(2_000, player.getValue("balance").jsonPrimitive.long)
        }
    }

    @Test
    fun `a blackjack round is written to the ledger as it ends`() = testApplication {
        val guest = TestPlayers()
        serve(guest)

        sockets().webSocket("/ws/blackjack") {
            sayHello(guest.token)
            view()
            val settled = playBlackjackRound()
            val net = settled.getValue("result").jsonObject.getValue("net").jsonPrimitive.long

            assertEquals(2_000 + net, guest.players.balance(guest.player))
            assertEquals(2_000 + net, settled.getValue("stack").jsonPrimitive.long, "the table shows the bankroll")

            val round = guest.store.rounds(guest.player.id, 10).single()
            assertEquals(Game.BLACKJACK, round.game)
            assertEquals(net, round.net)
            assertEquals(100, round.staked)
            assertTrue("dealerTotal" in round.detail)
        }
    }

    @Test
    fun `chips carry from one visit to the next`() = testApplication {
        val guest = TestPlayers()
        serve(guest)

        var after = 0L
        sockets().webSocket("/ws/blackjack") {
            sayHello(guest.token)
            view()
            after = playBlackjackRound().getValue("stack").jsonPrimitive.long
        }

        sockets().webSocket("/ws/blackjack") {
            val balance = sayHello(guest.token).getValue("player").jsonObject.getValue("balance").jsonPrimitive.long
            assertEquals(after, balance)
            assertEquals(after, view().getValue("stack").jsonPrimitive.long, "not reset to the opening chips")
        }
    }

    @Test
    fun `poker is played from the same bankroll and records how the hand went`() = testApplication {
        val guest = TestPlayers()
        serve(guest)
        // A lean bankroll, to show the stack at the table is the bankroll and not a fresh 2,000.
        runBlocking {
            guest.players.settle(guest.player, FinishedRound(Game.BLACKJACK, "t", 500, -500, RoundOutcome.LOSS, JsonObject(emptyMap())))
        }

        sockets().webSocket("/ws/table") {
            sayHello(guest.token)

            var view = view()
            val me = view.getValue("players").jsonArray[0].jsonObject
            assertEquals(1_500, me.getValue("stack").jsonPrimitive.long + me.getValue("committed").jsonPrimitive.long)

            // Fold at the first chance, whoever acts first.
            while (view["result"] is JsonNull) {
                if (view.getValue("actorSeat").jsonPrimitive.int == 0) say("""{"type":"act","action":"fold"}""")
                view = receiveStateView()
            }
        }

        val hand = guest.store.rounds(guest.player.id, 10).first()
        assertEquals(Game.POKER, hand.game)
        assertEquals(RoundOutcome.LOSS, hand.outcome)
        assertTrue(hand.net < 0)
        assertTrue(hand.detail.getValue("folded").jsonPrimitive.boolean)
        assertEquals(1_500 + hand.net, guest.players.balance(guest.player))
    }

    /** Leaves the guest with [chips], by way of one large loss. */
    private fun TestPlayers.leaveWith(chips: Long) = runBlocking {
        val balance = players.balance(player)
        players.settle(player, FinishedRound(Game.BLACKJACK, "t", balance - chips, chips - balance, RoundOutcome.LOSS, JsonObject(emptyMap())))
    }

    @Test
    fun `a player who cannot cover the smallest bet is staked by the house, and told`() = testApplication {
        val guest = TestPlayers()
        serve(guest)
        guest.leaveWith(5)

        sockets().webSocket("/ws/blackjack") {
            sayHello(guest.token)
            assertEquals(505, view().getValue("stack").jsonPrimitive.long)

            val notice = receiveJson()
            assertEquals("staked", notice.getValue("type").jsonPrimitive.content)
            assertEquals(500, notice.getValue("amount").jsonPrimitive.long)
        }

        val ledger = guest.store.ledger(guest.player.id, 10)
        assertEquals(LedgerReason.BUST_TOP_UP, ledger.last().reason)
        assertEquals(500, ledger.last().amount, "a modest stake, written down")
    }

    @Test
    fun `the house does not stake the same player twice in a row, and blackjack is not dealt`() = testApplication {
        val guest = TestPlayers()
        serve(guest)
        runBlocking { guest.players.claimDaily(guest.player) }
        guest.leaveWith(5)
        runBlocking { guest.players.fund(guest.player, 10) }
        guest.leaveWith(5)

        sockets().webSocket("/ws/blackjack") {
            sayHello(guest.token)
            assertEquals(5, view().getValue("stack").jsonPrimitive.long, "what they have, and no more")

            val notice = receiveJson()
            assertEquals("broke", notice.getValue("type").jsonPrimitive.content)
            assertEquals(false, notice.getValue("dailyReady").jsonPrimitive.boolean)
            assertEquals("2026-10-06T16:00:00Z", notice.getValue("nextChipsAt").jsonPrimitive.content, "four hours after the last stake")

            say("""{"type":"bet","amount":10}""")
            assertEquals("betting", view().getValue("phase").jsonPrimitive.content, "the bet is not taken")
            assertEquals("broke", receiveJson().getValue("type").jsonPrimitive.content)
        }
        assertEquals(5, guest.players.balance(guest.player))
    }

    @Test
    fun `a broke player can play again once the house will stake them`() = testApplication {
        val guest = TestPlayers()
        serve(guest)
        runBlocking { guest.players.fund(guest.player.also { guest.leaveWith(5) }, 10) }
        guest.leaveWith(5)

        sockets().webSocket("/ws/blackjack") {
            sayHello(guest.token)
            view()
            assertEquals("broke", receiveJson().getValue("type").jsonPrimitive.content)

            guest.clock.advance(java.time.Duration.ofHours(4))
            say("""{"type":"bet","amount":10}""")

            assertEquals(505, view().getValue("stack").jsonPrimitive.long)
            assertEquals("staked", receiveJson().getValue("type").jsonPrimitive.content)
            assertEquals(1, view().getValue("roundNumber").jsonPrimitive.int, "and the bet is taken")
        }
    }

    @Test
    fun `poker deals nothing to a broke player and says why`() = testApplication {
        val guest = TestPlayers()
        serve(guest)
        runBlocking { guest.players.fund(guest.player.also { guest.leaveWith(5) }, 20) }
        guest.leaveWith(15)

        sockets().webSocket("/ws/table") {
            sayHello(guest.token)

            val notice = receiveJson()
            assertEquals("broke", notice.getValue("type").jsonPrimitive.content)
            assertTrue(notice.getValue("dailyReady").jsonPrimitive.boolean, "today's reward is still there for the taking")

            // Claiming it is enough to be dealt in.
            runBlocking { guest.players.claimDaily(guest.player) }
            say("""{"type":"next_hand"}""")
            assertEquals(1, receiveStateView().getValue("handNumber").jsonPrimitive.int)
        }
    }

    @Test
    fun `poker tells a player the house has staked them before dealing`() = testApplication {
        val guest = TestPlayers()
        serve(guest)
        guest.leaveWith(15)

        sockets().webSocket("/ws/table") {
            sayHello(guest.token)

            assertEquals("staked", receiveJson().getValue("type").jsonPrimitive.content)
            val me = receiveStateView().getValue("players").jsonArray[0].jsonObject
            assertEquals(515, me.getValue("stack").jsonPrimitive.long + me.getValue("committed").jsonPrimitive.long)
        }
    }

    /** Skips the opponent's trace messages to the next state. */
    private suspend fun DefaultClientWebSocketSession.receiveStateView(): JsonObject {
        while (true) {
            val message = receiveJson()
            if (message.getValue("type").jsonPrimitive.content == "state") return message.getValue("view").jsonObject
        }
    }
}

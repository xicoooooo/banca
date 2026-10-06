package com.banca.players

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What any player store must do, run against each implementation so the one
 * used in tests and the one used in production cannot drift apart.
 */
abstract class PlayerStoreContract {

    /** The store under test, or null when it is not available here. */
    abstract fun store(): PlayerStore?

    private fun with(test: suspend (PlayerStore) -> Unit) {
        val store = store()
        assumeTrue(store != null, "this store is not available in this environment")
        runBlocking { test(store!!) }
    }

    private fun token() = UUID.randomUUID().toString()

    private fun round(net: Long, game: Game = Game.BLACKJACK, staked: Long = 100) = FinishedRound(
        game = game,
        tableId = "table-1",
        staked = staked,
        net = net,
        outcome = if (net > 0) RoundOutcome.WIN else if (net < 0) RoundOutcome.LOSS else RoundOutcome.PUSH,
        detail = buildJsonObject { put("note", "net $net") },
    )

    @Test
    fun `a new player has their opening chips and an entry that explains them`() = with { store ->
        val player = store.create("Ana", token(), openingChips = 2_000)

        assertEquals("Ana", player.name)
        assertEquals(2_000, store.balance(player.id))
        val entry = store.ledger(player.id, 10).single()
        assertEquals(2_000, entry.amount)
        assertEquals(LedgerReason.SIGNUP_GRANT, entry.reason)
        assertTrue(store.rounds(player.id, 10).isEmpty())
    }

    @Test
    fun `a player is found again by their token and by nothing else`() = with { store ->
        val mine = token()
        val player = store.create("Ana", mine, 2_000)

        assertEquals(player.id, assertNotNull(store.findByTokenHash(mine)).id)
        assertNull(store.findByTokenHash(token()))
    }

    @Test
    fun `a round moves the balance and is remembered with its detail`() = with { store ->
        val player = store.create("Ana", token(), 2_000)

        assertEquals(2_150, store.recordRound(player.id, round(net = 150)))
        assertEquals(2_050, store.recordRound(player.id, round(net = -100, game = Game.POKER, staked = 100)))
        assertEquals(2_050, store.balance(player.id))

        val rounds = store.rounds(player.id, 10)
        assertEquals(listOf(-100L, 150L), rounds.map { it.net }, "newest first")
        assertEquals(listOf(Game.POKER, Game.BLACKJACK), rounds.map { it.game })
        assertEquals(listOf(RoundOutcome.LOSS, RoundOutcome.WIN), rounds.map { it.outcome })
        assertEquals("net 150", rounds[1].detail.getValue("note").jsonPrimitive.content)
        assertEquals(100, rounds[0].staked)
    }

    @Test
    fun `the balance is always the sum of the ledger`() = with { store ->
        val player = store.create("Ana", token(), 2_000)
        listOf(150L, -100L, 0L, -400L, 75L).forEach { store.recordRound(player.id, round(it)) }
        store.grant(player.id, 500, LedgerReason.BUST_TOP_UP)

        val ledger = store.ledger(player.id, 100)
        assertEquals(store.balance(player.id), ledger.sumOf { it.amount })
        assertEquals(LedgerReason.SIGNUP_GRANT, ledger.first().reason, "oldest first")
        assertEquals(LedgerReason.BUST_TOP_UP, ledger.last().reason)
        assertEquals(7, ledger.size)
    }

    @Test
    fun `limits keep the newest rounds and the newest entries`() = with { store ->
        val player = store.create("Ana", token(), 2_000)
        (1L..6L).forEach { store.recordRound(player.id, round(net = it)) }

        assertEquals(listOf(6L, 5L, 4L), store.rounds(player.id, 3).map { it.net })
        assertEquals(listOf(5L, 6L), store.ledger(player.id, 2).map { it.amount }, "the last two, in order")
    }

    @Test
    fun `players do not see each other's chips or rounds`() = with { store ->
        val ana = store.create("Ana", token(), 2_000)
        val rui = store.create("Rui", token(), 2_000)
        store.recordRound(ana.id, round(net = 300))

        assertEquals(2_300, store.balance(ana.id))
        assertEquals(2_000, store.balance(rui.id))
        assertTrue(store.rounds(rui.id, 10).isEmpty())
    }

    @Test
    fun `a player can be renamed`() = with { store ->
        val mine = token()
        val player = store.create("Ana", mine, 2_000)

        assertEquals("Ana Sofia", store.rename(player.id, "Ana Sofia").name)
        assertEquals("Ana Sofia", assertNotNull(store.findByTokenHash(mine)).name)
    }
}

class InMemoryPlayerStoreTest : PlayerStoreContract() {
    override fun store(): PlayerStore = InMemoryPlayerStore()
}

/** Runs only where TEST_DATABASE_URL points at a database with the migrations applied. */
class PostgresPlayerStoreTest : PlayerStoreContract() {
    override fun store(): PlayerStore? = shared

    private companion object {
        val shared: PlayerStore? by lazy { System.getenv("TEST_DATABASE_URL")?.let(PostgresPlayerStore::connect) }
    }
}

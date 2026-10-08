package com.banca.players

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `a player can be known by several tokens, and each can be taken away alone`() = with { store ->
        val phone = token()
        val laptop = token()
        val player = store.create("Ana", phone, 2_000)
        store.addToken(player.id, laptop)

        assertEquals(player.id, assertNotNull(store.findByTokenHash(laptop)).id)

        store.removeToken(phone)
        assertNull(store.findByTokenHash(phone))
        assertEquals(player.id, assertNotNull(store.findByTokenHash(laptop)).id, "the other device is untouched")
        assertEquals(2_000, store.balance(player.id), "and so is the profile")
    }

    @Test
    fun `a profile saved to an account is found by it`() = with { store ->
        val account = UUID.randomUUID().toString()
        val mine = token()
        val player = store.create("Ana", mine, 2_000)
        assertNull(player.accountId)
        assertNull(store.findByAccount(account))

        assertEquals(account, store.linkAccount(player.id, account).accountId)

        assertEquals(player.id, assertNotNull(store.findByAccount(account)).id)
        assertEquals(account, assertNotNull(store.findByTokenHash(mine)).accountId)
    }

    @Test
    fun `an account has one profile and a profile one account`() = with { store ->
        val account = UUID.randomUUID().toString()
        val ana = store.create("Ana", token(), 2_000)
        val rui = store.create("Rui", token(), 2_000)
        store.linkAccount(ana.id, account)

        assertTrue(runCatching { store.linkAccount(rui.id, account) }.isFailure, "the account is taken")
        assertTrue(runCatching { store.linkAccount(ana.id, UUID.randomUUID().toString()) }.isFailure, "the profile is taken")
        assertEquals(ana.id, assertNotNull(store.findByAccount(account)).id)
    }

    @Test
    fun `a grant that may only happen once in a while is refused the second time`() = with { store ->
        val player = store.create("Ana", token(), 2_000)
        val since = Instant.now().minusSeconds(3_600)

        assertEquals(2_200, store.grantUnlessSince(player.id, 200, LedgerReason.DAILY_REWARD, since))
        assertNull(store.grantUnlessSince(player.id, 200, LedgerReason.DAILY_REWARD, since))
        assertEquals(2_200, store.balance(player.id))

        assertEquals(2_700, store.grantUnlessSince(player.id, 500, LedgerReason.BUST_TOP_UP, since), "each reason is counted alone")
        assertEquals(
            2_900,
            store.grantUnlessSince(player.id, 200, LedgerReason.DAILY_REWARD, Instant.now().plusSeconds(60)),
            "and one from before the period does not count",
        )
    }

    @Test
    fun `two claims arriving together are paid once`() = with { store ->
        val player = store.create("Ana", token(), 2_000)
        val since = Instant.now().minusSeconds(3_600)

        val paid = coroutineScope {
            (1..8).map { async(Dispatchers.Default) { store.grantUnlessSince(player.id, 200, LedgerReason.DAILY_REWARD, since) } }.awaitAll()
        }

        assertEquals(1, paid.count { it != null })
        assertEquals(2_200, store.balance(player.id))
    }

    @Test
    fun `the moments a player was given chips for a reason are listed newest first`() = with { store ->
        val player = store.create("Ana", token(), 2_000)
        assertTrue(store.grantsOf(player.id, LedgerReason.DAILY_REWARD, 10).isEmpty())

        store.grant(player.id, 200, LedgerReason.DAILY_REWARD)
        store.grant(player.id, 500, LedgerReason.BUST_TOP_UP)
        store.grant(player.id, 300, LedgerReason.DAILY_REWARD)

        val claims = store.grantsOf(player.id, LedgerReason.DAILY_REWARD, 10)
        assertEquals(2, claims.size)
        assertTrue(!claims[0].isBefore(claims[1]))
        assertEquals(1, store.grantsOf(player.id, LedgerReason.DAILY_REWARD, 1).size)
    }

    /** A signed-in player with a name nobody else in a shared database will have. */
    private suspend fun member(store: PlayerStore, name: String): Player {
        val player = store.create(name, token(), 2_000)
        return store.linkAccount(player.id, UUID.randomUUID().toString())
    }

    @Test
    fun `standings count each signed-in player's rounds in the window, and nobody else's`() = with { store ->
        val ana = member(store, "Ana-${token()}")
        val idle = member(store, "Idle-${token()}")
        val guest = store.create("Guest-${token()}", token(), 2_000)
        store.recordRound(ana.id, round(net = 150, game = Game.POKER))
        store.recordRound(ana.id, round(net = -40))
        store.recordRound(guest.id, round(net = 900))

        val from = Instant.now().minusSeconds(3_600)
        val until = Instant.now().plusSeconds(3_600)
        val all = store.standings(from, until).associateBy { it.playerId }

        assertEquals(110, all.getValue(ana.id).net)
        assertEquals(2, all.getValue(ana.id).rounds)
        assertEquals(0, all.getValue(ana.id).tier)
        assertEquals(0, all.getValue(idle.id).rounds, "a signed-in player who has not played is still listed")
        assertEquals(0, all.getValue(idle.id).net)
        assertTrue(guest.id !in all, "a guest is in no league")

        assertEquals(150, store.standings(from, until, Game.POKER).first { it.playerId == ana.id }.net, "one game at a time")
        assertEquals(0, store.standings(from, until, Game.ROULETTE).first { it.playerId == ana.id }.rounds)
        assertEquals(0, store.standings(until, until.plusSeconds(60)).first { it.playerId == ana.id }.rounds, "nothing outside the window")
    }

    @Test
    fun `a round at a private table moves chips and is kept, but counts for nothing in the standings`() = with { store ->
        val ana = member(store, "Ana-${token()}")
        store.recordRound(ana.id, round(net = 100))
        // Won from a friend at a table of their own: real chips, and no way to climb a league.
        store.recordRound(ana.id, round(net = 5_000, game = Game.POKER).copy(tableId = "invite:k7x2m9"))

        val mine = store.standings(Instant.now().minusSeconds(3_600), Instant.now().plusSeconds(3_600)).first { it.playerId == ana.id }

        assertEquals(100, mine.net)
        assertEquals(1, mine.rounds)
        assertEquals(2_000 + 100 + 5_000, store.balance(ana.id), "the chips are theirs all the same")
        assertEquals(2, store.rounds(ana.id, 10).size, "and the round is in their history")
    }

    @Test
    fun `settling a week moves leagues, pays prizes and keeps the result, once`() = with { store ->
        val ana = member(store, "Ana-${token()}")
        val rui = member(store, "Rui-${token()}")
        store.recordRound(ana.id, round(net = 500))
        // A week nobody else's test will settle, and long past, so a database
        // these tests share with a running server is not left thinking the
        // future has already been settled.
        val week = LocalDate.of(1000, 1, 6).plusWeeks((0..40_000L).random())
        val from = Instant.now().minusSeconds(3_600)
        val until = Instant.now().plusSeconds(3_600)

        var asked = 0
        val decide = { standings: List<Standing> ->
            asked++
            val mine = standings.first { it.playerId == ana.id }
            assertEquals(500, mine.net, "the week's standings are what is decided from")
            listOf(
                LeagueResult(ana.id, tier = 0, position = 1, net = 500, rounds = 1, outcome = LeagueOutcome.PROMOTED, prize = 1_000),
                LeagueResult(rui.id, tier = 0, position = 0, net = 0, rounds = 0, outcome = LeagueOutcome.STAYED, prize = 0),
            )
        }

        assertTrue(store.settleWeek(week, from, until, decide))
        assertFalse(store.settleWeek(week, from, until, decide), "a second settling does nothing")
        assertEquals(1, asked)

        assertEquals(3_500, store.balance(ana.id), "the opening chips, the round and one prize")
        assertEquals(LedgerReason.LEAGUE_PRIZE, store.ledger(ana.id, 10).last().reason)
        assertEquals(1, store.standings(from, until).first { it.playerId == ana.id }.tier, "promoted")
        assertEquals(0, store.standings(from, until).first { it.playerId == rui.id }.tier)

        val kept = assertNotNull(store.leagueResult(ana.id, week))
        assertEquals(LeagueOutcome.PROMOTED, kept.outcome)
        assertEquals(1, kept.position)
        assertEquals(1_000, kept.prize)
        assertNull(store.leagueResult(ana.id, week.plusWeeks(1)))

        val trophy = store.trophies(ana.id, 10).single()
        assertEquals(week, trophy.week)
        assertEquals(1, trophy.position)
        assertEquals(1_000, trophy.prize)
        assertTrue(store.trophies(rui.id, 10).isEmpty(), "a week that paid nothing leaves no trophy")

        assertEquals(1, assertNotNull(store.findById(ana.id)).leagueTier)
        assertNull(store.findById(UUID.randomUUID()))
        assertTrue(assertNotNull(store.lastSettledWeek()) >= week, "the latest settled week is no earlier than this one")
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

package com.banca.players

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Players kept in Postgres, in the tables the migrations define.
 *
 * Plain SQL over a small connection pool: the queries are few and worth being
 * able to read. JDBC blocks, so every call moves to the IO dispatcher and never
 * holds up a table.
 */
class PostgresPlayerStore(private val source: DataSource) : PlayerStore {

    private suspend fun <T> query(work: (Connection) -> T): T =
        withContext(Dispatchers.IO) { source.connection.use(work) }

    /** Runs [work] so that everything it writes happens, or none of it does. */
    private suspend fun <T> transaction(work: (Connection) -> T): T = query { connection ->
        connection.autoCommit = false
        try {
            work(connection).also { connection.commit() }
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    override suspend fun create(name: String, tokenHash: String, openingChips: Long): Player = transaction { connection ->
        val player = connection.prepareStatement("insert into profiles (display_name) values (?) returning $PLAYER").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { rows -> rows.next(); rows.toPlayer() }
        }
        connection.addToken(player.id, tokenHash)
        connection.addEntry(player.id, openingChips, LedgerReason.SIGNUP_GRANT, reference = null)
        player
    }

    override suspend fun findByTokenHash(tokenHash: String): Player? = query { connection ->
        connection.prepareStatement(
            "select $PLAYER from profiles where id = (select profile_id from player_tokens where token_hash = ?)",
        ).use { statement ->
            statement.setString(1, tokenHash)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toPlayer() else null }
        }
    }

    override suspend fun addToken(id: UUID, tokenHash: String): Unit = query { it.addToken(id, tokenHash) }

    override suspend fun removeToken(tokenHash: String): Unit = query { connection ->
        connection.prepareStatement("delete from player_tokens where token_hash = ?").use { statement ->
            statement.setString(1, tokenHash)
            statement.executeUpdate()
        }
    }

    override suspend fun findByAccount(accountId: String): Player? = query { connection ->
        connection.prepareStatement("select $PLAYER from profiles where auth_user_id = ?::uuid").use { statement ->
            statement.setString(1, accountId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toPlayer() else null }
        }
    }

    override suspend fun linkAccount(id: UUID, accountId: String): Player = query { connection ->
        // The unique constraint refuses an account that already has a profile.
        connection.prepareStatement(
            "update profiles set auth_user_id = ?::uuid where id = ? and auth_user_id is null returning $PLAYER",
        ).use { statement ->
            statement.setString(1, accountId)
            statement.setObject(2, id)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "This profile already belongs to an account" }
                rows.toPlayer()
            }
        }
    }

    override suspend fun rename(id: UUID, name: String): Player = query { connection ->
        connection.prepareStatement("update profiles set display_name = ? where id = ? returning $PLAYER").use { statement ->
            statement.setString(1, name)
            statement.setObject(2, id)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "No player $id" }
                rows.toPlayer()
            }
        }
    }

    override suspend fun balance(id: UUID): Long = query { it.balanceOf(id) }

    override suspend fun recordRound(id: UUID, round: FinishedRound): Long = transaction { connection ->
        val roundId = connection.prepareStatement(
            "insert into rounds (game, table_id, ended_at) values (?::game_kind, ?, now()) returning id",
        ).use { statement ->
            statement.setString(1, round.game.name.lowercase())
            statement.setString(2, round.tableId)
            statement.executeQuery().use { rows -> rows.next(); rows.getObject(1, UUID::class.java) }
        }

        // One player per round today, so they always sit in seat 0. When
        // several share a round, each gets a row of their own here.
        connection.prepareStatement(
            "insert into round_results (round_id, seat, profile_id, net_chips, staked, outcome, detail) " +
                "values (?, 0, ?, ?, ?, ?, ?::jsonb)",
        ).use { statement ->
            statement.setObject(1, roundId)
            statement.setObject(2, id)
            statement.setLong(3, round.net)
            statement.setLong(4, round.staked)
            statement.setString(5, round.outcome.name.lowercase())
            statement.setString(6, round.detail.toString())
            statement.executeUpdate()
        }

        connection.addEntry(id, round.net, LedgerReason.ROUND, reference = roundId.toString())
        connection.balanceOf(id)
    }

    override suspend fun grant(id: UUID, amount: Long, reason: LedgerReason): Long = transaction { connection ->
        connection.addEntry(id, amount, reason, reference = null)
        connection.balanceOf(id)
    }

    override suspend fun grantUnlessSince(id: UUID, amount: Long, reason: LedgerReason, since: Instant): Long? =
        transaction { connection ->
            // Holding the player's row makes two claims arriving together take turns.
            connection.prepareStatement("select 1 from profiles where id = ? for update").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows -> check(rows.next()) { "No player $id" } }
            }
            val already = connection.prepareStatement(
                "select 1 from wallet_entries where profile_id = ? and reason = ?::wallet_reason and created_at >= ? limit 1",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, reason.name.lowercase())
                statement.setTimestamp(3, Timestamp.from(since))
                statement.executeQuery().use { rows -> rows.next() }
            }
            if (already) return@transaction null

            connection.addEntry(id, amount, reason, reference = null)
            connection.balanceOf(id)
        }

    override suspend fun grantsOf(id: UUID, reason: LedgerReason, limit: Int): List<Instant> = query { connection ->
        connection.prepareStatement(
            "select created_at from wallet_entries where profile_id = ? and reason = ?::wallet_reason order by id desc limit ?",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, reason.name.lowercase())
            statement.setInt(3, limit)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getTimestamp(1).toInstant()) }
            }
        }
    }

    override suspend fun rounds(id: UUID, limit: Int): List<RoundRecord> = query { connection ->
        connection.prepareStatement(
            "select r.game, r.ended_at, rr.staked, rr.net_chips, rr.outcome, rr.detail " +
                "from round_results rr join rounds r on r.id = rr.round_id " +
                "where rr.profile_id = ? order by rr.id desc limit ?",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            RoundRecord(
                                game = Game.valueOf(rows.getString("game").uppercase()),
                                endedAt = rows.getTimestamp("ended_at").toInstant(),
                                staked = rows.getLong("staked"),
                                net = rows.getLong("net_chips"),
                                outcome = RoundOutcome.valueOf(rows.getString("outcome").uppercase()),
                                detail = Json.parseToJsonElement(rows.getString("detail") ?: "{}") as JsonObject,
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun ledger(id: UUID, limit: Int): List<LedgerEntry> = query { connection ->
        // The newest [limit] entries, handed back oldest first.
        connection.prepareStatement(
            "select amount, reason, created_at from (" +
                "select id, amount, reason, created_at from wallet_entries where profile_id = ? order by id desc limit ?" +
                ") recent order by id",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            LedgerEntry(
                                amount = rows.getLong("amount"),
                                reason = LedgerReason.valueOf(rows.getString("reason").uppercase()),
                                at = rows.getTimestamp("created_at").toInstant(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun Connection.addToken(id: UUID, tokenHash: String) {
        prepareStatement("insert into player_tokens (token_hash, profile_id) values (?, ?)").use { statement ->
            statement.setString(1, tokenHash)
            statement.setObject(2, id)
            statement.executeUpdate()
        }
    }

    private fun Connection.addEntry(id: UUID, amount: Long, reason: LedgerReason, reference: String?) {
        prepareStatement(
            "insert into wallet_entries (profile_id, amount, reason, ref_id) values (?, ?, ?::wallet_reason, ?)",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setLong(2, amount)
            statement.setString(3, reason.name.lowercase())
            statement.setString(4, reference)
            statement.executeUpdate()
        }
    }

    private fun Connection.balanceOf(id: UUID): Long =
        prepareStatement("select coalesce(sum(amount), 0) from wallet_entries where profile_id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }

    private fun ResultSet.toPlayer() = Player(
        id = getObject("id", UUID::class.java),
        name = getString("display_name"),
        createdAt = getTimestamp("created_at").toInstant(),
        accountId = getString("auth_user_id"),
    )

    companion object {
        /** The columns a player is read from. */
        private const val PLAYER = "id, display_name, created_at, auth_user_id"

        /**
         * Opens a pool from an address of the usual form,
         * postgresql://user:password@host:port/database. The pool is kept small:
         * the free database tier allows few connections and this needs fewer.
         */
        fun connect(url: String): PostgresPlayerStore {
            val address = URI(url.replaceFirst(Regex("^postgres(ql)?://"), "http://"))
            val (user, password) = address.rawUserInfo.split(":", limit = 2).map { java.net.URLDecoder.decode(it, "UTF-8") }

            val config = HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://${address.host}:${if (address.port > 0) address.port else 5432}${address.path}"
                username = user
                this.password = password
                maximumPoolSize = 4
                minimumIdle = 0
                connectionTimeout = 10_000
                poolName = "banca"
            }
            return PostgresPlayerStore(HikariDataSource(config))
        }
    }
}

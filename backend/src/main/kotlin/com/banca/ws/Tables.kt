package com.banca.ws

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The tables in play, kept by the server rather than by any one connection.
 *
 * A connection is a wire, and wires drop: a phone changes network, a page is
 * reloaded. The table a player was sitting at should still be there when they
 * come back, with the hand as they left it. So a table belongs to this
 * register, and a connection only attaches to it for as long as it lasts.
 *
 * A table nobody returns to is not kept for ever. After [keepFor] it is
 * abandoned: whatever was being played is finished on the player's behalf and
 * written down, and the table is cleared away. That also means leaving in the
 * middle of a round is never a way out of losing it.
 */
class Tables(private val scope: CoroutineScope, private val keepFor: Duration = 3.minutes) {

    private val log = LoggerFactory.getLogger(Tables::class.java)

    /** A player's place at a table, and whoever is connected to it at the moment. */
    inner class Seat internal constructor(private val key: String, private val table: GameConnection, private val outbox: Outbox) {
        /** One thing happens at a table at a time, whichever connection it came from. */
        private val turn = Mutex()

        private var holder: Job? = null
        private var clearing: Job? = null

        /**
         * Connects [connection] to the table, taking the place of any other
         * connection the same player has to it. The table then tells the new
         * connection where things stand.
         */
        internal suspend fun take(connection: Job, send: Send) {
            clearing?.cancel()
            holder?.takeIf { it !== connection && it.isActive }?.let { earlier ->
                // The table can only be played from one place. The newest wins.
                outbox.deliver(refusal("This table has been opened somewhere else", code = "replaced"))
                earlier.cancel()
            }
            holder = connection
            outbox.target = send
            turn.withLock { table.attached() }
        }

        /** Hands the table one message. */
        suspend fun received(text: String) = turn.withLock { table.received(text) }

        /** The connection has gone. The table waits a while for the player to come back. */
        fun leave(connection: Job) {
            if (holder !== connection) return
            holder = null
            outbox.target = null
            table.detached()

            clearing = scope.launch {
                delay(keepFor)
                try {
                    turn.withLock { table.abandoned() }
                } catch (failure: Exception) {
                    log.warn("A table could not be cleared away cleanly", failure)
                } finally {
                    seats.remove(key, this@Seat)
                }
            }
        }
    }

    private val seats = ConcurrentHashMap<String, Seat>()

    /**
     * Sits [connection] at the table known by [key], setting one up with
     * [create] if the player is not already at one.
     */
    suspend fun sit(key: String, connection: Job, send: Send, create: (Send) -> GameConnection): Seat {
        val seat = seats.computeIfAbsent(key) {
            val outbox = Outbox()
            Seat(key, create(outbox::deliver), outbox)
        }
        seat.take(connection, send)
        return seat
    }

    /** How many tables are being kept, connected or not. */
    val size: Int get() = seats.size
}

/**
 * Where a table sends what it has to say. It reaches the player when someone
 * is connected and goes nowhere when nobody is, so a table never needs to know
 * which.
 */
class Outbox {
    @Volatile
    var target: Send? = null

    suspend fun deliver(text: String) {
        // A connection that has just dropped is not the table's problem.
        runCatching { target?.invoke(text) }
    }
}

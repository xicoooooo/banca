package com.banca.ws

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The tables of one game that players have opened for themselves, to play at
 * with whoever they give the address to.
 *
 * They are the same tables as the ones every player can see, run in the same
 * way. What sets them apart is only that they are on no list: the address is
 * the invitation. One is opened on request, lives for as long as anyone is
 * using it, and is cleared away once it has stood empty for a while.
 */
class Invites<R : Any>(
    /** Makes a table of this game from what it is to be called. */
    private val make: (RoomSpec) -> R,
    /** Whether anyone is at a table at the moment. */
    private val occupied: suspend (R) -> Boolean,
    private val most: Int = 150,
    private val keepEmptyFor: Duration = 30.minutes,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Open<R>(val table: R, @Volatile var lastUsed: Long)

    private val open = ConcurrentHashMap<String, Open<R>>()
    private val random = SecureRandom()

    /** Opens a table called [name], or returns null when there are as many as the server will keep. */
    suspend fun open(name: String, options: TableOptions = TableOptions()): RoomSpec? {
        clearTheEmpty()
        if (open.size >= most) return null

        var id = newId()
        while (open.containsKey(id)) id = newId()
        val spec = RoomSpec(id, name, byInvite = true, options = options)
        open[id] = Open(make(spec), now())
        return spec
    }

    /** The table at [id], if it is still open. Looking for it counts as using it. */
    fun find(id: String): R? = open[id]?.also { it.lastUsed = now() }?.table

    val size: Int get() = open.size

    private suspend fun clearTheEmpty() {
        val at = now()
        for ((id, entry) in open) {
            if (at - entry.lastUsed < keepEmptyFor.inWholeMilliseconds) continue
            // Someone sitting at a table is using it, however long ago they sat down.
            if (occupied(entry.table)) entry.lastUsed = at else open.remove(id, entry)
        }
    }

    // Eight characters from an alphabet with nothing in it that is easily
    // misread, which is some eleven hundred billion addresses: enough that a
    // table cannot be found by guessing.
    private fun newId(): String = buildString { repeat(ID_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    private companion object {
        const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        const val ID_LENGTH = 8
    }
}

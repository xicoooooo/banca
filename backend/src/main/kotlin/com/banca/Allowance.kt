package com.banca

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** So many, within so long. */
class Limit(val max: Int, val within: Duration)

/**
 * How much of something each caller may have. A caller is whatever string
 * names them, an address or a player, and they may take one more only while
 * every limit still has room.
 *
 * It is kept in memory, which is all one process needs: a restart forgets it,
 * and nothing here is worth more than that.
 */
class Allowance(private vararg val limits: Limit, private val now: () -> Long = System::currentTimeMillis) {

    private val longest = limits.maxOf { it.within.inWholeMilliseconds }
    private val taken = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** Takes one for [caller] and returns true, or returns false and takes nothing because they have had their share. */
    fun take(caller: String): Boolean {
        val at = now()
        if (taken.size > FORGET_ABOVE) forgetTheIdle(at)

        var allowed = false
        taken.compute(caller) { _, before ->
            val times = before ?: ArrayDeque()
            while (times.isNotEmpty() && at - times.first() >= longest) times.removeFirst()
            allowed = limits.all { limit -> times.count { at - it < limit.within.inWholeMilliseconds } < limit.max }
            if (allowed) times.addLast(at)
            times
        }
        return allowed
    }

    /** Callers who have taken nothing for as long as the longest limit owe nothing and need not be remembered. */
    private fun forgetTheIdle(at: Long) {
        taken.entries.removeIf { (_, times) -> synchronized(times) { times.isEmpty() || at - times.last() >= longest } }
    }

    private companion object {
        const val FORGET_ABOVE = 10_000
    }
}

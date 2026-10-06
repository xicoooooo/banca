package com.banca.ws

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/**
 * One question at a time, put to something slow.
 *
 * A model takes seconds to answer and a table must not wait for it, so the
 * answer is worked out on its own coroutine and delivered when it is ready.
 * The player can carry on meanwhile; if what they asked about is overtaken,
 * the work is stopped and its answer never sent. An answer is remembered for
 * the question it was given to, so asking the same thing again costs nothing.
 */
class Consultation<A : Any> {
    private var working: Job? = null
    private var answered: Pair<String, A>? = null

    /** The answer already given to [question], if it is the one last answered. */
    fun answerTo(question: String): A? = answered?.takeIf { it.first == question }?.second

    /**
     * Sets [work] going on [question] unless something is already being worked
     * on. Its result goes to [deliver]; if it fails, [failed] is told, and the
     * connection it belongs to carries on either way.
     */
    suspend fun ask(
        question: String,
        work: suspend () -> A,
        deliver: suspend (A) -> Unit,
        failed: suspend (Exception) -> Unit,
    ) {
        answerTo(question)?.let {
            deliver(it)
            return
        }
        if (working?.isActive == true) return

        working = CoroutineScope(currentCoroutineContext()).launch {
            try {
                val answer = work()
                answered = question to answer
                deliver(answer)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed(failure)
            }
        }
    }

    /** Stops whatever is being worked on. What has been answered is kept. */
    fun cancel() {
        working?.cancel()
    }
}

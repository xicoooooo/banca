package com.banca.sessions

import com.banca.games.poker.Action
import kotlinx.serialization.Serializable

/**
 * One step a driven seat took while deciding.
 *
 * [label] is safe to show the table while the hand is live. [detail] is not: it
 * can hold the seat's cards or how strong it thinks it is, so it is only sent
 * once the hand is over.
 */
@Serializable
data class TraceEvent(
    val kind: String,
    val label: String,
    val detail: String? = null,
) {
    fun withoutDetail(): TraceEvent = copy(detail = null)

    companion object {
        const val TOOL = "tool"
        const val THOUGHT = "thought"
        const val DECISION = "decision"
        const val FALLBACK = "fallback"
    }
}

/** Whatever decides for a seat that no human is sitting in. */
fun interface SeatDriver {
    suspend fun decide(view: TableView, trace: suspend (TraceEvent) -> Unit): Action
}

/**
 * Checks when it can and calls when it cannot. Useful in tests and as a
 * stand-in when no model is available.
 */
class PassiveBot : SeatDriver {
    override suspend fun decide(view: TableView, trace: suspend (TraceEvent) -> Unit): Action {
        val legal = view.legal ?: error("Asked to act out of turn")
        return if (legal.canCheck) Action.Check else Action.Call
    }
}

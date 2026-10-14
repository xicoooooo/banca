package com.banca.ws

import kotlinx.serialization.Serializable
import java.util.UUID

/** One player's place in a tournament: how they stand, and where they finished once they have. */
@Serializable
data class TournamentSeat(
    val name: String,
    val chips: Long,
    /** Where they finished, counting from first. Null while they are still playing. */
    val place: Int?,
    val you: Boolean,
)

/** A tournament as a player at its table sees it. */
@Serializable
data class TournamentView(
    /** True from the host's start until one player has every chip. */
    val running: Boolean,
    /** The level of the blinds, from 1, and what they are. */
    val level: Int,
    val smallBlind: Long,
    val bigBlind: Long,
    /** Hands still to be played before the blinds go up. */
    val handsToNextLevel: Int,
    /** What everyone started with. */
    val stack: Long,
    /** Everyone who entered, those still playing first by their chips, then those out by where they finished. */
    val standings: List<TournamentSeat>,
)

/**
 * A tournament at a private poker table. Everyone the host starts it with is
 * given the same stack of tournament chips, which are the table's own and
 * touch nobody's bankroll. Nothing is topped up: a player who loses every
 * chip is out, and the blinds rise every few hands so that it ends. The last
 * player with chips wins, and everyone is placed by the order they went out.
 *
 * It keeps the score and nothing else. The table deals the hands as it always
 * does, asks here what each player brings to a hand and at what blinds, and
 * tells it afterwards what each came away with.
 */
class Tournament(val stack: Long = STACK, private val handsPerLevel: Int = HANDS_PER_LEVEL) {

    data class Level(val small: Long, val big: Long)

    private val chips = LinkedHashMap<UUID, Long>()
    private val names = HashMap<UUID, String>()
    /** Who has gone out, first out first. */
    private val out = mutableListOf<UUID>()

    var running = false
        private set
    var hands = 0
        private set

    /** Begins a tournament for [entrants], by id and name, each with a full stack. Whatever came before is forgotten. */
    fun begin(entrants: List<Pair<UUID, String>>) {
        require(entrants.size >= 2) { "A tournament needs two" }
        chips.clear()
        names.clear()
        out.clear()
        entrants.forEach { (id, name) ->
            chips[id] = stack
            names[id] = name
        }
        hands = 0
        running = true
    }

    /** Whether [id] is still playing. */
    fun isIn(id: UUID): Boolean = running && (chips[id] ?: 0) > 0

    fun chipsOf(id: UUID): Long = chips[id] ?: 0

    val level: Level get() = LEVELS[minOf(hands / handsPerLevel, LEVELS.lastIndex)]

    val handsToNextLevel: Int get() = if (hands / handsPerLevel >= LEVELS.lastIndex) 0 else handsPerLevel - hands % handsPerLevel

    /**
     * Writes down a hand: what each player who was dealt in came away with.
     * Anyone left with nothing is out, and of two who go out in the same hand
     * the one who began it with more finishes higher. When one player is left
     * the tournament is over.
     */
    fun handPlayed(after: Map<UUID, Long>) {
        check(running) { "No tournament is being played" }
        hands++
        val before = after.keys.associateWith { chips[it] ?: 0 }
        after.forEach { (id, left) -> chips[id] = left }
        val busted = after.filterValues { it <= 0 }.keys.sortedBy { before[it] ?: 0 }
        out += busted.filter { it !in out }
        if (chips.count { it.value > 0 } <= 1) running = false
    }

    /** [id] has left the tournament before it was over. They go out where they stand, with nothing. */
    fun forfeit(id: UUID) {
        if (!isIn(id)) return
        chips[id] = 0
        out += id
        if (chips.count { it.value > 0 } <= 1) running = false
    }

    /** Where [id] finished, or null while they are still playing. */
    fun placeOf(id: UUID): Int? {
        val outAt = out.indexOf(id)
        if (outAt >= 0) return chips.size - outAt
        return if (!running && (chips[id] ?: 0) > 0) 1 else null
    }

    /** True once a tournament has been played to its end. */
    val finished: Boolean get() = !running && chips.isNotEmpty()

    fun view(viewer: UUID?): TournamentView {
        val playing = chips.keys.filter { it !in out }.sortedByDescending { chips[it] ?: 0 }
        val gone = out.reversed()
        return TournamentView(
            running = running,
            level = minOf(hands / handsPerLevel, LEVELS.lastIndex) + 1,
            smallBlind = level.small,
            bigBlind = level.big,
            handsToNextLevel = handsToNextLevel,
            stack = stack,
            standings = (playing + gone).map { id ->
                TournamentSeat(names[id] ?: "Someone", chips[id] ?: 0, placeOf(id), you = id == viewer)
            },
        )
    }

    companion object {
        const val STACK = 1_500L
        const val HANDS_PER_LEVEL = 6

        /** How the blinds rise, a level at a time. The last holds until the end. */
        val LEVELS = listOf(
            Level(10, 20), Level(15, 30), Level(25, 50), Level(50, 100), Level(75, 150), Level(100, 200),
            Level(150, 300), Level(200, 400), Level(300, 600), Level(500, 1_000), Level(800, 1_600), Level(1_000, 2_000),
        )

        /** Banca's place in a tournament, which is not a player's id. */
        val BANCA: UUID = UUID(0, 0)
    }
}

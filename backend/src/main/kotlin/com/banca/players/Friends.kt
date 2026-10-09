package com.banca.players

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Where a player is sitting: at which game, and at which table of it. A table for one is [ALONE]. */
data class Place(val game: Game, val table: String) {
    companion object {
        const val ALONE = "solo"

        /** The tables anyone may walk into, which is what makes a friend's seat at one somewhere to join them. */
        val OPEN_TABLES = setOf("emerald", "gold", "ivory")

        /** Reads a place from the address a game is played at, or null if it is not one. */
        fun at(path: String): Place? = when {
            path == "/ws/table" -> Place(Game.POKER, ALONE)
            path == "/ws/blackjack" -> Place(Game.BLACKJACK, ALONE)
            path == "/ws/roulette" -> Place(Game.ROULETTE, ALONE)
            path.startsWith("/ws/poker/tables/") -> Place(Game.POKER, path.substringAfterLast('/'))
            path.startsWith("/ws/blackjack/tables/") -> Place(Game.BLACKJACK, path.substringAfterLast('/'))
            path.startsWith("/ws/roulette/rooms/") -> Place(Game.ROULETTE, path.substringAfterLast('/'))
            else -> null
        }
    }
}

/**
 * Who is here at the moment. It is known only from what the server can see for
 * itself, a connection to a table or a request just made, and it is kept in
 * memory and nowhere else: nothing about when anyone was online is stored.
 */
class Presence(private val clock: Clock = Clock.systemUTC()) {
    private val seen = ConcurrentHashMap<UUID, Instant>()
    private val seats = ConcurrentHashMap<UUID, List<Place>>()

    /** The player has just asked the server for something, so they are about. */
    fun seen(id: UUID) {
        seen[id] = clock.instant()
    }

    fun sat(id: UUID, place: Place) {
        seats.merge(id, listOf(place)) { before, added -> before + added }
    }

    fun left(id: UUID, place: Place) {
        seats.computeIfPresent(id) { _, before -> (before - place).takeIf { it.isNotEmpty() } }
        // Getting up from a table is being about for a little longer.
        seen(id)
    }

    /** Where the player is sitting, the latest place first if they are at more than one, or null if at none. */
    fun placeOf(id: UUID): Place? = seats[id]?.lastOrNull()

    fun isOnline(id: UUID): Boolean =
        seats.containsKey(id) || seen[id]?.let { Duration.between(it, clock.instant()) < AWAY_AFTER } == true

    private companion object {
        /** How long after a player was last heard from they are still taken to be about. */
        val AWAY_AFTER: Duration = Duration.ofSeconds(90)
    }
}

/** A friend as the player is shown them. [joinAt] is where to go to sit down with them, when that is somewhere anyone may. */
@Serializable
data class FriendView(
    val id: String,
    val name: String,
    val league: String,
    val online: Boolean,
    /** What they are doing, in a few words, when they are at a table. */
    val doing: String?,
    val joinAt: String?,
)

/** Someone who has asked to be the player's friend, or whom the player has asked. */
@Serializable
data class FriendRequestView(val id: String, val name: String)

@Serializable
data class FriendsView(
    /** The player's own code, to give to someone who wants to add them. */
    val code: String,
    val friends: List<FriendView>,
    val incoming: List<FriendRequestView>,
    val outgoing: List<FriendRequestView>,
)

/**
 * Friends: who a player has chosen to keep track of, and who has chosen them.
 *
 * It is for players who have signed in. A guest is a browser, with nothing to
 * be found by, and could not be anyone's friend from another device. A
 * friendship is asked for and accepted, never taken, and either of the two can
 * end it. What a friend is shown is what any player at a table would see of
 * someone, and whether they are here now.
 */
class Friends(private val store: PlayerStore, private val presence: Presence) {

    private val random = SecureRandom()

    private fun requireSignedIn(player: Player) {
        require(player.accountId != null) { NEEDS_SIGN_IN }
    }

    suspend fun view(player: Player): FriendsView {
        requireSignedIn(player)
        val links = store.friendLinks(player.id)
        fun asked(state: FriendState) = links.filter { it.state == state }.map { FriendRequestView(it.other.id.toString(), it.other.name) }

        return FriendsView(
            code = store.friendCode(player.id, ::newCode),
            friends = links.filter { it.state == FriendState.FRIENDS }
                .map { friendView(it.other) }
                // Whoever is here now comes first, then by name.
                .sortedWith(compareByDescending<FriendView> { it.online }.thenBy { it.name.lowercase() }),
            incoming = asked(FriendState.INCOMING),
            outgoing = asked(FriendState.OUTGOING),
        )
    }

    private fun friendView(friend: Player): FriendView {
        val place = presence.placeOf(friend.id)
        return FriendView(
            id = friend.id.toString(),
            name = friend.name,
            league = Leagues.TIERS[friend.leagueTier.coerceIn(0, Leagues.TIERS.lastIndex)],
            online = presence.isOnline(friend.id),
            doing = place?.let(::doing),
            // A table anyone may walk into is somewhere to join a friend. A private one is by its host's invitation, and a table for one has no second seat.
            joinAt = place?.takeIf { it.table in Place.OPEN_TABLES }?.let { "${it.game.name.lowercase()}/${it.table}" },
        )
    }

    private fun doing(place: Place): String {
        val game = when (place.game) {
            Game.POKER -> "poker"
            Game.BLACKJACK -> "blackjack"
            Game.ROULETTE -> "roulette"
        }
        return when (place.table) {
            Place.ALONE -> "Playing $game alone"
            in Place.OPEN_TABLES -> "Playing $game at the ${place.table.replaceFirstChar(Char::uppercase)} ${if (place.game == Game.ROULETTE) "Room" else "Table"}"
            else -> "Playing $game at a private table"
        }
    }

    /**
     * Asks someone to be a friend, found by the code they gave out or by their
     * id from a page of theirs. If they had already asked, this accepts it.
     */
    suspend fun ask(player: Player, code: String?, id: UUID?): FriendState {
        requireSignedIn(player)
        val other = when {
            code != null -> store.findByFriendCode(tidied(code))
            id != null -> store.findById(id)
            else -> null
        }
        // A guest is not to be found, by anyone. They are answered for as if they were not there.
        requireNotNull(other?.takeIf { it.accountId != null }) { "There is no player with that code" }
        require(other.id != player.id) { "That is your own code" }
        require(store.friendLinks(player.id).size < MOST) { "You have as many friends and requests as there is room for" }

        return store.befriend(player.id, other.id)
    }

    /** Accepts a request the player has been sent. Refused when there is none from that player. */
    suspend fun accept(player: Player, from: UUID): FriendState {
        requireSignedIn(player)
        val waiting = store.friendLinks(player.id).any { it.other.id == from && it.state == FriendState.INCOMING }
        require(waiting) { "There is no request from that player" }
        return store.befriend(player.id, from)
    }

    /** Ends a friendship, turns a request down, or takes one back. */
    suspend fun remove(player: Player, other: UUID): Boolean {
        requireSignedIn(player)
        return store.unfriend(player.id, other)
    }

    private fun newCode(): String = buildString { repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    companion object {
        const val NEEDS_SIGN_IN = "Sign in to have friends"

        /** Friends and requests together, which is plenty for a table game and keeps one player from asking everybody. */
        const val MOST = 100

        // The same plain alphabet as a table's code, two characters longer, since these last.
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        private const val CODE_LENGTH = 8

        /** A code as it is kept, however it was typed. */
        fun tidied(code: String): String = code.lowercase().filter { it.isLetterOrDigit() }
    }
}

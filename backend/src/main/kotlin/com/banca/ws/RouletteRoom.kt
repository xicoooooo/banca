package com.banca.ws

import com.banca.agents.BookAnalyst
import com.banca.agents.LayoutRead
import com.banca.agents.RouletteAdvisor
import com.banca.games.roulette.Roulette
import com.banca.games.roulette.Wager
import com.banca.games.roulette.Wheel
import com.banca.players.Funding
import com.banca.players.PlayerSession
import com.banca.sessions.TraceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long each part of a round lasts in a shared room. */
class RoomTimings(
    val betting: Duration = 20.seconds,
    val spinning: Duration = 5.seconds,
    val results: Duration = 5.seconds,
)

class RoomSpec(val id: String, val name: String)

class RoomsConfig(
    val rooms: List<RoomSpec> = listOf(
        RoomSpec("emerald", "Emerald Room"),
        RoomSpec("gold", "Gold Room"),
        RoomSpec("ivory", "Ivory Room"),
    ),
    val timings: RoomTimings = RoomTimings(),
    val random: () -> Random = { Random.Default },
    val analyst: RouletteAdvisor = BookAnalyst(),
)

/** Someone in the room, as the others see them. [net] is what the last spin did for them, once it is known. */
@Serializable
data class RoomPlayerView(val name: String, val staked: Long, val net: Long?, val you: Boolean)

/** The chips on one bet, everyone's together, and how many players put them there. */
@Serializable
data class CrowdSpot(val kind: String, val number: Int?, val other: Int?, val amount: Long, val players: Int)

/** Something said in the room. Every line is one of the room's set phrases; nobody types. */
@Serializable
data class ChatLine(val from: String, val text: String, val emote: Boolean)

@Serializable
data class RoomView(
    val room: String,
    val name: String,
    val roundNumber: Int,
    /** "betting", "spinning" or "results". */
    val phase: String,
    /** How long this phase has left, in milliseconds, by the server's clock. */
    val msLeft: Long,
    val stack: Long,
    val minBet: Long,
    val maxInside: Long,
    val maxOutside: Long,
    /** Where the room's ball has landed lately, newest first. The pocket being spun now is not in it yet. */
    val history: List<Int>,
    /** Where the ball is landing, from the moment bets close. */
    val pocket: Int?,
    /** The player's own bets for this round, as the room has them. */
    val bets: List<WagerMessage>,
    val crowd: List<CrowdSpot>,
    val players: List<RoomPlayerView>,
    /** What the spin came to for this player, if they had chips down. */
    val result: RouletteResultView?,
)

/** A room as the lobby lists it. */
@Serializable
data class RoomSummary(val id: String, val name: String, val players: Int, val history: List<Int>)

@Serializable
sealed interface RoomClientMessage {
    /** The player's whole layout for this round, replacing whatever they had down. An empty list takes it all back. */
    @Serializable
    @SerialName("bets")
    data class Bets(val bets: List<WagerMessage>) : RoomClientMessage

    /** Says one of the room's set phrases, named by its id. */
    @Serializable
    @SerialName("chat")
    data class Chat(val say: String) : RoomClientMessage

    @Serializable
    @SerialName("analyse")
    data class Analyse(val bets: List<WagerMessage>) : RoomClientMessage
}

@Serializable
sealed interface RoomServerMessage {
    @Serializable
    @SerialName("state")
    data class State(val view: RoomView) : RoomServerMessage

    /** What has been said lately, sent to someone as they walk in. */
    @Serializable
    @SerialName("chat_log")
    data class ChatLog(val lines: List<ChatLine>, val phrases: List<Phrase>) : RoomServerMessage

    @Serializable
    @SerialName("chat")
    data class Said(val line: ChatLine) : RoomServerMessage

    @Serializable
    @SerialName("trace")
    data class Trace(val event: TraceEvent) : RoomServerMessage

    @Serializable
    @SerialName("read")
    data class Read(val read: LayoutRead) : RoomServerMessage
}

/** Something that can be said in a room. */
@Serializable
data class Phrase(val id: String, val text: String, val emote: Boolean = false)

/**
 * What players may say to each other. A fixed list, chosen from rather than
 * typed, so that a room of strangers needs nobody to moderate it: there is
 * nothing unkind in here to say.
 */
object RoomPhrases {
    val ALL = listOf(
        Phrase("hello", "Hello all"),
        Phrase("good_luck", "Good luck"),
        Phrase("nice", "Nice hit!"),
        Phrase("close", "So close"),
        Phrase("ouch", "Ouch"),
        Phrase("well_played", "Well played"),
        Phrase("thanks", "Thanks"),
        Phrase("red", "Feeling red"),
        Phrase("black", "Feeling black"),
        Phrase("one_more", "One more spin"),
        Phrase("bye", "Good game, bye"),
        Phrase("clap", "👏", emote = true),
        Phrase("party", "🎉", emote = true),
        Phrase("clover", "🍀", emote = true),
        Phrase("fire", "🔥", emote = true),
        Phrase("sweat", "😅", emote = true),
        Phrase("cry", "😭", emote = true),
    )

    private val byId = ALL.associateBy { it.id }

    fun find(id: String): Phrase? = byId[id]
}

/**
 * A roulette table that several players share, and that runs whether or not
 * any one of them is there.
 *
 * It keeps time for everyone: bets are open for a while, then closed, the ball
 * lands, the result is shown, and the next round opens. Each player's chips
 * are still their own. The room holds their bets until the spin, settles each
 * player against the same pocket, and writes each result to that player's
 * record, exactly as a private table would.
 *
 * A room with nobody in it stops turning, and starts again when someone walks in.
 */
class RouletteRoom(
    private val spec: RoomSpec,
    private val scope: CoroutineScope,
    private val timings: RoomTimings,
    private val random: Random,
) {
    private val log = LoggerFactory.getLogger(RouletteRoom::class.java)

    private enum class Phase { BETTING, SPINNING, RESULTS }

    private class Member(val id: UUID, val name: String, val session: PlayerSession, var send: Send) {
        var connected = true
        var sent: List<WagerMessage> = emptyList()
        var wagers: List<Wager> = emptyList()
        var result: RouletteResultView? = null
        var net: Long? = null
        /** What the player is shown as having: their balance, less the chips they have down. */
        var stack = 0L
        var lastSpoke = 0L

        val staked: Long get() = wagers.sumOf { it.amount }
    }

    /** One thing happens in the room at a time: a bet, a word, or the turn of the clock. */
    private val lock = Mutex()
    private val members = LinkedHashMap<UUID, Member>()
    private val history = ArrayDeque<Int>()
    private val chat = ArrayDeque<ChatLine>()

    private var running = false
    private var phase = Phase.BETTING
    private var phaseEndsAt = 0L
    private var roundNumber = 0
    private var pocket: Int? = null

    private fun now(): Long = System.nanoTime() / 1_000_000

    val id: String get() = spec.id

    suspend fun summary(): RoomSummary = lock.withLock {
        RoomSummary(spec.id, spec.name, players = members.values.count { it.connected }, history = history.take(8))
    }

    /** Brings a player into the room, or back into it, and shows them where things stand. */
    suspend fun join(session: PlayerSession, send: Send) = lock.withLock {
        val member = members.getOrPut(session.player.id) { Member(session.player.id, session.player.name, session, send) }
        member.connected = true
        // Coming back after a long while, they may be reaching the room by a new connection.
        member.send = send
        if (member.wagers.isEmpty()) member.stack = session.balance()

        if (!running) {
            running = true
            scope.launch { run() }
        } else {
            broadcast()
        }
        member.send(encode(RoomServerMessage.ChatLog(chat.toList(), RoomPhrases.ALL)))
    }

    /** The player's connection has gone. Their chips stay down: a bet made is a bet made. */
    fun disconnected(playerId: UUID) {
        scope.launch {
            lock.withLock {
                members[playerId]?.connected = false
                broadcast()
            }
        }
    }

    /** The player is not coming back. They leave once any bets they had down are settled. */
    suspend fun leave(playerId: UUID) = lock.withLock {
        val member = members[playerId] ?: return@withLock
        member.connected = false
        if (member.wagers.isEmpty()) {
            members.remove(playerId)
            broadcast()
        }
    }

    /** Replaces a player's layout for the round. Refused once bets are closed. */
    suspend fun setBets(playerId: UUID, bets: List<WagerMessage>) = lock.withLock {
        val member = members[playerId] ?: error("You are not in this room")
        check(phase == Phase.BETTING) { "No more bets. Wait for the next round" }

        val funding = member.session.fund(RouletteHouse.MIN_BET)
        when (funding) {
            is Funding.Staked -> member.send(stakedNotice(funding))
            is Funding.Broke -> {
                member.send(brokeNotice(funding))
                return@withLock
            }
            is Funding.Ready -> Unit
        }

        member.wagers = RouletteHouse.wagersIn(bets, funding.balance)
        member.sent = bets
        member.stack = funding.balance - member.staked
        broadcast()
    }

    /** Says a set phrase to the room. Not too often, so one player cannot fill the screen. */
    suspend fun say(playerId: UUID, phraseId: String) = lock.withLock {
        val member = members[playerId] ?: error("You are not in this room")
        val phrase = RoomPhrases.find(phraseId) ?: throw IllegalArgumentException("That is not something that can be said here")
        check(now() - member.lastSpoke >= CHAT_EVERY_MS) { "Give it a moment before saying more" }
        member.lastSpoke = now()

        val line = ChatLine(from = member.name, text = phrase.text, emote = phrase.emote)
        chat.addLast(line)
        while (chat.size > CHAT_KEPT) chat.removeFirst()
        val message = encode(RoomServerMessage.Said(line))
        members.values.filter { it.connected }.forEach { it.send(message) }
    }

    /** The clock. Runs for as long as there is anyone to run it for. */
    private suspend fun run() {
        try {
            while (true) {
                lock.withLock { openBetting() }
                delay(timings.betting)

                val spun = lock.withLock {
                    if (members.values.none { it.connected } && members.values.all { it.wagers.isEmpty() }) {
                        // Nobody is here and nothing is riding. The wheel rests.
                        members.clear()
                        running = false
                        false
                    } else {
                        spin()
                        true
                    }
                }
                if (!spun) return
                delay(timings.spinning)

                lock.withLock { showResults() }
                delay(timings.results)
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            // A room that breaks stops rather than spinning wrongly; the next player in restarts it.
            log.error("Room ${spec.id} stopped", failure)
            lock.withLock { running = false }
        }
    }

    private suspend fun openBetting() {
        roundNumber++
        phase = Phase.BETTING
        phaseEndsAt = now() + timings.betting.inWholeMilliseconds
        pocket = null
        // Those who have gone and have nothing more riding are shown out.
        members.values.removeAll { !it.connected && it.wagers.isEmpty() }
        for (member in members.values) {
            member.sent = emptyList()
            member.result = null
            member.net = null
            member.stack = member.session.balance()
        }
        broadcast()
    }

    /** Closes the bets, lands the ball, and settles every player against the same pocket. */
    private suspend fun spin() {
        val landed = Wheel.spin(random)
        pocket = landed
        phase = Phase.SPINNING
        phaseEndsAt = now() + timings.spinning.inWholeMilliseconds

        for (member in members.values.filter { it.wagers.isNotEmpty() }) {
            try {
                val balance = member.session.balance()
                if (member.staked > balance) {
                    // Their chips went elsewhere while these bets were down.
                    member.send(refusal("Your bets came to more than your chips, so they were not played"))
                    member.stack = balance
                    member.sent = emptyList()
                } else {
                    val spin = Roulette.settle(member.wagers, landed)
                    member.session.settle(RouletteHouse.finished(spin, tableId = "room:${spec.id}"))
                    val after = member.session.fund(RouletteHouse.MIN_BET)
                    member.result = RouletteHouse.resultView(spin, member.sent, refilled = after is Funding.Staked)
                    member.net = spin.net
                    // Until the ball is seen to land, the player is shown what they had with their bets down.
                    member.stack = balance - spin.staked
                }
            } catch (failure: Exception) {
                log.warn("Could not settle a bet in room ${spec.id}", failure)
            }
            // The chips stay on the felt to be seen until the next round opens.
            member.wagers = emptyList()
        }
        broadcast()
    }

    private suspend fun showResults() {
        pocket?.let { landed ->
            history.addFirst(landed)
            while (history.size > RouletteHouse.HISTORY) history.removeLast()
        }
        phase = Phase.RESULTS
        phaseEndsAt = now() + timings.results.inWholeMilliseconds
        for (member in members.values) member.stack = member.session.balance()
        broadcast()
    }

    /** Tells everyone connected where the room stands, each from their own seat. */
    private suspend fun broadcast() {
        val crowd = members.values
            .flatMap { member -> member.sent.groupBy { it.toBet() }.map { (_, same) -> same.first() to same.sumOf { it.amount } } }
            .groupBy({ (wager, _) -> wager.toBet() }, { it })
            .map { (_, placed) ->
                val (wager, _) = placed.first()
                CrowdSpot(wager.kind, wager.number, wager.other, amount = placed.sumOf { it.second }, players = placed.size)
            }
        val msLeft = (phaseEndsAt - now()).coerceAtLeast(0)

        for (member in members.values.filter { it.connected }) {
            val view = RoomView(
                room = spec.id,
                name = spec.name,
                roundNumber = roundNumber,
                phase = phase.name.lowercase(),
                msLeft = msLeft,
                stack = member.stack,
                minBet = RouletteHouse.MIN_BET,
                maxInside = RouletteHouse.MAX_INSIDE,
                maxOutside = RouletteHouse.MAX_OUTSIDE,
                history = history.toList(),
                pocket = pocket,
                bets = member.sent,
                crowd = crowd,
                players = members.values.filter { it.connected || it.staked > 0 || it.net != null }.map { other ->
                    RoomPlayerView(
                        name = other.name,
                        staked = if (phase == Phase.BETTING) other.staked else other.result?.staked ?: 0,
                        // What a spin did for someone is not shown before the ball is seen to land.
                        net = other.net.takeIf { phase == Phase.RESULTS },
                        you = other.id == member.id,
                    )
                },
                result = member.result,
            )
            member.send(encode(RoomServerMessage.State(view)))
        }
    }

    private fun encode(message: RoomServerMessage): String = wireJson.encodeToString(RoomServerMessage.serializer(), message)

    private companion object {
        const val CHAT_EVERY_MS = 1_500L
        const val CHAT_KEPT = 30
    }
}

/**
 * One player's place in a shared room. The room is everybody's; this is the
 * part that is theirs: their connection to it, and Banca's read of their bets.
 */
class RoomSeat(
    private val room: RouletteRoom,
    private val analyst: RouletteAdvisor,
    private val send: Send,
    private val session: PlayerSession,
) : GameConnection {

    private val log = LoggerFactory.getLogger(RoomSeat::class.java)
    private val analysis = Consultation<LayoutRead>()

    override suspend fun attached() = room.join(session, send)

    override suspend fun received(text: String) {
        when (val message = wireJson.decodeFromString<RoomClientMessage>(text)) {
            is RoomClientMessage.Bets -> room.setBets(session.player.id, message.bets)
            is RoomClientMessage.Chat -> room.say(session.player.id, message.say)
            is RoomClientMessage.Analyse -> analyse(message.bets)
        }
    }

    private suspend fun analyse(bets: List<WagerMessage>) {
        require(bets.isNotEmpty()) { "Place a bet first" }
        val balance = session.balance()
        val wagers = RouletteHouse.wagersIn(bets, balance)

        analysis.ask(
            question = wagers.map { "${it.bet}=${it.amount}" }.sorted().joinToString(),
            work = { analyst.read(wagers, balance) { event -> emit(RoomServerMessage.Trace(event)) } },
            deliver = { read -> emit(RoomServerMessage.Read(read)) },
            failed = { failure ->
                log.warn("The analyst failed", failure)
                send(refusal("Banca could not be reached. Try again."))
            },
        )
    }

    override fun detached() {
        analysis.cancel()
        room.disconnected(session.player.id)
    }

    override suspend fun abandoned() = room.leave(session.player.id)

    private suspend fun emit(message: RoomServerMessage) = send(wireJson.encodeToString(RoomServerMessage.serializer(), message))
}

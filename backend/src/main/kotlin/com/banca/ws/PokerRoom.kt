package com.banca.ws

import com.banca.games.cards.Deck
import com.banca.games.poker.Action
import com.banca.games.poker.Hand
import com.banca.games.poker.Player
import com.banca.players.FinishedRound
import com.banca.players.Funding
import com.banca.players.Game
import com.banca.players.PlayerSession
import com.banca.players.RoundOutcome
import com.banca.sessions.PassiveBot
import com.banca.sessions.SeatDriver
import com.banca.sessions.TableView
import com.banca.agents.BookPokerAdvisor
import com.banca.agents.PokerAdvice
import com.banca.agents.PokerAdvisor
import com.banca.sessions.TraceEvent
import com.banca.sessions.handSummaryOf
import com.banca.sessions.tableViewOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How long a shared poker table waits for things. */
class PokerTimings(
    /** How long a player has for each decision before it is made for them: a check if it is free, a fold if not. */
    val turn: Duration = 25.seconds,
    /** The same, for a player whose connection has dropped. */
    val turnAway: Duration = 3.seconds,
    /** How long a finished hand is left on the table before the next is dealt. */
    val results: Duration = 8.seconds,
    /** A pause before Banca acts, so the table can follow the hand. */
    val agentDelay: Duration = 700.milliseconds,
)

class PokerTablesConfig(
    val tables: List<RoomSpec> = listOf(
        RoomSpec("emerald", "Emerald Table"),
        RoomSpec("gold", "Gold Table"),
        RoomSpec("ivory", "Ivory Table"),
    ),
    /** Seats in all, one of which is always Banca's. */
    val seats: Int = 6,
    val timings: PokerTimings = PokerTimings(),
    /** Who plays Banca's seat at each table. */
    val opponent: () -> SeatDriver = { PassiveBot() },
    val random: () -> Random = { Random.Default },
    /** Who answers a player who asks what to do. A second agent, apart from the one in Banca's seat. */
    val coach: PokerAdvisor = BookPokerAdvisor(),
)

/** Someone with a seat at the table, whether or not they are in the hand being played. */
@Serializable
data class PokerSeatView(val seat: Int, val name: String, val you: Boolean, val inHand: Boolean, val away: Boolean)

@Serializable
data class PokerRoomView(
    val room: String,
    val name: String,
    /** "waiting" for a hand to be dealt, "playing" one, or showing its "results". */
    val phase: String,
    /** How long the table will wait for the player whose turn it is, or before the next hand, in milliseconds. */
    val msLeft: Long,
    val yourTurn: Boolean,
    /** Whose turn it is, when it is someone's. */
    val actor: String?,
    /** True when this player has cards in the hand being played. Someone who sat down part way through waits for the next. */
    val dealtIn: Boolean,
    /** The hand as this player may see it, in the same shape as at a table alone. Null before the first hand. */
    val table: TableView?,
    val seats: List<PokerSeatView>,
    val seatsInAll: Int,
)

@Serializable
data class PokerTableSummary(val id: String, val name: String, val players: Int, val seats: Int)

@Serializable
sealed interface PokerRoomClientMessage {
    @Serializable
    @SerialName("act")
    data class Act(val action: String, val amount: Long? = null) : PokerRoomClientMessage

    @Serializable
    @SerialName("chat")
    data class Chat(val say: String? = null, val text: String? = null) : PokerRoomClientMessage

    /** Asks the coach what it would do with the decision in front of the player. */
    @Serializable
    @SerialName("advise")
    data object Advise : PokerRoomClientMessage
}

@Serializable
sealed interface PokerRoomServerMessage {
    @Serializable
    @SerialName("state")
    data class State(val view: PokerRoomView) : PokerRoomServerMessage

    /** A step Banca took while deciding, with nothing private in it. */
    @Serializable
    @SerialName("trace")
    data class Trace(val handNumber: Int, val event: TraceEvent) : PokerRoomServerMessage

    /** Banca's full reasoning for a hand, sent only once it is over. */
    @Serializable
    @SerialName("reveal")
    data class Reveal(val handNumber: Int, val events: List<TraceEvent>) : PokerRoomServerMessage

    @Serializable
    @SerialName("chat_log")
    data class ChatLog(val lines: List<ChatLine>, val phrases: List<Phrase>) : PokerRoomServerMessage

    @Serializable
    @SerialName("chat")
    data class Said(val line: ChatLine) : PokerRoomServerMessage

    /** One step the coach took for the player who asked it. Sent to them alone. */
    @Serializable
    @SerialName("coach_trace")
    data class CoachTrace(val handNumber: Int, val event: TraceEvent) : PokerRoomServerMessage

    /** The coach's advice, sent only to the player who asked. */
    @Serializable
    @SerialName("advice")
    data class Advised(val handNumber: Int, val advice: PokerAdvice) : PokerRoomServerMessage
}

/**
 * A poker table that several players share, with Banca always in one seat.
 *
 * It is a cash game, as the table for one is: each hand a player sits down
 * with their bankroll, up to the buy-in, and what the hand wins or loses goes
 * straight to their ledger. Hands follow one another without anyone asking.
 * A player who sits down part way through a hand is dealt into the next, and
 * every decision has a time limit, so one slow or absent player cannot hold
 * the table.
 *
 * Banca plays its seat as it does heads up: from its own view of the hand, by
 * calling tools, with its reasoning shown as steps while the hand is live and
 * in full once it is over.
 *
 * A table with no players at it stops, and starts again when someone sits down.
 */
class PokerRoom(
    private val spec: RoomSpec,
    private val config: PokerTablesConfig,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(PokerRoom::class.java)
    private val random = config.random()
    private val timings = config.timings
    private val banca = config.opponent()

    private enum class TablePhase { WAITING, PLAYING, RESULTS }

    private class Member(val id: UUID, val seat: Int, val name: String, val session: PlayerSession, var send: Send) {
        var connected = true
        /** Set when the player is not coming back. They keep their seat until any hand they are in is over. */
        var leaving = false
        /** What the player did in the hand being played, by name, for the record of how they play. */
        val actions = HashMap<String, Int>()
    }

    private sealed interface Turn {
        data class Agent(val view: TableView) : Turn
        data class Human(val waitFor: Duration) : Turn
    }

    /** One thing happens at the table at a time: an action, a word, or the turn of the clock. */
    private val lock = Mutex()
    private val members = LinkedHashMap<UUID, Member>()
    private val chat = RoomChat()
    private val reasoning = mutableListOf<TraceEvent>()

    /** Told whenever a player has done something the clock may have been waiting for. */
    private val acted = Channel<Unit>(Channel.CONFLATED)

    private var running = false
    private var phase = TablePhase.WAITING
    private var phaseEndsAt = 0L
    private var hand: Hand? = null
    private var handNumber = 0
    private var buttonSeat = BANCA_SEAT
    /** The names of the seats in the hand being played, kept so a player who leaves is still named until it ends. */
    private var names: Map<Int, String> = emptyMap()

    private fun now(): Long = System.nanoTime() / 1_000_000

    val id: String get() = spec.id

    suspend fun summary(): PokerTableSummary = lock.withLock {
        // Banca is always there, and takes one of the seats.
        PokerTableSummary(spec.id, spec.name, players = members.values.count { it.connected }, seats = config.seats - 1)
    }

    /** Seats a player, or brings them back to their seat, and shows them the table. */
    suspend fun join(session: PlayerSession, send: Send) = lock.withLock {
        val seated = members[session.player.id]
        val member = seated ?: run {
            val taken = members.values.map { it.seat }.toSet() + BANCA_SEAT
            val free = (0 until config.seats).firstOrNull { it !in taken } ?: error("This table is full")
            Member(session.player.id, free, session.player.name, session, send).also { members[it.id] = it }
        }
        member.connected = true
        member.leaving = false
        member.send = send

        member.send(encode(PokerRoomServerMessage.ChatLog(chat.recent(), RoomPhrases.ALL)))
        if (!running) {
            running = true
            scope.launch { run() }
        } else {
            broadcast()
        }
    }

    /** The player's connection has gone. Their hand stays in play, and is checked or folded for them when their turn comes. */
    fun disconnected(playerId: UUID) {
        scope.launch {
            lock.withLock {
                members[playerId]?.connected = false
                // If the table was waiting on them, it need not wait as long now.
                acted.trySend(Unit)
                broadcast()
            }
        }
    }

    /** The player is not coming back. They give up their seat once any hand they are in is over. */
    suspend fun leave(playerId: UUID) = lock.withLock {
        val member = members[playerId] ?: return@withLock
        member.connected = false
        member.leaving = true
        if (!inHand(member)) {
            members.remove(playerId)
            chat.forget(playerId)
            broadcast()
        }
    }

    /** Plays an action for a player, when it is their turn. */
    suspend fun act(playerId: UUID, action: Action, name: String) = lock.withLock {
        val member = members[playerId] ?: error("You are not at this table")
        val current = hand?.takeUnless { it.isComplete } ?: error("There is no hand being played")
        check(current.actorSeat == member.seat) { "It is not your turn" }

        hand = current.act(action)
        member.actions.merge(name, 1, Int::plus)
        acted.trySend(Unit)
        broadcast()
    }

    /** The hand as this player sees it while it is their turn to act, or null when it is not. */
    suspend fun decisionOf(playerId: UUID): TableView? = lock.withLock {
        val member = members[playerId] ?: return@withLock null
        val current = hand?.takeUnless { it.isComplete || phase != TablePhase.PLAYING } ?: return@withLock null
        if (current.actorSeat != member.seat || !inHand(member)) return@withLock null
        tableViewOf(current, handNumber, names, member.seat)
    }

    /** Says something to the table. */
    suspend fun say(playerId: UUID, phraseId: String?, typed: String?) = lock.withLock {
        val member = members[playerId] ?: error("You are not at this table")
        val line = chat.say(playerId, member.name, phraseId, typed)
        val message = encode(PokerRoomServerMessage.Said(line))
        members.values.filter { it.connected }.forEach { it.send(message) }
    }

    // ------------------------------------------------------------------ the clock

    private suspend fun run() {
        try {
            while (true) {
                val dealt = lock.withLock { deal() } ?: return
                if (!dealt) {
                    // Players are here but none can be dealt in. Look again shortly.
                    delay(1.seconds)
                    continue
                }

                while (true) {
                    when (val turn = lock.withLock { nextTurn() } ?: break) {
                        is Turn.Agent -> {
                            delay(timings.agentDelay)
                            val action = decideForBanca(turn.view)
                            lock.withLock { playForBanca(action) }
                        }
                        is Turn.Human -> {
                            val moved = withTimeoutOrNull(turn.waitFor) { acted.receive() }
                            if (moved == null) lock.withLock { playForAbsent() }
                        }
                    }
                }

                lock.withLock { finish() }
                delay(timings.results)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // A table that breaks stops rather than dealing wrongly; the next player to sit down restarts it.
            log.error("Poker table ${spec.id} stopped", failure)
            lock.withLock {
                running = false
                hand = null
                phase = TablePhase.WAITING
            }
        }
    }

    /**
     * Deals the next hand to Banca and every player who is here and can post
     * a blind. Returns false when nobody can be dealt in, and null when there
     * is nobody here at all, which stops the table.
     */
    private suspend fun deal(): Boolean? {
        members.values.removeAll { it.leaving || !it.connected }
        if (members.isEmpty()) {
            running = false
            hand = null
            phase = TablePhase.WAITING
            return null
        }

        val stacks = LinkedHashMap<Int, Long>()
        for (member in members.values) {
            // Someone who cannot post a blind is staked by the house first, if the house will.
            when (val funding = member.session.fund(BIG_BLIND)) {
                is Funding.Broke -> member.send(brokeNotice(funding))
                is Funding.Staked -> {
                    member.send(stakedNotice(funding))
                    stacks[member.seat] = minOf(funding.balance, BUY_IN)
                }
                is Funding.Ready -> stacks[member.seat] = minOf(funding.balance, BUY_IN)
            }
        }
        if (stacks.isEmpty()) {
            phase = TablePhase.WAITING
            broadcast()
            return false
        }
        stacks[BANCA_SEAT] = BUY_IN

        // The button moves to the next seat in the hand, clockwise.
        val seats = stacks.keys.sorted()
        buttonSeat = seats.firstOrNull { it > buttonSeat } ?: seats.first()
        names = members.values.associate { it.seat to it.name } + (BANCA_SEAT to "Banca")
        members.values.forEach { it.actions.clear() }
        reasoning.clear()

        handNumber++
        hand = Hand.start(
            seats = seats.map { Player(it, stacks.getValue(it)) },
            buttonSeat = buttonSeat,
            smallBlind = SMALL_BLIND,
            bigBlind = BIG_BLIND,
            deck = Deck.shuffled(random),
        )
        phase = TablePhase.PLAYING
        return true
    }

    /** Says whose turn it is and how long the table will wait for them, or null when the hand is over. */
    private suspend fun nextTurn(): Turn? {
        val current = hand ?: return null
        val seat = current.actorSeat ?: return null

        if (seat == BANCA_SEAT) {
            phaseEndsAt = now()
            broadcast()
            return Turn.Agent(tableViewOf(current, handNumber, names, BANCA_SEAT))
        }
        val member = members.values.firstOrNull { it.seat == seat }
        val waitFor = if (member?.connected == true) timings.turn else timings.turnAway
        phaseEndsAt = now() + waitFor.inWholeMilliseconds
        broadcast()
        return Turn.Human(waitFor)
    }

    /** Lets Banca decide. Never fails: whatever goes wrong, the hand is given a safe action to carry on with. */
    private suspend fun decideForBanca(view: TableView): Action = try {
        banca.decide(view) { event ->
            lock.withLock {
                reasoning += event
                // What Banca is thinking would give its hand away, so only the step itself is shown for now.
                val message = encode(PokerRoomServerMessage.Trace(handNumber, event.withoutDetail()))
                members.values.filter { it.connected }.forEach { it.send(message) }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        log.warn("Banca could not decide at table ${spec.id}", failure)
        if (view.legal?.canCheck == true) Action.Check else Action.Fold
    }

    private suspend fun playForBanca(action: Action) {
        val current = hand?.takeIf { it.actorSeat == BANCA_SEAT } ?: return
        hand = try {
            current.act(action)
        } catch (illegal: IllegalArgumentException) {
            // The engine has the last word on what a driver may do.
            current.act(if (current.legalActions().canCheck) Action.Check else Action.Fold)
        }
        broadcast()
    }

    /** The player whose turn it is has run out of time: they check if it costs nothing, and fold if it does. */
    private suspend fun playForAbsent() {
        val current = hand ?: return
        val seat = current.actorSeat?.takeIf { it != BANCA_SEAT } ?: return
        val free = current.legalActions().canCheck
        hand = current.act(if (free) Action.Check else Action.Fold)

        val member = members.values.firstOrNull { it.seat == seat }
        member?.actions?.merge(if (free) "check" else "fold", 1, Int::plus)
        if (member?.connected == true) {
            member.send(refusal(if (free) "You ran out of time, so you checked" else "You ran out of time, so your hand was folded"))
        }
    }

    /** Writes the hand to each player's record, shows how it ended, and tells everyone what Banca was thinking. */
    private suspend fun finish() {
        val finished = hand ?: return
        for (member in members.values) {
            val summary = runCatching { handSummaryOf(finished, member.seat) }.getOrNull() ?: continue
            try {
                member.session.settle(
                    FinishedRound(
                        game = Game.POKER,
                        tableId = "table:${spec.id}",
                        staked = summary.staked,
                        net = summary.net,
                        outcome = when {
                            summary.net > 0 -> RoundOutcome.WIN
                            summary.net < 0 -> RoundOutcome.LOSS
                            else -> RoundOutcome.PUSH
                        },
                        detail = buildJsonObject {
                            put("pot", summary.pot)
                            put("showdown", summary.wentToShowdown)
                            summary.hand?.let { put("hand", it) }
                            put("folded", summary.folded)
                            put("bets", member.actions["bet"] ?: 0)
                            put("raises", member.actions["raise"] ?: 0)
                            put("calls", member.actions["call"] ?: 0)
                            put("checks", member.actions["check"] ?: 0)
                            put("players", finished.players.size)
                        },
                    ),
                )
            } catch (failure: Exception) {
                log.warn("Could not settle a hand at table ${spec.id}", failure)
            }
        }

        phase = TablePhase.RESULTS
        phaseEndsAt = now() + timings.results.inWholeMilliseconds
        broadcast()

        // What Banca was thinking would have given its hand away, so it is told only now.
        if (reasoning.isNotEmpty()) {
            val reveal = encode(PokerRoomServerMessage.Reveal(handNumber, reasoning.toList()))
            members.values.filter { it.connected }.forEach { it.send(reveal) }
        }
    }

    // ------------------------------------------------------------------ the views

    private fun inHand(member: Member): Boolean =
        hand?.takeUnless { phase == TablePhase.WAITING }?.players?.any { it.seat == member.seat } == true &&
            names[member.seat] == member.name

    private suspend fun broadcast() {
        val current = hand
        val msLeft = (phaseEndsAt - now()).coerceAtLeast(0)
        val actorSeat = current?.actorSeat.takeIf { phase == TablePhase.PLAYING }
        val actorName = actorSeat?.let { names[it] }

        for (member in members.values.filter { it.connected }) {
            val dealtIn = inHand(member)
            val view = PokerRoomView(
                room = spec.id,
                name = spec.name,
                phase = phase.name.lowercase(),
                msLeft = msLeft,
                yourTurn = dealtIn && actorSeat == member.seat,
                actor = actorName,
                dealtIn = dealtIn,
                // Someone waiting for the next hand watches this one, with nobody's cards shown to them.
                table = current?.let { tableViewOf(it, handNumber, names, if (dealtIn) member.seat else null) },
                seats = listOf(PokerSeatView(BANCA_SEAT, "Banca", you = false, inHand = current != null, away = false)) +
                    members.values.sortedBy { it.seat }.map { other ->
                        PokerSeatView(other.seat, other.name, you = other.id == member.id, inHand = inHand(other), away = !other.connected)
                    },
                seatsInAll = config.seats,
            )
            member.send(encode(PokerRoomServerMessage.State(view)))
        }
    }

    private fun encode(message: PokerRoomServerMessage): String = wireJson.encodeToString(PokerRoomServerMessage.serializer(), message)

    private companion object {
        const val BANCA_SEAT = 0
        const val SMALL_BLIND = 10L
        const val BIG_BLIND = 20L

        /** The most any seat brings to a hand: a hundred big blinds. */
        const val BUY_IN = 2_000L
    }
}

/**
 * One player's seat at a shared poker table: their connection to it, and the
 * coach, which is theirs alone. What it tells them goes to nobody else at the
 * table, and it is given nothing but their own view of the hand.
 */
class PokerSeat(
    private val room: PokerRoom,
    private val coach: PokerAdvisor,
    private val send: Send,
    private val session: PlayerSession,
) : GameConnection {

    private val log = LoggerFactory.getLogger(PokerSeat::class.java)
    private val coaching = Consultation<PokerAdvice>()

    override suspend fun attached() {
        try {
            room.join(session, send)
        } catch (full: IllegalStateException) {
            send(refusal(full.message ?: "This table is full", code = "full"))
        }
    }

    override suspend fun received(text: String) {
        when (val message = wireJson.decodeFromString<PokerRoomClientMessage>(text)) {
            is PokerRoomClientMessage.Act -> {
                room.act(session.player.id, ClientMessage.Act(message.action, message.amount).toAction(), message.action)
                // Advice for a decision that has been made is of no use to anyone.
                coaching.cancel()
            }
            is PokerRoomClientMessage.Chat -> room.say(session.player.id, message.say, message.text)
            is PokerRoomClientMessage.Advise -> advise()
        }
    }

    private suspend fun advise() {
        val view = room.decisionOf(session.player.id) ?: error("There is nothing to advise on right now")
        val question = PokerHouse.decisionIn(view)

        coaching.ask(
            question = question,
            work = { coach.advise(view) { event -> emit(PokerRoomServerMessage.CoachTrace(view.handNumber, event)) } },
            deliver = { advice ->
                // The clock may have folded the hand while the coach was thinking.
                val stillAsked = room.decisionOf(session.player.id)?.let(PokerHouse::decisionIn) == question
                if (stillAsked) emit(PokerRoomServerMessage.Advised(view.handNumber, advice))
            },
            failed = { failure ->
                log.warn("The poker coach failed", failure)
                send(refusal("The coach could not be reached. Try again."))
            },
        )
    }

    override fun detached() {
        coaching.cancel()
        room.disconnected(session.player.id)
    }

    override suspend fun abandoned() = room.leave(session.player.id)

    private suspend fun emit(message: PokerRoomServerMessage) =
        send(wireJson.encodeToString(PokerRoomServerMessage.serializer(), message))
}

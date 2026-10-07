package com.banca.ws

import com.banca.agents.Advice
import com.banca.agents.BlackjackAdvisor
import com.banca.agents.BookAdvisor
import com.banca.games.blackjack.BlackjackAction
import com.banca.games.blackjack.Phase
import com.banca.games.blackjack.Round
import com.banca.games.blackjack.Rules
import com.banca.games.blackjack.Strategy
import com.banca.games.blackjack.valueOf
import com.banca.games.cards.Card
import com.banca.games.cards.Deck
import com.banca.players.Funding
import com.banca.players.PlayerSession
import com.banca.sessions.BlackjackHandView
import com.banca.sessions.BlackjackView
import com.banca.sessions.DealerView
import com.banca.sessions.TraceEvent
import com.banca.sessions.blackjackViewOf
import com.banca.sessions.handViewOf
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
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long each part of a round lasts at a shared blackjack table. */
class BlackjackTimings(
    val betting: Duration = 15.seconds,
    val insurance: Duration = 10.seconds,
    /** How long a player has for each decision before their hand is stood for them. */
    val turn: Duration = 20.seconds,
    /** The same, for a player whose connection has dropped: the table does not wait long for an empty chair. */
    val turnAway: Duration = 3.seconds,
    val results: Duration = 7.seconds,
)

class BlackjackTablesConfig(
    val tables: List<RoomSpec> = listOf(
        RoomSpec("emerald", "Emerald Table"),
        RoomSpec("gold", "Gold Table"),
        RoomSpec("ivory", "Ivory Table"),
    ),
    val seats: Int = 5,
    val timings: BlackjackTimings = BlackjackTimings(),
    val rules: Rules = Rules(),
    val random: () -> Random = { Random.Default },
    val advisor: BlackjackAdvisor = BookAdvisor(rules),
)

/** Someone at the table, as the others see them. */
@Serializable
data class TableSeatView(
    val name: String,
    val you: Boolean,
    /** What they have staked this round, across all their hands. */
    val bet: Long,
    val hands: List<BlackjackHandView>,
    /** True while the table is waiting on them. */
    val acting: Boolean,
    /** What the round did for them, once it is over. */
    val net: Long?,
)

@Serializable
data class BlackjackTableView(
    val room: String,
    val name: String,
    /** True for a table a player opened for their own company, which is on no list. */
    val byInvite: Boolean = false,
    val roundNumber: Int,
    /** "betting", "insurance", "playing" or "results". */
    val phase: String,
    /** How long the table will wait in this phase, or for the player whose turn it is, in milliseconds. */
    val msLeft: Long,
    val yourTurn: Boolean,
    /** Whose turn it is, when it is someone's. */
    val actor: String?,
    /** The round as this player sees their own part in it, in the same shape as at a table alone. */
    val you: BlackjackView,
    val seats: List<TableSeatView>,
    val seatsInAll: Int,
)

@Serializable
data class BlackjackTableSummary(val id: String, val name: String, val players: Int, val seats: Int)

@Serializable
sealed interface BlackjackTableClientMessage {
    /** The player's stake for the coming round. Nought takes it back. */
    @Serializable
    @SerialName("bet")
    data class Bet(val amount: Long) : BlackjackTableClientMessage

    @Serializable
    @SerialName("act")
    data class Act(val action: String) : BlackjackTableClientMessage

    @Serializable
    @SerialName("advise")
    data object Advise : BlackjackTableClientMessage

    @Serializable
    @SerialName("chat")
    data class Chat(val say: String? = null, val text: String? = null) : BlackjackTableClientMessage
}

@Serializable
sealed interface BlackjackTableServerMessage {
    @Serializable
    @SerialName("state")
    data class State(val view: BlackjackTableView) : BlackjackTableServerMessage

    @Serializable
    @SerialName("chat_log")
    data class ChatLog(val lines: List<ChatLine>, val phrases: List<Phrase>) : BlackjackTableServerMessage

    @Serializable
    @SerialName("chat")
    data class Said(val line: ChatLine) : BlackjackTableServerMessage

    @Serializable
    @SerialName("trace")
    data class Trace(val roundNumber: Int, val event: TraceEvent) : BlackjackTableServerMessage

    @Serializable
    @SerialName("advice")
    data class Advised(val roundNumber: Int, val hand: Int?, val advice: Advice) : BlackjackTableServerMessage
}

/**
 * A blackjack table that several players share: one dealer, one shoe, and a
 * hand each.
 *
 * The table keeps time. Bets are open for a while and then the cards are
 * dealt. The players act one at a time, in the order they sat down, each with
 * a limit on how long they may take; the dealer's hand is then played once and
 * every player is settled against it. Each player's chips, result and record
 * are still their own, exactly as at a table alone.
 *
 * A table with nobody at it stops, and starts again when someone sits down.
 */
class BlackjackRoom(
    private val spec: RoomSpec,
    private val config: BlackjackTablesConfig,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(BlackjackRoom::class.java)
    private val random = config.random()
    private val rules = config.rules
    private val timings = config.timings

    private enum class TablePhase { BETTING, INSURANCE, PLAYING, RESULTS }

    private class Member(val id: UUID, val name: String, val session: PlayerSession, var send: Send, strategy: Strategy) {
        var connected = true
        var bet = 0L
        var lastBet: Long? = null
        var round: Round? = null
        var net: Long? = null
        var refilled = false
        /** What the player is shown as having while no round of theirs is in play. */
        var stack = 0L
        val tally = DecisionTally(strategy)
    }

    /** One thing happens at the table at a time: a bet, an action, a word, or the turn of the clock. */
    private val lock = Mutex()
    private val members = LinkedHashMap<UUID, Member>()
    private val chat = RoomChat()
    private val strategy = Strategy(rules)

    /** Told whenever a player has done something the clock may have been waiting for. */
    private val acted = Channel<Unit>(Channel.CONFLATED)

    private var running = false
    private var phase = TablePhase.BETTING
    private var phaseEndsAt = 0L
    private var roundNumber = 0
    private var shoe: List<Card> = emptyList()
    private var dealer: List<Card> = emptyList()
    private var actor: UUID? = null

    private fun now(): Long = System.nanoTime() / 1_000_000

    val id: String get() = spec.id

    suspend fun summary(): BlackjackTableSummary = lock.withLock {
        BlackjackTableSummary(spec.id, spec.name, players = members.values.count { it.connected }, seats = config.seats)
    }

    /** Seats a player, or brings them back to their seat, and shows them the table. */
    suspend fun join(session: PlayerSession, send: Send) = lock.withLock {
        val seated = members[session.player.id]
        check(seated != null || members.size < config.seats) { "This table is full" }

        val member = seated ?: Member(session.player.id, session.player.name, session, send, strategy).also { members[it.id] = it }
        member.connected = true
        member.send = send
        if (member.round == null) member.stack = session.balance() - member.bet

        member.send(encode(BlackjackTableServerMessage.ChatLog(chat.recent(), RoomPhrases.ALL)))
        if (!running) {
            running = true
            scope.launch { run() }
        } else {
            broadcast()
        }
    }

    /** The player's connection has gone. Their hand stays in play, and is stood for them if their turn comes. */
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

    /** The player is not coming back. They give up their seat once any round of theirs is over. */
    suspend fun leave(playerId: UUID) = lock.withLock {
        val member = members[playerId] ?: return@withLock
        member.connected = false
        if (member.round == null && member.bet == 0L) {
            members.remove(playerId)
            chat.forget(playerId)
            broadcast()
        }
    }

    /** Sets a player's stake for the coming round. Refused once the cards are out. */
    suspend fun setBet(playerId: UUID, amount: Long) = lock.withLock {
        val member = members[playerId] ?: error("You are not at this table")
        check(phase == TablePhase.BETTING) { "The round has started. Bet on the next one" }

        if (amount == 0L) {
            member.bet = 0
            member.stack = member.session.balance()
            broadcast()
            return@withLock
        }

        val funding = member.session.fund(BlackjackHouse.MIN_BET)
        when (funding) {
            is Funding.Staked -> member.send(stakedNotice(funding))
            is Funding.Broke -> {
                member.send(brokeNotice(funding))
                return@withLock
            }
            is Funding.Ready -> Unit
        }
        require(amount in BlackjackHouse.MIN_BET..BlackjackHouse.MAX_BET) {
            "A bet must be between ${BlackjackHouse.MIN_BET} and ${BlackjackHouse.MAX_BET}"
        }
        require(amount <= funding.balance) { "A bet of $amount is more than the ${funding.balance} you have" }

        member.bet = amount
        member.stack = funding.balance - amount
        broadcast()
    }

    /**
     * Plays an action for a player: an answer about insurance, or a play on
     * their hand when it is their turn. [advice] is what the coach had told
     * them about this decision, if they asked.
     */
    suspend fun act(playerId: UUID, action: BlackjackAction, advice: Advice?) = lock.withLock {
        val member = members[playerId] ?: error("You are not at this table")
        val round = member.round ?: error("You have no hand in this round")

        when (phase) {
            TablePhase.INSURANCE -> check(round.phase == Phase.INSURANCE) { "You have already answered" }
            TablePhase.PLAYING -> check(actor == playerId) { "It is not your turn" }
            else -> error("There is nothing to play right now")
        }

        val before = viewFor(member)
        // The cards come from the table's shoe, which whoever acted last left as it is.
        val after = round.copy(shoe = shoe).act(action)
        shoe = after.shoe
        member.round = after
        member.tally.note(before, action, advice)

        acted.trySend(Unit)
        broadcast()
    }

    /** Says something to the table. */
    suspend fun say(playerId: UUID, phraseId: String?, typed: String?) = lock.withLock {
        val member = members[playerId] ?: error("You are not at this table")
        val line = chat.say(playerId, member.name, phraseId, typed)
        val message = encode(BlackjackTableServerMessage.Said(line))
        members.values.filter { it.connected }.forEach { it.send(message) }
    }

    /** The round as one player sees their own part in it, for the coach to look at. */
    suspend fun viewOf(playerId: UUID): BlackjackView = lock.withLock {
        viewFor(members[playerId] ?: error("You are not at this table"))
    }

    // ------------------------------------------------------------------ the clock

    private suspend fun run() {
        try {
            while (true) {
                lock.withLock { openBetting() }
                delay(timings.betting)

                val dealt = lock.withLock {
                    when {
                        members.values.none { it.connected } && members.values.all { it.bet == 0L } -> {
                            // Nobody is here and nothing is staked. The table rests.
                            members.clear()
                            running = false
                            null
                        }
                        members.values.none { it.bet > 0 } -> false
                        else -> {
                            deal()
                            true
                        }
                    }
                } ?: return
                // With nobody betting there is no round; the table waits through another window.
                if (!dealt) continue

                if (lock.withLock { phase == TablePhase.INSURANCE }) {
                    withTimeoutOrNull(timings.insurance) {
                        while (lock.withLock { members.values.any { it.round?.phase == Phase.INSURANCE } }) acted.receive()
                    }
                    lock.withLock { declineForTheRest() }
                }

                // Each player in turn, for as many decisions as their hands need.
                while (true) {
                    val waitFor = lock.withLock { nextTurn() } ?: break
                    val moved = withTimeoutOrNull(waitFor) { acted.receive() }
                    if (moved == null) lock.withLock { standForActor() }
                }

                lock.withLock { finish() }
                delay(timings.results)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // A table that breaks stops rather than dealing wrongly; the next player to sit down restarts it.
            log.error("Blackjack table ${spec.id} stopped", failure)
            lock.withLock {
                running = false
                members.values.forEach { it.round = null; it.bet = 0 }
            }
        }
    }

    private suspend fun openBetting() {
        phase = TablePhase.BETTING
        phaseEndsAt = now() + timings.betting.inWholeMilliseconds
        actor = null
        dealer = emptyList()
        // Those who have gone and have nothing staked give up their seats.
        members.values.removeAll { !it.connected && it.bet == 0L && it.round?.isSettled != false }
        for (member in members.values) {
            if (member.round != null) {
                member.round = null
                member.net = null
                member.refilled = false
                member.bet = 0
            }
            member.stack = member.session.balance() - member.bet
        }
        broadcast()
    }

    /** Deals to everyone who has bet: a card each, the dealer's, another each, and the dealer's hole card. */
    private suspend fun deal() {
        // A shoe is played most of the way down, then shuffled afresh.
        if (shoe.size < Round.CARDS_NEEDED + members.size * 12) shoe = (1..rules.decks).flatMap { Deck.full() }.shuffled(random)

        // Anyone whose chips went elsewhere since they bet sits this round out.
        val playing = members.values.filter { it.bet > 0 }.filter { member ->
            val affordable = member.bet <= member.session.balance()
            if (!affordable) {
                member.send(refusal("Your bet came to more than your chips, so you were not dealt in"))
                member.bet = 0
            }
            affordable
        }
        if (playing.isEmpty()) return

        roundNumber++
        val count = playing.size
        dealer = listOf(shoe[count], shoe[2 * count + 1])
        val rest = shoe.drop(2 * count + 2)
        playing.forEachIndexed { index, member ->
            member.tally.reset()
            member.lastBet = member.bet
            member.round = Round.seated(
                bet = member.bet,
                stack = member.session.balance(),
                cards = listOf(shoe[index], shoe[count + 1 + index]),
                dealer = dealer,
                shoe = rest,
                rules = rules,
            )
        }
        shoe = rest

        if (playing.any { it.round?.phase == Phase.INSURANCE }) {
            phase = TablePhase.INSURANCE
            phaseEndsAt = now() + timings.insurance.inWholeMilliseconds
        } else {
            phase = TablePhase.PLAYING
        }
        broadcast()
    }

    /** Anyone who has not answered about insurance by now has declined it. */
    private suspend fun declineForTheRest() {
        for (member in members.values) {
            val round = member.round?.takeIf { it.phase == Phase.INSURANCE } ?: continue
            member.round = round.act(BlackjackAction.DeclineInsurance)
        }
        phase = TablePhase.PLAYING
    }

    /**
     * Passes the turn to the next player with a decision to make and returns
     * how long the table will wait for them, or null when everyone has finished.
     */
    private suspend fun nextTurn(): Duration? {
        val next = members.values.firstOrNull { it.round?.phase == Phase.PLAYER }
        if (next == null) {
            actor = null
            return null
        }
        actor = next.id
        val waitFor = if (next.connected) timings.turn else timings.turnAway
        phaseEndsAt = now() + waitFor.inWholeMilliseconds
        broadcast()
        return waitFor
    }

    /** The player whose turn it is has run out of time: the hand they were on stands. */
    private suspend fun standForActor() {
        val member = members[actor] ?: return
        val round = member.round?.takeIf { it.phase == Phase.PLAYER } ?: return
        val after = round.copy(shoe = shoe).act(BlackjackAction.Stand)
        shoe = after.shoe
        member.round = after
        if (member.connected) member.send(refusal("You ran out of time, so your hand stood"))
    }

    /** Plays the dealer once for everyone, settles each player against that hand, and writes each round down. */
    private suspend fun finish() {
        actor = null
        if (members.values.any { it.round?.hasLiveHand == true }) {
            val (played, rest) = Round.drawDealer(dealer, shoe, rules)
            dealer = played
            shoe = rest
        }

        for (member in members.values) {
            val round = member.round ?: continue
            val settled = if (round.phase == Phase.WAITING) round.settledAgainst(dealer) else round
            member.round = settled
            if (!settled.isSettled) continue
            try {
                member.session.settle(BlackjackHouse.finished(settled, tableId = "table:${spec.id}", member.tally))
                member.net = settled.result?.net
                member.refilled = member.session.fund(BlackjackHouse.MIN_BET) is Funding.Staked
            } catch (failure: Exception) {
                log.warn("Could not settle a hand at table ${spec.id}", failure)
            }
            member.bet = 0
            member.stack = member.session.balance()
        }

        phase = TablePhase.RESULTS
        phaseEndsAt = now() + timings.results.inWholeMilliseconds
        broadcast()
    }

    // ------------------------------------------------------------------ the views

    /** The dealer's hand as the whole table may see it: the hole card stays down until the round is over. */
    private fun dealerView(): DealerView? {
        if (dealer.isEmpty()) return null
        // A dealer's natural ends the round on the deal, so by the time it is shown the round is over for everyone.
        val shown = if (phase == TablePhase.RESULTS) dealer else dealer.take(1)
        val value = valueOf(shown)
        return DealerView(
            cards = dealer.mapIndexed { index, card -> if (index < shown.size) card.toString() else null },
            total = value.total,
            soft = value.soft,
        )
    }

    /**
     * Whether the table is waiting on this player. The turn is theirs only
     * while they still have something to decide: the moment their last hand is
     * finished it is nobody's, until the clock passes it on.
     */
    private fun isActing(member: Member): Boolean =
        phase == TablePhase.PLAYING && actor == member.id && member.round?.phase == Phase.PLAYER

    private fun viewFor(member: Member): BlackjackView {
        val round = member.round
        val own = blackjackViewOf(
            round = round,
            roundNumber = roundNumber,
            stack = round?.stack?.takeIf { phase != TablePhase.RESULTS } ?: member.stack,
            minBet = BlackjackHouse.MIN_BET,
            maxBet = BlackjackHouse.MAX_BET,
            lastBet = member.lastBet,
            refilled = member.refilled,
        )
        val myTurn = isActing(member)

        return own.copy(
            // Someone watching a round they are not in is waiting, not betting.
            phase = when {
                round == null && phase != TablePhase.BETTING -> "waiting"
                else -> own.phase
            },
            // Every player sees the same dealer, however their own round stands.
            dealer = dealerView(),
            legal = own.legal.copy(
                bet = phase == TablePhase.BETTING,
                hit = own.legal.hit && myTurn,
                stand = own.legal.stand && myTurn,
                double = own.legal.double && myTurn,
                split = own.legal.split && myTurn,
                insurance = own.legal.insurance && phase == TablePhase.INSURANCE,
            ),
            // A result is the player's to see as soon as it is certain, which for a natural is on the deal.
            result = own.result,
            review = member.tally.review().takeIf { round?.isSettled == true },
        )
    }

    private suspend fun broadcast() {
        val msLeft = (phaseEndsAt - now()).coerceAtLeast(0)
        val actorName = members[actor]?.takeIf(::isActing)?.name

        for (member in members.values.filter { it.connected }) {
            val view = BlackjackTableView(
                room = spec.id,
                name = spec.name,
                byInvite = spec.byInvite,
                roundNumber = roundNumber,
                phase = phase.name.lowercase(),
                msLeft = msLeft,
                yourTurn = isActing(member),
                actor = actorName,
                you = viewFor(member),
                seats = members.values.map { other ->
                    val round = other.round
                    TableSeatView(
                        name = other.name,
                        you = other.id == member.id,
                        bet = round?.let { it.hands.sumOf { hand -> hand.bet } } ?: other.bet,
                        hands = round?.hands.orEmpty().mapIndexed { index, hand -> handViewOf(round!!, index, hand) },
                        acting = isActing(other),
                        net = other.net.takeIf { phase == TablePhase.RESULTS },
                    )
                },
                seatsInAll = config.seats,
            )
            member.send(encode(BlackjackTableServerMessage.State(view)))
        }
    }

    private fun encode(message: BlackjackTableServerMessage): String =
        wireJson.encodeToString(BlackjackTableServerMessage.serializer(), message)
}

/**
 * One player's seat at a shared blackjack table. The table is everybody's;
 * this is the part that is theirs: their connection to it, and the coach.
 */
class BlackjackSeat(
    private val room: BlackjackRoom,
    private val advisor: BlackjackAdvisor,
    private val send: Send,
    private val session: PlayerSession,
) : GameConnection {

    private val log = LoggerFactory.getLogger(BlackjackSeat::class.java)
    private val coaching = Consultation<Advice>()

    override suspend fun attached() {
        try {
            room.join(session, send)
        } catch (full: IllegalStateException) {
            send(refusal(full.message ?: "This table is full", code = "full"))
        }
    }

    override suspend fun received(text: String) {
        when (val message = wireJson.decodeFromString<BlackjackTableClientMessage>(text)) {
            is BlackjackTableClientMessage.Bet -> room.setBet(session.player.id, message.amount)
            is BlackjackTableClientMessage.Act -> {
                val action = BlackjackClientMessage.Act(message.action).toAction()
                val before = room.viewOf(session.player.id)
                room.act(session.player.id, action, advice = coaching.answerTo(BlackjackHouse.decisionIn(before)))
                // Advice for a decision that has been made is of no use to anyone.
                coaching.cancel()
            }
            is BlackjackTableClientMessage.Advise -> advise()
            is BlackjackTableClientMessage.Chat -> room.say(session.player.id, message.say, message.text)
        }
    }

    private suspend fun advise() {
        val view = room.viewOf(session.player.id)
        val deciding = view.legal.hit || view.legal.insurance
        check(deciding) { "There is nothing to advise on right now" }

        coaching.ask(
            question = BlackjackHouse.decisionIn(view),
            work = { advisor.advise(view) { event -> emit(BlackjackTableServerMessage.Trace(view.roundNumber, event)) } },
            deliver = { advice -> emit(BlackjackTableServerMessage.Advised(view.roundNumber, view.activeHand, advice)) },
            failed = { failure ->
                log.warn("The coach failed", failure)
                send(refusal("The coach could not be reached. Try again."))
            },
        )
    }

    override fun detached() {
        coaching.cancel()
        room.disconnected(session.player.id)
    }

    override suspend fun abandoned() = room.leave(session.player.id)

    private suspend fun emit(message: BlackjackTableServerMessage) =
        send(wireJson.encodeToString(BlackjackTableServerMessage.serializer(), message))
}

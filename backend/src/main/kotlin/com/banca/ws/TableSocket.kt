package com.banca.ws

import com.banca.agents.BookPokerAdvisor
import com.banca.agents.PokerAdvice
import com.banca.agents.PokerAdvisor
import com.banca.games.poker.Action
import com.banca.players.FinishedRound
import com.banca.players.Funding
import com.banca.players.Game
import com.banca.players.PlayerSession
import com.banca.players.RoundOutcome
import com.banca.sessions.PassiveBot
import com.banca.sessions.PokerTable
import com.banca.sessions.SeatDriver
import com.banca.sessions.TraceEvent
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val HUMAN_SEAT = 0
private const val OPPONENT_SEAT = 1

private const val SMALL_BLIND = 10L
private const val BIG_BLIND = 20L

/** The most either seat brings to a hand: a hundred big blinds. */
private const val BUY_IN = 2_000L

class TableSocketConfig(
    /** A pause before the opponent acts, so a person can follow the hand. */
    val opponentDelay: Duration = 700.milliseconds,
    val opponent: () -> SeatDriver = { PassiveBot() },
    val random: () -> Random = { Random.Default },
    /** Who answers when the player asks what to do. The rule of thumb, unless a model is given. */
    val coach: PokerAdvisor = BookPokerAdvisor(),
)

/**
 * A private heads-up poker table: the person who connected against a driven
 * seat.
 *
 * It is played as a cash game backed by the player's bankroll. Each hand they
 * sit down with what they have, up to the buy-in, and the house sits down with
 * a full one. What the hand wins or loses goes straight to their ledger, so the
 * table never holds chips of its own between hands.
 */
class PokerConnection(
    private val config: TableSocketConfig,
    private val send: Send,
    private val session: PlayerSession,
    /** Who coaches this player. The config's coach, unless they are given one of their own. */
    private val coach: PokerAdvisor = config.coach,
) : GameConnection {

    private val tableId = UUID.randomUUID().toString()
    private val table = PokerTable(
        names = mapOf(HUMAN_SEAT to "You", OPPONENT_SEAT to "Banca"),
        startingStack = BUY_IN,
        smallBlind = SMALL_BLIND,
        bigBlind = BIG_BLIND,
        random = config.random(),
    )
    private val opponent = config.opponent()
    private val reasoning = mutableListOf<TraceEvent>()

    /** What the player did this hand, by name, for the record of how they play. */
    private val actions = mutableMapOf<String, Int>()
    private var recordedHand = 0
    private var remarkedHand = 0

    private var seated = false

    private val log = LoggerFactory.getLogger(PokerConnection::class.java)

    /** The coach, asked about one decision at a time. It is no part of the opponent and is told nothing by it. */
    private val coaching = Consultation<PokerAdvice>()

    override suspend fun attached() {
        // The first time, a hand is dealt. Coming back, the hand is as it was left.
        if (!seated) {
            if (!deal()) return
            seated = true
        }
        pushState()
        // The opponent may have been part way through its turn when the player dropped.
        playOpponentTurns()
    }

    /**
     * The player left and did not come back. A hand still being played is
     * given up: they fold when it is their turn, and until then the opponent
     * only checks or calls, so nothing more is risked and no model is kept
     * thinking for an empty chair. The hand is then written down like any other.
     */
    override suspend fun abandoned() {
        var guard = 0
        while (!table.isHandComplete && guard++ < 50) {
            when (table.actorSeat) {
                HUMAN_SEAT -> table.act(HUMAN_SEAT, Action.Fold)
                else -> {
                    val legal = table.view(OPPONENT_SEAT).legal
                    table.act(OPPONENT_SEAT, if (legal?.canCheck == true) Action.Check else Action.Call)
                }
            }
        }
        record()
    }

    override suspend fun received(text: String) {
        when (val message = wireJson.decodeFromString<ClientMessage>(text)) {
            is ClientMessage.Advise -> {
                advise()
                return
            }
            is ClientMessage.Act -> {
                table.act(HUMAN_SEAT, message.toAction())
                // Advice for a decision that has been made is of no use to anyone.
                coaching.cancel()
                actions.merge(message.action, 1, Int::plus)
            }
            // With nothing to play with there is no new hand, and nothing new to show.
            is ClientMessage.NextHand -> {
                if (!deal()) return
                seated = true
            }
        }
        pushState()
        playOpponentTurns()
    }

    /**
     * Sets the coach to work on the decision in front of the player. It is
     * given their view of the table and nothing else, answers in its own time,
     * and is stopped if they act first.
     */
    private suspend fun advise() {
        check(!table.isHandComplete && table.actorSeat == HUMAN_SEAT) { "There is nothing to advise on right now" }
        val view = table.view(HUMAN_SEAT)
        val question = PokerHouse.decisionIn(view)

        coaching.ask(
            question = question,
            work = { coach.advise(view) { event -> emit(ServerMessage.CoachTrace(view.handNumber, event)) } },
            deliver = { advice ->
                val stillAsked = !table.isHandComplete && PokerHouse.decisionIn(table.view(HUMAN_SEAT)) == question
                if (stillAsked) emit(ServerMessage.Advised(view.handNumber, advice))
            },
            failed = { failure ->
                // A coach that breaks must not take the table with it.
                log.warn("The poker coach failed", failure)
                send(refusal("The coach could not be reached. Try again."))
            },
        )
    }

    override fun detached() = coaching.cancel()

    /** Deals the next hand, or says why not and returns false. */
    private suspend fun deal(): Boolean {
        check(table.isHandComplete) { "The current hand is still being played" }

        // Someone who cannot post a blind is staked by the house before the
        // cards come, if the house will; otherwise there is no hand.
        val funding = session.fund(BIG_BLIND)
        when (funding) {
            is Funding.Broke -> {
                send(brokeNotice(funding))
                return false
            }
            is Funding.Staked -> send(stakedNotice(funding))
            is Funding.Ready -> Unit
        }
        actions.clear()
        table.startHand(mapOf(HUMAN_SEAT to minOf(funding.balance, BUY_IN), OPPONENT_SEAT to BUY_IN))
        return true
    }

    private suspend fun pushState() {
        // The hand is written down before the player is told it is over, so
        // anything they look at next already includes it.
        record()
        emit(ServerMessage.State(table.view(HUMAN_SEAT)))

        // What the opponent was thinking would give its hand away mid-hand,
        // so the detail is held back until nothing rides on it.
        if (table.isHandComplete && reasoning.isNotEmpty()) {
            emit(ServerMessage.Reveal(table.handNumber, reasoning.toList()))
            reasoning.clear()
        }
        remark()
    }

    /** Passes on what the opponent has to say about a finished hand, if anything, and once only. */
    private suspend fun remark() {
        if (!table.isHandComplete || remarkedHand == table.handNumber) return
        remarkedHand = table.handNumber
        val net = table.summary(OPPONENT_SEAT)?.net ?: return
        val text = runCatching { opponent.remark(table.view(OPPONENT_SEAT), net) }.getOrNull() ?: return
        emit(ServerMessage.Remark(table.handNumber, text))
    }

    private suspend fun record() {
        val summary = table.summary(HUMAN_SEAT) ?: return
        if (recordedHand == table.handNumber) return
        recordedHand = table.handNumber

        session.settle(
            FinishedRound(
                game = Game.POKER,
                tableId = tableId,
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
                    put("bets", actions["bet"] ?: 0)
                    put("raises", actions["raise"] ?: 0)
                    put("calls", actions["call"] ?: 0)
                    put("checks", actions["check"] ?: 0)
                },
            ),
        )
    }

    private suspend fun playOpponentTurns() {
        while (table.actorSeat == OPPONENT_SEAT) {
            delay(config.opponentDelay)
            val action = opponent.decide(table.view(OPPONENT_SEAT)) { event ->
                reasoning += event
                emit(ServerMessage.Trace(table.handNumber, event.withoutDetail()))
            }
            try {
                table.act(OPPONENT_SEAT, action)
            } catch (illegal: IllegalArgumentException) {
                // The engine has the last word on what a driver may do.
                val legal = table.view(OPPONENT_SEAT).legal
                table.act(OPPONENT_SEAT, if (legal?.canCheck == true) Action.Check else Action.Fold)
            }
            pushState()
        }
    }

    private suspend fun emit(message: ServerMessage) =
        send(wireJson.encodeToString(ServerMessage.serializer(), message))
}

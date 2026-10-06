package com.banca.ws

import com.banca.agents.BookAnalyst
import com.banca.agents.LayoutRead
import com.banca.agents.RouletteAdvisor
import com.banca.games.roulette.Bet
import com.banca.games.roulette.Roulette
import com.banca.games.roulette.SpinResult
import com.banca.games.roulette.Wager
import com.banca.games.roulette.Wheel
import com.banca.players.FinishedRound
import com.banca.players.Funding
import com.banca.players.Game
import com.banca.players.PlayerSession
import com.banca.players.RoundOutcome
import com.banca.sessions.TraceEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.random.Random

/**
 * A bet as it is written on the wire. [number] is the number for a straight
 * bet, the lowest number for a street, corner or six line, and which one for a
 * dozen or column. A split names its second number in [other].
 */
@Serializable
data class WagerMessage(val kind: String, val number: Int? = null, val other: Int? = null, val amount: Long) {
    fun toBet(): Bet {
        fun number(): Int = number ?: throw IllegalArgumentException("A $kind bet needs a number")
        return when (kind) {
            "straight" -> Bet.Straight(number())
            "split" -> Bet.Split(number(), other ?: throw IllegalArgumentException("A split needs two numbers"))
            "street" -> Bet.Street(number())
            "corner" -> Bet.Corner(number())
            "six_line" -> Bet.SixLine(number())
            "dozen" -> Bet.Dozen(number())
            "column" -> Bet.Column(number())
            "red" -> Bet.Red
            "black" -> Bet.Black
            "even" -> Bet.Even
            "odd" -> Bet.Odd
            "low" -> Bet.Low
            "high" -> Bet.High
            else -> throw IllegalArgumentException("Unknown bet '$kind'")
        }
    }
}

@Serializable
sealed interface RouletteClientMessage {
    /** Roulette has one move: put the bets down and spin. */
    @Serializable
    @SerialName("spin")
    data class Spin(val bets: List<WagerMessage>) : RouletteClientMessage

    /** Asks Banca what it makes of a layout, before spinning it. */
    @Serializable
    @SerialName("analyse")
    data class Analyse(val bets: List<WagerMessage>) : RouletteClientMessage
}

@Serializable
data class WagerView(val kind: String, val number: Int?, val other: Int?, val amount: Long, val returned: Long)

@Serializable
data class RouletteResultView(
    val pocket: Int,
    /** "green", "red" or "black". */
    val color: String,
    val wagers: List<WagerView>,
    val staked: Long,
    /** What the spin did to the player's chips, all told. */
    val net: Long,
    /** True when the spin left the player out of chips and the house staked them. */
    val refilled: Boolean,
)

/** Everything the player may know about the roulette table, which is everything: nothing here is hidden. */
@Serializable
data class RouletteView(
    val roundNumber: Int,
    val stack: Long,
    /** The least that may go on any one bet. */
    val minBet: Long,
    /** The most that may go on one bet on the numbers, and on one of the boxes around them. */
    val maxInside: Long,
    val maxOutside: Long,
    /** Where the ball has landed lately, newest first. */
    val history: List<Int>,
    val result: RouletteResultView?,
)

@Serializable
sealed interface RouletteServerMessage {
    @Serializable
    @SerialName("state")
    data class State(val view: RouletteView) : RouletteServerMessage

    /** One step the analyst took. Nothing is hidden: it sees only the player's own bets. */
    @Serializable
    @SerialName("trace")
    data class Trace(val event: TraceEvent) : RouletteServerMessage

    @Serializable
    @SerialName("read")
    data class Read(val read: LayoutRead) : RouletteServerMessage
}

class RouletteSocketConfig(
    val random: () -> Random = { Random.Default },
    /** Who answers when the player asks about their layout. The figures alone, unless a model is given. */
    val analyst: RouletteAdvisor = BookAnalyst(),
)

/**
 * A private roulette table: the person who connected against the wheel,
 * betting from their bankroll. There is nothing to play out, so a round is one
 * message in and one answer back, written to the ledger in between.
 */
class RouletteConnection(
    private val config: RouletteSocketConfig,
    private val send: Send,
    private val session: PlayerSession,
) : GameConnection {

    private val tableId = UUID.randomUUID().toString()
    private val random = config.random()
    private val history = ArrayDeque<Int>()
    private var roundNumber = 0
    private var result: RouletteResultView? = null

    private val log = LoggerFactory.getLogger(RouletteConnection::class.java)

    /** The analyst, asked about one layout at a time. */
    private val analysis = Consultation<LayoutRead>()

    // A spin is over as soon as it is asked for, so there is never a round to
    // come back to: only where the ball has been, and what the player has now.
    override suspend fun attached() {
        val funding = session.fund(MIN_BET)
        pushState(funding.balance)
        tell(funding)
    }

    override suspend fun received(text: String) {
        val message = when (val decoded = wireJson.decodeFromString<RouletteClientMessage>(text)) {
            is RouletteClientMessage.Analyse -> return analyse(decoded.bets)
            is RouletteClientMessage.Spin -> decoded
        }
        // A read of a layout that has been spun is of no use to anyone.
        analysis.cancel()

        val funding = session.fund(MIN_BET)
        if (funding !is Funding.Ready) {
            pushState(funding.balance)
            tell(funding)
            if (funding is Funding.Broke) return
        }

        val wagers = wagersIn(message.bets, funding.balance)
        val spin = Roulette.settle(wagers, Wheel.spin(random))

        roundNumber++
        history.addFirst(spin.pocket)
        while (history.size > HISTORY) history.removeLast()

        session.settle(
            FinishedRound(
                game = Game.ROULETTE,
                tableId = tableId,
                staked = spin.staked,
                net = spin.net,
                outcome = when {
                    spin.net > 0 -> RoundOutcome.WIN
                    spin.net < 0 -> RoundOutcome.LOSS
                    else -> RoundOutcome.PUSH
                },
                detail = buildJsonObject {
                    put("pocket", spin.pocket)
                    put("color", spin.color.name.lowercase())
                    put("bets", spin.wagers.size)
                    put("betsWon", spin.wagers.count { it.won })
                    put("insideStake", spin.wagers.filter { it.wager.bet.isInside }.sumOf { it.wager.amount })
                    put("outsideStake", spin.wagers.filterNot { it.wager.bet.isInside }.sumOf { it.wager.amount })
                    put("straightBets", spin.wagers.count { it.wager.bet is Bet.Straight })
                    put("straightHit", spin.wagers.any { it.wager.bet is Bet.Straight && it.won })
                },
            ),
        )

        // Chips cannot be bought, so a player left unable to bet is staked by
        // the house, if it has not done so too recently.
        val after = session.fund(MIN_BET)
        result = view(spin, message.bets, refilled = after is Funding.Staked)
        pushState(after.balance)
        if (after is Funding.Broke) tell(after)
    }

    /**
     * Sets the analyst to work on a layout. It answers in its own time, so the
     * player is never kept from spinning while it thinks.
     */
    private suspend fun analyse(bets: List<WagerMessage>) {
        val balance = session.balance()
        val wagers = wagersIn(bets, balance)
        // The same bets in another order are the same question.
        val question = wagers.map { "${it.bet}=${it.amount}" }.sorted().joinToString()

        analysis.ask(
            question = question,
            work = { config.analyst.read(wagers, balance) { event -> emit(RouletteServerMessage.Trace(event)) } },
            deliver = { read -> emit(RouletteServerMessage.Read(read)) },
            failed = { failure ->
                log.warn("The analyst failed", failure)
                send(refusal("Banca could not be reached. Try again."))
            },
        )
    }

    override fun detached() = analysis.cancel()

    private suspend fun emit(message: RouletteServerMessage) =
        send(wireJson.encodeToString(RouletteServerMessage.serializer(), message))

    /**
     * Reads the layout the player sent into wagers, refusing it whole if any
     * part of it is not allowed. Chips on the same bet are counted together.
     */
    private fun wagersIn(bets: List<WagerMessage>, balance: Long): List<Wager> {
        require(bets.isNotEmpty()) { "Place a bet before spinning" }
        require(bets.size <= MOST_BETS) { "That is more bets than the table takes" }

        val wagers = bets
            .groupBy({ it.toBet() }, { it.amount })
            .map { (bet, amounts) ->
                require(amounts.all { it > 0 }) { "A bet must be for at least one chip" }
                Wager(bet, amounts.sum())
            }

        for (wager in wagers) {
            val most = if (wager.bet.isInside) MAX_INSIDE else MAX_OUTSIDE
            require(wager.amount >= MIN_BET) { "Each bet must be at least $MIN_BET" }
            require(wager.amount <= most) { "The most on that bet is $most" }
        }
        val total = wagers.sumOf { it.amount }
        require(total <= balance) { "Those bets come to $total and you have $balance" }
        return wagers
    }

    private fun view(spin: SpinResult, sent: List<WagerMessage>, refilled: Boolean): RouletteResultView {
        // Each bet goes back in the words it came in, with what it returned.
        val described = sent.associateBy { it.toBet() }
        return RouletteResultView(
            pocket = spin.pocket,
            color = spin.color.name.lowercase(),
            wagers = spin.wagers.map { result ->
                val wager = described.getValue(result.wager.bet)
                WagerView(wager.kind, wager.number, wager.other, result.wager.amount, result.returned)
            },
            staked = spin.staked,
            net = spin.net,
            refilled = refilled,
        )
    }

    private suspend fun tell(funding: Funding) {
        when (funding) {
            is Funding.Staked -> send(stakedNotice(funding))
            is Funding.Broke -> send(brokeNotice(funding))
            is Funding.Ready -> Unit
        }
    }

    private suspend fun pushState(stack: Long) = send(
        wireJson.encodeToString(
            RouletteServerMessage.serializer(),
            RouletteServerMessage.State(
                RouletteView(
                    roundNumber = roundNumber,
                    stack = stack,
                    minBet = MIN_BET,
                    maxInside = MAX_INSIDE,
                    maxOutside = MAX_OUTSIDE,
                    history = history.toList(),
                    result = result,
                ),
            ),
        ),
    )

    private companion object {
        const val MIN_BET = 10L

        /** A single number pays 35 to 1, so what may go on one is kept small. */
        const val MAX_INSIDE = 100L
        const val MAX_OUTSIDE = 500L

        const val MOST_BETS = 60
        const val HISTORY = 12
    }
}

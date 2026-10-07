package com.banca.players

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * Turns a player's rounds and ledger into the dashboard.
 *
 * A pure function of the record: nothing here is stored, so no figure can
 * drift from the rounds it came from, and the same numbers come out whichever
 * store the record was read from. A figure that needs more play than there has
 * been is left out rather than guessed.
 */
object DashboardBuilder {

    /** Fewest rounds of a game before saying anything about how the player plays it. */
    private const val ENOUGH_ROUNDS = 10

    /** Fewest decisions of a kind before a share of them means much. */
    private const val ENOUGH_CASES = 5

    private const val HISTORY_POINTS = 60
    private const val ACTIVITY_DAYS = 14
    private const val RECENT_ROUNDS = 20

    /**
     * @param rounds newest first
     * @param ledger oldest first, ending at the present
     */
    fun build(
        player: Player,
        balance: Long,
        rounds: List<RoundRecord>,
        ledger: List<LedgerEntry>,
        now: Instant,
        rewards: RewardStatus = Rewards.status(emptyList(), null, now),
        trophies: List<Trophy> = emptyList(),
    ): Dashboard {
        val xp = rounds.sumOf(::experienceFor)
        val level = Levels.levelAt(xp)

        return Dashboard(
            player = PlayerCard(
                signedIn = player.accountId != null,
                name = player.name,
                memberSince = player.createdAt.toString(),
                level = level,
                title = Levels.titleOf(level),
                xp = xp,
                levelStart = Levels.startOf(level),
                nextLevelAt = Levels.startOf(level + 1),
            ),
            bankroll = bankroll(balance, rounds, ledger),
            totals = totals(rounds),
            streaks = streaks(rounds),
            games = Game.entries.map { game -> breakdown(game, rounds.filter { it.game == game }) },
            activity = activity(rounds, now),
            achievements = Achievements.all(rounds, balance, ledger),
            recent = rounds.take(RECENT_ROUNDS).map { round ->
                RecentRound(
                    game = round.game.name.lowercase(),
                    at = round.endedAt.toString(),
                    net = round.net,
                    outcome = round.outcome.name.lowercase(),
                    summary = Summaries.of(round),
                )
            },
            rewards = rewards,
            league = if (player.accountId != null) Leagues.TIERS[player.leagueTier.coerceIn(0, Leagues.TIERS.lastIndex)] else null,
            trophies = trophies.map(::trophyView),
        )
    }

    private val PLACES = listOf("Champion", "Runner-up", "Third place")

    fun trophyView(trophy: Trophy): TrophyView {
        val league = Leagues.TIERS[trophy.tier.coerceIn(0, Leagues.TIERS.lastIndex)]
        return TrophyView(
            league = league,
            position = trophy.position,
            title = "$league ${PLACES.getOrElse(trophy.position - 1) { "Finalist" }}",
            week = trophy.week.toString(),
            prize = trophy.prize,
        )
    }

    /** Ten for turning up, more for winning, and a little extra for the moments worth remembering. */
    fun experienceFor(round: RoundRecord): Long {
        var earned = 10L
        if (round.outcome == RoundOutcome.WIN) earned += 15
        if (round.detail.flag("natural")) earned += 10
        if (round.outcome == RoundOutcome.WIN && round.detail.flag("showdown")) earned += 5
        return earned
    }

    private fun bankroll(balance: Long, rounds: List<RoundRecord>, ledger: List<LedgerEntry>): Bankroll {
        // The ledger read may not reach back to the beginning, so the running
        // total starts from whatever came before it.
        var running = balance - ledger.sumOf { it.amount }
        val points = ledger.map { entry ->
            running += entry.amount
            BankrollPoint(entry.at.toString(), running)
        }

        return Bankroll(
            balance = balance,
            net = rounds.sumOf { it.net },
            granted = ledger.filter { it.reason != LedgerReason.ROUND }.sumOf { it.amount },
            peak = points.maxOfOrNull { it.balance } ?: balance,
            history = thin(points, HISTORY_POINTS),
        )
    }

    /** Keeps the first and last points and an even spread between, so a long history still draws as a line. */
    private fun <T> thin(points: List<T>, most: Int): List<T> {
        if (points.size <= most) return points
        val step = (points.size - 1).toDouble() / (most - 1)
        return (0 until most).map { points[(it * step).roundToInt()] }
    }

    private fun totals(rounds: List<RoundRecord>): Totals {
        val wins = rounds.count { it.outcome == RoundOutcome.WIN }
        val losses = rounds.count { it.outcome == RoundOutcome.LOSS }

        return Totals(
            rounds = rounds.size,
            wins = wins,
            losses = losses,
            pushes = rounds.size - wins - losses,
            winRate = share(wins, rounds.size),
            biggestWin = rounds.maxOfOrNull { it.net }?.takeIf { it > 0 },
            biggestLoss = rounds.minOfOrNull { it.net }?.takeIf { it < 0 },
            averageResult = if (rounds.isEmpty()) null else rounds.sumOf { it.net }.toDouble() / rounds.size,
            staked = rounds.sumOf { it.staked },
            biggestPot = rounds
                .filter { it.game == Game.POKER && it.outcome == RoundOutcome.WIN }
                .mapNotNull { it.detail.number("pot") }
                .maxOrNull(),
        )
    }

    private fun streaks(rounds: List<RoundRecord>): Streaks {
        // Pushes are stepped over: a tie does not end a run of wins.
        val decided = rounds.filter { it.outcome != RoundOutcome.PUSH }
        val latest = decided.firstOrNull()?.outcome

        var bestWin = 0
        var worstLoss = 0
        var run = 0
        var kind: RoundOutcome? = null
        for (round in decided) {
            if (round.outcome == kind) run++ else { kind = round.outcome; run = 1 }
            if (kind == RoundOutcome.WIN) bestWin = maxOf(bestWin, run) else worstLoss = maxOf(worstLoss, run)
        }

        return Streaks(
            currentKind = latest?.name?.lowercase(),
            current = decided.takeWhile { it.outcome == latest }.size,
            bestWin = bestWin,
            worstLoss = worstLoss,
        )
    }

    private fun breakdown(game: Game, rounds: List<RoundRecord>): GameBreakdown {
        val wins = rounds.count { it.outcome == RoundOutcome.WIN }
        val losses = rounds.count { it.outcome == RoundOutcome.LOSS }

        return GameBreakdown(
            game = game.name.lowercase(),
            rounds = rounds.size,
            wins = wins,
            losses = losses,
            pushes = rounds.size - wins - losses,
            winRate = share(wins, rounds.size),
            net = rounds.sumOf { it.net },
            biggestWin = rounds.maxOfOrNull { it.net }?.takeIf { it > 0 },
            tendencies = if (rounds.size < ENOUGH_ROUNDS) emptyList() else when (game) {
                Game.POKER -> pokerTendencies(rounds)
                Game.BLACKJACK -> blackjackTendencies(rounds)
                Game.ROULETTE -> rouletteTendencies(rounds)
            },
        )
    }

    private fun pokerTendencies(rounds: List<RoundRecord>): List<Tendency> = buildList {
        val showdowns = rounds.filter { it.detail.flag("showdown") }
        add(tendency("Sees a showdown", showdowns.size, rounds.size, "hands"))
        if (showdowns.size >= ENOUGH_CASES) {
            add(tendency("Wins at showdown", showdowns.count { it.outcome == RoundOutcome.WIN }, showdowns.size, "showdowns"))
        }
        add(tendency("Folds", rounds.count { it.detail.flag("folded") }, rounds.size, "hands"))

        // Of the times chips went in by choice, how often it was a bet or raise
        // rather than a call.
        val aggressive = rounds.sumOf { it.detail.count("bets") + it.detail.count("raises") }
        val passive = rounds.sumOf { it.detail.count("calls") }
        if (aggressive + passive >= ENOUGH_ROUNDS) {
            add(tendency("Bets or raises rather than calls", aggressive, aggressive + passive, "actions"))
        }
    }

    private fun blackjackTendencies(rounds: List<RoundRecord>): List<Tendency> = buildList {
        add(tendency("Busts", rounds.count { it.detail.count("busts") > 0 }, rounds.size, "rounds"))
        add(tendency("Doubles down", rounds.count { it.detail.count("doubled") > 0 }, rounds.size, "rounds"))
        add(tendency("Dealt a natural", rounds.count { it.detail.flag("natural") }, rounds.size, "rounds"))

        // How often the player's own decisions were the best play, by the arithmetic.
        val decisions = rounds.sumOf { it.detail.count("decisions") }
        if (decisions >= ENOUGH_ROUNDS) {
            add(tendency("Plays by the book", rounds.sumOf { it.detail.count("byTheBook") }, decisions, "decisions"))
        }
        val advised = rounds.sumOf { it.detail.count("advised") }
        if (advised >= ENOUGH_CASES) {
            add(tendency("Takes Banca's advice", rounds.sumOf { it.detail.count("followedAdvice") }, advised, "times asked"))
        }

        val offered = rounds.filter { it.detail.flag("insuranceOffered") }
        if (offered.size >= ENOUGH_CASES) {
            add(tendency("Takes insurance", offered.count { it.detail.flag("insured") }, offered.size, "offers"))
        }
    }

    private fun rouletteTendencies(rounds: List<RoundRecord>): List<Tendency> = buildList {
        // Where the chips go: on the numbers themselves, or on the boxes around them.
        val inside = rounds.sumOf { it.detail.count("insideStake") }
        val outside = rounds.sumOf { it.detail.count("outsideStake") }
        if (inside + outside > 0) add(tendency("Chips on the numbers", inside, inside + outside, "chips"))

        val bets = rounds.sumOf { it.detail.count("bets") }
        if (bets >= ENOUGH_ROUNDS) add(tendency("Bets that win", rounds.sumOf { it.detail.count("betsWon") }, bets, "bets"))

        val onANumber = rounds.filter { it.detail.count("straightBets") > 0 }
        if (onANumber.size >= ENOUGH_CASES) {
            add(tendency("Hits a single number", onANumber.count { it.detail.flag("straightHit") }, onANumber.size, "spins with one"))
        }
    }

    private fun tendency(label: String, count: Int, of: Int, unit: String) =
        Tendency(label = label, value = "${(100.0 * count / of).roundToInt()}%", basis = "$count of $of $unit")

    private fun activity(rounds: List<RoundRecord>, now: Instant): List<DayActivity> {
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val byDay = rounds.groupBy { LocalDate.ofInstant(it.endedAt, ZoneOffset.UTC) }

        return (ACTIVITY_DAYS - 1 downTo 0).map { daysAgo ->
            val day = today.minusDays(daysAgo.toLong())
            val played = byDay[day].orEmpty()
            DayActivity(date = day.toString(), rounds = played.size, net = played.sumOf { it.net })
        }
    }

    private fun share(count: Int, of: Int): Double? = if (of == 0) null else count.toDouble() / of
}

internal fun JsonObject.flag(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull ?: false
internal fun JsonObject.count(name: String): Int = this[name]?.jsonPrimitive?.intOrNull ?: 0
internal fun JsonObject.number(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
internal fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

/** Experience needed grows by a hundred more each level: 100, 300, 600, 1,000 and on. */
object Levels {
    fun startOf(level: Int): Long = 50L * level * (level - 1)

    fun levelAt(xp: Long): Int {
        var level = 1
        while (startOf(level + 1) <= xp) level++
        return level
    }

    fun titleOf(level: Int): String = when {
        level < 3 -> "Newcomer"
        level < 5 -> "Regular"
        level < 8 -> "Player"
        level < 12 -> "Card Sharp"
        level < 16 -> "High Roller"
        else -> "House Favourite"
    }
}

/** One line about a round, for the list of recent play. */
object Summaries {
    fun of(round: RoundRecord): String = when (round.game) {
        Game.POKER -> poker(round)
        Game.BLACKJACK -> blackjack(round)
        Game.ROULETTE -> roulette(round)
    }

    private fun roulette(round: RoundRecord): String {
        val detail = round.detail
        val pocket = detail.count("pocket")
        val landed = if (pocket == 0) "Zero" else "$pocket ${detail.text("color")}"
        val bets = detail.count("bets")
        return when {
            detail.flag("straightHit") -> "$landed, straight up"
            bets == 1 -> if (round.outcome == RoundOutcome.WIN) "$landed, your bet won" else "$landed, your bet lost"
            else -> "$landed, ${detail.count("betsWon")} of $bets bets won"
        }
    }

    private fun poker(round: RoundRecord): String {
        val detail = round.detail
        val hand = detail.text("hand")?.replace('_', ' ')
        return when {
            detail.flag("folded") -> "Folded"
            detail.flag("showdown") && hand != null -> when (round.outcome) {
                RoundOutcome.WIN -> "Won at showdown with $hand"
                RoundOutcome.LOSS -> "Lost at showdown with $hand"
                RoundOutcome.PUSH -> "Split the pot with $hand"
            }
            round.outcome == RoundOutcome.WIN -> "Banca folded"
            else -> "Hand played"
        }
    }

    private fun blackjack(round: RoundRecord): String {
        val detail = round.detail
        val outcomes = detail["outcomes"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val total = detail.count("total")
        val dealer = detail.count("dealerTotal")

        return when {
            outcomes.size > 1 -> "Split into ${outcomes.size} hands: ${outcomes.joinToString(", ")}"
            detail.flag("natural") && round.outcome == RoundOutcome.WIN -> "Blackjack"
            detail.count("busts") > 0 -> "Bust on $total"
            round.outcome == RoundOutcome.WIN && dealer > 21 -> "Dealer bust, you held $total"
            round.outcome == RoundOutcome.WIN -> "Won, $total against $dealer"
            round.outcome == RoundOutcome.PUSH -> "Push at $total"
            else -> "Lost, $total against $dealer"
        }.let { if (detail.count("doubled") > 0 && outcomes.size <= 1) "$it, doubled" else it }
    }
}

/** Marks of things done, each decided by the record and nothing else. */
object Achievements {

    fun all(rounds: List<RoundRecord>, balance: Long, ledger: List<LedgerEntry>): List<Achievement> {
        val wins = rounds.count { it.outcome == RoundOutcome.WIN }
        val gamesPlayed = rounds.map { it.game }.toSet().size
        val bestStreak = bestWinStreak(rounds)
        val biggestPot = rounds
            .filter { it.game == Game.POKER && it.outcome == RoundOutcome.WIN }
            .mapNotNull { it.detail.number("pot") }.maxOrNull() ?: 0

        var running = balance - ledger.sumOf { it.amount }
        val peak = ledger.maxOfOrNull { running += it.amount; running } ?: balance

        fun count(test: (RoundRecord) -> Boolean) = rounds.count(test).toLong()

        return listOf(
            goal("first_round", "Take a Seat", "Play your first round", rounds.size.toLong(), 1),
            goal("first_win", "First Win", "Win a round", wins.toLong(), 1),
            goal("rounds_25", "Getting Warm", "Play 25 rounds", rounds.size.toLong(), 25),
            goal("rounds_100", "Regular", "Play 100 rounds", rounds.size.toLong(), 100),
            goal("both_tables", "Both Tables", "Play at two different tables", gamesPlayed.toLong(), 2),
            goal("every_table", "Full House", "Play Hold'em, blackjack and roulette", gamesPlayed.toLong(), Game.entries.size.toLong()),
            goal("streak_3", "On a Run", "Win 3 rounds in a row", bestStreak.toLong(), 3),
            goal("streak_5", "Heater", "Win 5 rounds in a row", bestStreak.toLong(), 5),
            goal("natural", "Natural", "Be dealt a blackjack", count { it.detail.flag("natural") }, 1),
            goal(
                "double_win", "Pressed and Paid", "Win a hand you doubled",
                count { it.game == Game.BLACKJACK && it.outcome == RoundOutcome.WIN && it.detail.count("doubled") > 0 }, 1,
            ),
            goal(
                "showdown_win", "Called It", "Win a Hold'em showdown",
                count { it.game == Game.POKER && it.outcome == RoundOutcome.WIN && it.detail.flag("showdown") }, 1,
            ),
            goal(
                "straight_up", "On the Nose", "Hit a single number at roulette",
                count { it.game == Game.ROULETTE && it.detail.flag("straightHit") }, 1,
            ),
            goal("big_pot", "Big Pot", "Take down a Hold'em pot of 1,000 or more", biggestPot, 1_000),
            goal("peak_5000", "In the Black", "Reach a bankroll of 5,000", peak, 5_000),
        )
    }

    private fun goal(id: String, name: String, description: String, progress: Long, target: Long) = Achievement(
        id = id,
        name = name,
        description = description,
        earned = progress >= target,
        progress = progress.coerceIn(0, target),
        target = target,
    )

    private fun bestWinStreak(rounds: List<RoundRecord>): Int {
        var best = 0
        var run = 0
        for (round in rounds) {
            when (round.outcome) {
                RoundOutcome.WIN -> { run++; best = maxOf(best, run) }
                RoundOutcome.LOSS -> run = 0
                RoundOutcome.PUSH -> Unit
            }
        }
        return best
    }
}

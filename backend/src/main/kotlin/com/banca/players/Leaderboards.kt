package com.banca.players

import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant

/** One player's line in their league this week. */
@Serializable
data class LeagueRow(
    /** Where they stand among those who have played this week, from 1, or 0 if they have not played yet. */
    val position: Int,
    val name: String,
    val net: Long,
    val rounds: Int,
    val you: Boolean,
    /** "promotion", "safe" or "demotion": where they would end up if the week finished now. */
    val zone: String,
)

/** How last week went for the player, said once it has been settled. */
@Serializable
data class LastWeek(val tier: String, val position: Int, val net: Long, val outcome: String, val prize: Long)

/** The rules, sent with the standings so the page explaining them cannot drift from the code applying them. */
@Serializable
data class LeagueRules(
    val promoted: Int,
    val demoted: Int,
    val demotionNeeds: Int,
    val minRounds: Int,
    /** The prizes for first, second and third in the league being shown. */
    val prizes: List<Long>,
)

@Serializable
data class LeagueView(
    /** False for a guest, who can look at a league but is not in one. */
    val signedIn: Boolean,
    val tier: Int,
    val tierName: String,
    val tiers: List<String>,
    val weekStart: String,
    val endsAt: String,
    /** How many players are in this league, whether or not they have played this week. */
    val players: Int,
    val rows: List<LeagueRow>,
    val lastWeek: LastWeek?,
    val rules: LeagueRules,
)

@Serializable
data class TopRow(val position: Int, val name: String, val league: String, val net: Long, val rounds: Int, val you: Boolean)

@Serializable
data class TopView(val period: String, val game: String?, val rows: List<TopRow>)

/**
 * The leagues and the lists of winners, as players are shown them.
 *
 * Nothing runs on a timer. A week that has ended is settled by whoever next
 * asks to see a league, before they are shown it, so the first visitor on a
 * Monday is the one who closes the week before.
 */
class Leaderboards(private val store: PlayerStore, private val clock: Clock = Clock.systemUTC()) {

    /**
     * Settles every week that has ended and not been settled yet. The first
     * time it is ever called there is nothing to settle: the leagues begin
     * with the week in progress.
     */
    suspend fun catchUp() {
        val current = Leagues.weekOf(clock.instant())
        val last = store.lastSettledWeek()
        if (last == null) {
            val before = current.minusWeeks(1)
            store.settleWeek(before, Leagues.startOf(before), Leagues.endOf(before)) { emptyList() }
            return
        }

        var week = last.plusWeeks(1)
        // After a long silence the weeks are closed a few at a time, and the rest on later visits.
        repeat(MOST_WEEKS_AT_ONCE) {
            if (!week.isBefore(current)) return
            store.settleWeek(week, Leagues.startOf(week), Leagues.endOf(week), Leagues::settle)
            week = week.plusWeeks(1)
        }
    }

    /** The league [player] is in this week, or the lowest league for a guest to look at. */
    suspend fun league(player: Player?): LeagueView {
        catchUp()
        val now = clock.instant()
        val week = Leagues.weekOf(now)
        // The player's league may have changed in the settling just done.
        val member = player?.takeIf { it.accountId != null }
        val all = store.standings(Leagues.startOf(week), Leagues.endOf(week))
        val tier = member?.let { mine -> all.firstOrNull { it.playerId == mine.id }?.tier } ?: 0
        val inTier = all.filter { it.tier.coerceIn(0, Leagues.TIERS.lastIndex) == tier }

        val rows = Leagues.zones(inTier)
            .mapIndexed { index, (standing, zone) ->
                LeagueRow(
                    position = if (standing.rounds > 0) index + 1 else 0,
                    name = standing.name,
                    net = standing.net,
                    rounds = standing.rounds,
                    you = standing.playerId == member?.id,
                    zone = zone.name.lowercase(),
                )
            }
            // Those who have played, and the player themselves whether or not they have.
            .filter { it.rounds > 0 || it.you }
            .take(MOST_ROWS)

        val lastWeek = member?.let { store.leagueResult(it.id, week.minusWeeks(1)) }?.let { result ->
            LastWeek(
                tier = Leagues.TIERS[result.tier.coerceIn(0, Leagues.TIERS.lastIndex)],
                position = result.position,
                net = result.net,
                outcome = result.outcome.name.lowercase(),
                prize = result.prize,
            )
        }

        return LeagueView(
            signedIn = member != null,
            tier = tier,
            tierName = Leagues.TIERS[tier],
            tiers = Leagues.TIERS,
            weekStart = week.toString(),
            endsAt = Leagues.endOf(week).toString(),
            players = inTier.size,
            rows = rows,
            lastWeek = lastWeek,
            rules = LeagueRules(
                promoted = Leagues.PROMOTED,
                demoted = Leagues.DEMOTED,
                demotionNeeds = Leagues.DEMOTION_NEEDS,
                minRounds = Leagues.MIN_ROUNDS,
                prizes = (1..3).map { Leagues.prizeFor(tier, it) },
            ),
        )
    }

    /** The biggest winners among signed-in players, this week or since the beginning, at one game or all. */
    suspend fun top(thisWeek: Boolean, game: Game?, player: Player?): TopView {
        val now = clock.instant()
        val week = Leagues.weekOf(now)
        val from = if (thisWeek) Leagues.startOf(week) else Instant.EPOCH
        val until = if (thisWeek) Leagues.endOf(week) else now.plusSeconds(1)

        val rows = store.standings(from, until, game)
            .filter { it.rounds > 0 }
            .sortedWith(compareByDescending<Standing> { it.net }.thenByDescending { it.rounds }.thenBy { it.name })
            .take(MOST_ROWS)
            .mapIndexed { index, standing ->
                TopRow(
                    position = index + 1,
                    name = standing.name,
                    league = Leagues.TIERS[standing.tier.coerceIn(0, Leagues.TIERS.lastIndex)],
                    net = standing.net,
                    rounds = standing.rounds,
                    you = standing.playerId == player?.id,
                )
            }
        return TopView(period = if (thisWeek) "week" else "all", game = game?.name?.lowercase(), rows = rows)
    }

    private companion object {
        const val MOST_ROWS = 50
        const val MOST_WEEKS_AT_ONCE = 12
    }
}

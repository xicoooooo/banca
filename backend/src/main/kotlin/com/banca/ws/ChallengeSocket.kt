package com.banca.ws

import com.banca.agents.BookAdvisor
import com.banca.games.blackjack.Rules
import com.banca.players.ChallengeRecord
import com.banca.players.Challenges
import com.banca.players.Entry
import com.banca.players.FinishedRound
import com.banca.players.Funding
import com.banca.players.PlayerSession
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/** What a challenge's socket says besides the table's own messages: the challenge as it stands. */
@Serializable
@SerialName("challenge")
data class ChallengeMessage(val view: com.banca.players.ChallengeView, val type: String = "challenge")

/**
 * A player's go at a challenge: a blackjack table to themselves, dealt from
 * the challenge's own shoe, with the challenge's chips and bets, for its set
 * number of rounds. It speaks to the browser as the table for one does, and
 * says besides how the challenge stands, at the start and once their go is
 * over. There is no coach: what is being compared is the player's own play.
 */
class ChallengeConnection(
    private val code: String,
    private val challenges: Challenges,
    private val rules: Rules,
    private val send: Send,
    private val session: PlayerSession,
) : GameConnection {

    private var game: BlackjackConnection? = null
    private var challenge: ChallengeRecord? = null
    private var chips = 0L
    private var settled = 0
    private var over = false

    /** The challenge's chips, which stand in for the player's own at the table. */
    private val purse = object : PlayerSession(session.player, session.players) {
        override suspend fun balance(): Long = chips

        override suspend fun settle(round: FinishedRound): Long {
            chips += round.net
            settled++
            val run = challenge ?: return chips
            if (settled >= run.rounds || chips < Challenges.MIN_BET) finish(run)
            return chips
        }

        // Nobody is staked in a challenge: what a player ends on is the whole point of it.
        override suspend fun fund(needed: Long): Funding = Funding.Ready(chips)
    }

    override suspend fun attached() {
        game?.let { table ->
            tell()
            table.attached()
            return
        }

        when (val entry = challenges.enter(code, session.player)) {
            Entry.NoSuchChallenge -> send(refusal("There is no challenge with that code. Check it, or ask for it again.", code = "no_challenge"))
            Entry.AlreadyTaken -> {
                tell()
                send(refusal("You have taken this challenge already. Each player has one go.", code = "challenge_taken"))
            }
            is Entry.Ready -> {
                val run = entry.challenge
                challenge = run
                chips = run.stack
                game = BlackjackConnection(
                    config = BlackjackSocketConfig(rules = rules, random = { Random(run.seed) }, advisor = BookAdvisor(rules)),
                    send = send,
                    session = purse,
                    minBet = Challenges.MIN_BET,
                    maxBet = Challenges.MAX_BET,
                    lastRound = run.rounds,
                ).also {
                    tell()
                    it.attached()
                }
            }
        }
    }

    override suspend fun received(text: String) {
        val table = game ?: error("This challenge is not being played here")
        check(!over) { "Your go at this challenge is over" }
        val type = runCatching { wireJson.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()
        check(type != "advise") { "There is no coach in a challenge. It is your play that is being compared." }
        table.received(text)
    }

    override fun detached() {
        game?.detached()
    }

    /** The player left and did not come back. A round being played is finished as the table would, and what they have is their score. */
    override suspend fun abandoned() {
        val table = game ?: return
        table.abandoned()
        challenge?.let { if (!over) finish(it) }
    }

    private suspend fun finish(run: ChallengeRecord) {
        if (over) return
        over = true
        challenges.finish(run, session.player, settled, chips)
        tell()
    }

    /** Sends the challenge as it stands. */
    private suspend fun tell() {
        val view = challenges.view(code, session.player) ?: return
        send(wireJson.encodeToString(ChallengeMessage.serializer(), ChallengeMessage(view)))
    }
}

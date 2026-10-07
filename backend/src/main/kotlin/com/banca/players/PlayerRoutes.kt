package com.banca.players

import com.banca.Allowance
import com.banca.Limit
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.hours

@Serializable
data class Identity(val name: String, val balance: Long, val signedIn: Boolean)

/** The token is in here once, when the guest is created, and never sent again. */
@Serializable
data class NewGuest(val token: String, val player: Identity)

@Serializable
data class Rename(val name: String)

@Serializable
data class Problem(val message: String)

@Serializable
data class DailyClaim(val granted: Long, val balance: Long, val rewards: RewardStatus)

/** Where the browser sends a player to sign in, or nulls when signing in is not set up. */
@Serializable
data class SignInSettings(val url: String?, val publicKey: String?)

@Serializable
data class SignInRequest(val accessToken: String)

/** [token] is set when the device should now use it in place of the one it had. */
@Serializable
data class SignInResult(val token: String?, val player: Identity)

private fun ApplicationCall.token(): String =
    request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim().orEmpty()

/**
 * Who the caller is, from the token in the Authorization header, or null after
 * answering that they are not known.
 */
private suspend fun ApplicationCall.player(players: Players): Player? {
    val player = players.authenticate(token())
    if (player == null) respond(HttpStatusCode.Unauthorized, Problem("This player is not known here"))
    return player
}

private suspend fun Players.identity(player: Player) = Identity(player.name, balance(player), signedIn = player.accountId != null)

/**
 * Where a request came from. The server sits behind its host's proxies, which
 * say who they are forwarding for; the first of those headers is set by the
 * outermost proxy and cannot be forged by the caller. Without any of them the
 * connection's own address is the caller's.
 */
private fun ApplicationCall.callerAddress(): String =
    listOf("CF-Connecting-IP", "True-Client-IP")
        .firstNotNullOfOrNull { request.headers[it]?.trim()?.takeIf(String::isNotEmpty) }
        ?: request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf(String::isNotEmpty)
        ?: request.local.remoteAddress

/** New guests from one address: a household's worth in an hour, a small crowd's in a day. */
fun guestAllowance() = Allowance(Limit(5, 1.hours), Limit(20, 24.hours))

fun Application.configurePlayerRoutes(players: Players, signIn: SignInConfig? = null, newGuests: Allowance = guestAllowance()) {
    routing {
        post("/players") {
            // A browser keeps the guest it is given, so nobody needs many. A caller
            // asking for guest after guest is filling the database, not playing.
            if (!newGuests.take(call.callerAddress())) {
                call.respond(HttpStatusCode.TooManyRequests, Problem("Too many new players from here for now. Try again in a while."))
                return@post
            }
            val (player, token) = players.createGuest()
            call.respond(HttpStatusCode.Created, NewGuest(token, players.identity(player)))
        }

        get("/players/me") {
            val player = call.player(players) ?: return@get
            call.respond(players.identity(player))
        }

        patch("/players/me") {
            val player = call.player(players) ?: return@patch
            try {
                call.respond(players.identity(players.rename(player, call.receive<Rename>().name)))
            } catch (refused: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, Problem(refused.message ?: "That name is not allowed"))
            }
        }

        get("/players/me/dashboard") {
            val player = call.player(players) ?: return@get
            call.respond(players.dashboard(player))
        }

        // Today's reward, once a day. What is on offer is part of the dashboard.
        post("/players/me/rewards/daily") {
            val player = call.player(players) ?: return@post
            val granted = players.claimDaily(player)
            if (granted == null) {
                call.respond(HttpStatusCode.Conflict, Problem("Today's reward has already been claimed"))
                return@post
            }
            call.respond(DailyClaim(granted, players.balance(player), players.rewards(player)))
        }

        // The league the caller is in this week. Anyone may look: without a
        // token, or with a guest's, it is the lowest league, to be read and not joined.
        get("/league") {
            call.respond(players.leaderboards.league(players.authenticate(call.token())))
        }

        // The biggest winners, this week or of all time, at one game or all of them.
        get("/leaderboard") {
            val game = call.request.queryParameters["game"]?.let { name -> Game.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            val thisWeek = call.request.queryParameters["period"] != "all"
            call.respond(players.leaderboards.top(thisWeek, game, players.authenticate(call.token())))
        }

        // What anyone may see of a signed-in player: their name, level, league and trophies.
        get("/profiles/{id}") {
            val id = runCatching { java.util.UUID.fromString(call.parameters["id"]) }.getOrNull()
            val profile = id?.let { players.publicProfile(it) }
            if (profile == null) {
                call.respond(HttpStatusCode.NotFound, Problem("There is no such player"))
                return@get
            }
            call.respond(profile)
        }

        get("/sign-in") {
            call.respond(SignInSettings(signIn?.url, signIn?.publicKey))
        }

        // Saves the caller's profile to the account they have just signed in
        // to, or brings that account's profile to this device.
        post("/players/me/account") {
            val player = call.player(players) ?: return@post
            if (signIn == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, Problem("Signing in is not set up on this server"))
                return@post
            }
            val account = runCatching { signIn.verifier.verify(call.receive<SignInRequest>().accessToken) }.getOrNull()
            if (account == null) {
                call.respond(HttpStatusCode.Forbidden, Problem("That sign-in could not be confirmed"))
                return@post
            }
            val result = players.signIn(player, call.token(), account)
            call.respond(SignInResult(result.token, players.identity(result.player)))
        }

        // Signs this device out. The profile stays with its account.
        delete("/players/me/session") {
            call.player(players) ?: return@delete
            players.signOut(call.token())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

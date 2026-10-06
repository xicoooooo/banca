package com.banca.players

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

fun Application.configurePlayerRoutes(players: Players, signIn: SignInConfig? = null) {
    routing {
        post("/players") {
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

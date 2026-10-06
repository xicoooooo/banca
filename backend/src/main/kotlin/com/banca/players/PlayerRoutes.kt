package com.banca.players

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class Identity(val name: String, val balance: Long)

/** The token is in here once, when the guest is created, and never sent again. */
@Serializable
data class NewGuest(val token: String, val player: Identity)

@Serializable
data class Rename(val name: String)

@Serializable
data class Problem(val message: String)

/**
 * Who the caller is, from the token in the Authorization header, or null after
 * answering that they are not known.
 */
private suspend fun ApplicationCall.player(players: Players): Player? {
    val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim().orEmpty()
    val player = players.authenticate(token)
    if (player == null) respond(HttpStatusCode.Unauthorized, Problem("This player is not known here"))
    return player
}

fun Application.configurePlayerRoutes(players: Players) {
    routing {
        post("/players") {
            val (player, token) = players.createGuest()
            call.respond(HttpStatusCode.Created, NewGuest(token, Identity(player.name, players.balance(player))))
        }

        get("/players/me") {
            val player = call.player(players) ?: return@get
            call.respond(Identity(player.name, players.balance(player)))
        }

        patch("/players/me") {
            val player = call.player(players) ?: return@patch
            try {
                val renamed = players.rename(player, call.receive<Rename>().name)
                call.respond(Identity(renamed.name, players.balance(renamed)))
            } catch (refused: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, Problem(refused.message ?: "That name is not allowed"))
            }
        }

        get("/players/me/dashboard") {
            val player = call.player(players) ?: return@get
            call.respond(players.dashboard(player))
        }
    }
}

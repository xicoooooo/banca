package com.banca

import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class Health(val status: String, val service: String, val version: String)

fun Application.configureRouting() {
    routing {
        get("/health") {
            call.respond(Health(status = "ok", service = "banca", version = "0.1.0"))
        }
    }
}

package com.banca

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true })
    }
}

/**
 * Browsers only let the frontend call this API from origins named here.
 * FRONTEND_ORIGINS is a comma separated list of hosts, such as
 * "banca.vercel.app"; local development is always allowed.
 */
fun Application.configureCors() {
    install(CORS) {
        allowHost("localhost:5173")
        allowHost("127.0.0.1:5173")
        System.getenv("FRONTEND_ORIGINS")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { allowHost(it, schemes = listOf("https")) }
    }
}

fun Application.configureLogging() {
    install(CallLogging) {
        level = Level.INFO
    }
}

package com.banca

import com.banca.agents.AgentDriver
import com.banca.agents.OllamaProvider
import com.banca.ws.TableSocketConfig
import com.banca.ws.configureTableSocket
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlin.time.Duration

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(tableSocketFromEnvironment()) }.start(wait = true)
}

/**
 * MODEL_PROVIDER picks who plays the opponent's seat: "ollama" (the default)
 * for a local model, or "passive" for the check-and-call stand-in.
 */
private fun tableSocketFromEnvironment(): TableSocketConfig =
    when (val provider = System.getenv("MODEL_PROVIDER")?.lowercase() ?: "ollama") {
        "ollama" -> {
            val model = OllamaProvider(
                baseUrl = System.getenv("OLLAMA_BASE_URL") ?: "http://localhost:11434",
                model = System.getenv("OLLAMA_MODEL") ?: "qwen2.5:7b",
            )
            // The model takes long enough that no artificial pause is needed.
            TableSocketConfig(opponentDelay = Duration.ZERO, opponent = { AgentDriver(model) })
        }
        "passive" -> TableSocketConfig()
        else -> error("Unknown MODEL_PROVIDER '$provider', expected ollama or passive")
    }

fun Application.module(tableSocket: TableSocketConfig = TableSocketConfig()) {
    configureSerialization()
    configureLogging()
    configureCors()
    configureRouting()
    configureTableSocket(tableSocket)
}

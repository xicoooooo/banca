package com.banca

import com.banca.agents.AgentDriver
import com.banca.agents.FallbackProvider
import com.banca.agents.OllamaProvider
import com.banca.agents.OpenAiCompatibleProvider
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
 * for a local model, "groq" for the hosted free tier, or "passive" for the
 * check-and-call stand-in.
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
        "groq" -> {
            val key = System.getenv("GROQ_API_KEY")?.takeIf { it.isNotBlank() }
                ?: error("MODEL_PROVIDER is groq but GROQ_API_KEY is not set")
            // GROQ_MODELS lists models in order of preference. Each has its own
            // allowance on the free tier, so the next takes over when one runs out.
            val names = (System.getenv("GROQ_MODELS")?.takeIf { it.isNotBlank() } ?: DEFAULT_GROQ_MODELS)
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val model = FallbackProvider(names.map { it to OpenAiCompatibleProvider.groq(apiKey = key, model = it) })
            // A hosted model answers fast enough to need a pause, or the
            // opponent's reply lands before the eye has left the button.
            TableSocketConfig(opponent = { AgentDriver(model) })
        }
        "passive" -> TableSocketConfig()
        else -> error("Unknown MODEL_PROVIDER '$provider', expected ollama, groq or passive")
    }

private const val DEFAULT_GROQ_MODELS = "openai/gpt-oss-120b,openai/gpt-oss-20b,qwen/qwen3.8-27b"

fun Application.module(tableSocket: TableSocketConfig = TableSocketConfig()) {
    configureSerialization()
    configureLogging()
    configureCors()
    configureRouting()
    configureTableSocket(tableSocket)
}

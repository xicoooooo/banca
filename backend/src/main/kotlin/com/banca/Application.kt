package com.banca

import com.banca.agents.AgentDriver
import com.banca.agents.FallbackProvider
import com.banca.agents.ModelProvider
import com.banca.agents.OllamaProvider
import com.banca.agents.OpenAiCompatibleProvider
import com.banca.agents.Warmup
import com.banca.players.InMemoryPlayerStore
import com.banca.players.PlayerStore
import com.banca.players.Players
import com.banca.players.PostgresPlayerStore
import com.banca.players.SignInConfig
import com.banca.players.SupabaseAccounts
import com.banca.players.configurePlayerRoutes
import com.banca.ws.BlackjackSocketConfig
import com.banca.ws.TableSocketConfig
import com.banca.ws.configureGameSockets
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread
import kotlin.time.Duration

fun main() {
    val port = Config["PORT"]?.toIntOrNull() ?: 8080
    val startup = startupFromEnvironment()
    val players = Players(playerStoreFromEnvironment())
    val signIn = signInFromEnvironment()

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(tableSocket = startup.tableSocket, players = players, signIn = signIn)
        // Only once the server is answering, so rehearsing never delays the
        // health check that tells the host the deploy worked.
        startup.modelToWarmUp?.let { model ->
            monitor.subscribe(ApplicationStarted) { warmUpInBackground(model) }
        }
    }.start(wait = true)
}

/**
 * With DATABASE_URL set, players and their history are kept in Postgres.
 * Without it they are kept in memory and forgotten when the server stops, which
 * is enough to play and to develop against.
 */
private fun playerStoreFromEnvironment(): PlayerStore {
    val log = LoggerFactory.getLogger("com.banca.Application")
    val url = Config["DATABASE_URL"]
    return if (url != null) {
        log.info("Keeping players in Postgres")
        PostgresPlayerStore.connect(url)
    } else {
        log.warn("DATABASE_URL is not set: players are kept in memory and will be forgotten on restart")
        InMemoryPlayerStore()
    }
}

/**
 * Signing in is offered when SUPABASE_URL and SUPABASE_ANON_KEY are set. Both
 * are public values. Without them everyone plays as a guest.
 */
private fun signInFromEnvironment(): SignInConfig? {
    val url = Config["SUPABASE_URL"]
    val key = Config["SUPABASE_ANON_KEY"]
    if (url == null || key == null) {
        LoggerFactory.getLogger("com.banca.Application").warn("SUPABASE_URL or SUPABASE_ANON_KEY is not set: signing in is off")
        return null
    }
    return SignInConfig(url, key, SupabaseAccounts(url, key))
}

private class Startup(val tableSocket: TableSocketConfig, val modelToWarmUp: ModelProvider? = null)

/**
 * MODEL_PROVIDER picks who plays the opponent's seat: "ollama" (the default)
 * for a local model, "groq" for the hosted free tier, or "passive" for the
 * check-and-call stand-in.
 */
private fun startupFromEnvironment(): Startup =
    when (val provider = Config["MODEL_PROVIDER"]?.lowercase() ?: "ollama") {
        "ollama" -> {
            val model = OllamaProvider(
                baseUrl = Config["OLLAMA_BASE_URL"] ?: "http://localhost:11434",
                model = Config["OLLAMA_MODEL"] ?: "qwen2.5:7b",
            )
            // The model takes long enough that no artificial pause is needed.
            Startup(TableSocketConfig(opponentDelay = Duration.ZERO, opponent = { AgentDriver(model) }))
        }
        "groq" -> {
            val key = Config["GROQ_API_KEY"]
                ?: error("MODEL_PROVIDER is groq but GROQ_API_KEY is not set")
            // GROQ_MODELS lists models in order of preference. Each has its own
            // allowance on the free tier, so the next takes over when one runs out.
            val names = (Config["GROQ_MODELS"] ?: DEFAULT_GROQ_MODELS)
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val model = FallbackProvider(names.map { it to OpenAiCompatibleProvider.groq(apiKey = key, model = it) })
            Startup(
                tableSocket = TableSocketConfig(opponentDelay = Duration.ZERO, opponent = { AgentDriver(model) }),
                modelToWarmUp = model,
            )
        }
        "passive" -> Startup(TableSocketConfig())
        else -> error("Unknown MODEL_PROVIDER '$provider', expected ollama, groq or passive")
    }

/**
 * Rehearses in the background so the first visitor finds the server warm. Not
 * done for a local model, where it would only slow a developer's every restart.
 */
private fun warmUpInBackground(model: ModelProvider) {
    thread(isDaemon = true, name = "warm-up") {
        runCatching { runBlocking { Warmup.run(model) } }
    }
}

// The first makes all its tool calls in one reply. The other two make one per
// reply, which costs more round trips and tokens, so they only stand in for it.
private const val DEFAULT_GROQ_MODELS = "qwen/qwen3.8-27b,openai/gpt-oss-120b,openai/gpt-oss-20b"

fun Application.module(
    tableSocket: TableSocketConfig = TableSocketConfig(),
    blackjack: BlackjackSocketConfig = BlackjackSocketConfig(),
    players: Players = Players(InMemoryPlayerStore()),
    signIn: SignInConfig? = null,
) {
    configureSerialization()
    configureLogging()
    configureCors()
    configureRouting()
    configurePlayerRoutes(players, signIn)
    configureGameSockets(players = players, poker = tableSocket, blackjack = blackjack)
}

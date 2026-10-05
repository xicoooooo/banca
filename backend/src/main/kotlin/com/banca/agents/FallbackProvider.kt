package com.banca.agents

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Several models behind one, tried in turn when one fails.
 *
 * Free tiers give every model its own small allowance per minute, so when one
 * is spent the next still has room. Whichever answered last is asked first
 * next time, which avoids knocking on a door that has just been shut.
 */
class FallbackProvider(private val providers: List<Pair<String, ModelProvider>>) : ModelProvider {

    init {
        require(providers.isNotEmpty()) { "At least one model is needed" }
    }

    private val log = LoggerFactory.getLogger(FallbackProvider::class.java)
    private val preferred = AtomicInteger(0)

    override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
        val first = preferred.get()
        var lastFailure: Exception? = null

        for (offset in providers.indices) {
            val index = (first + offset) % providers.size
            val (name, provider) = providers[index]
            try {
                return provider.chat(messages, tools).also { preferred.set(index) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.info("Model {} failed, trying the next: {}", name, failure.message?.take(120))
                lastFailure = failure
            }
        }

        throw IllegalStateException("Every model failed", lastFailure)
    }
}

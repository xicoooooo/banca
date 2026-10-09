package com.banca.agents

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.time.Duration.Companion.milliseconds

/**
 * Several models behind one, tried in turn when one fails.
 *
 * Free tiers give every model its own small allowance per minute, so when one
 * is spent the next still has room. Whichever answered last is asked first
 * next time, which avoids knocking on a door that has just been shut.
 *
 * A model that says it is busy also says for how long, and is left alone
 * until then. When every one of them is resting, that is said at once, with
 * no request made at all: the caller has another way to play the turn, and a
 * second spent being refused three times over is a second the table waits.
 */
class FallbackProvider(
    private val providers: List<Pair<String, ModelProvider>>,
    private val now: () -> Long = System::currentTimeMillis,
) : ModelProvider {

    init {
        require(providers.isNotEmpty()) { "At least one model is needed" }
    }

    private val log = LoggerFactory.getLogger(FallbackProvider::class.java)
    private val preferred = AtomicInteger(0)

    /** When each model may be asked again, as a moment in milliseconds. Nought for one that may be asked now. */
    private val restingUntil = AtomicLongArray(providers.size)

    override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
        val first = preferred.get()
        val at = now()
        var lastFailure: Exception? = null

        for (offset in providers.indices) {
            val index = (first + offset) % providers.size
            if (restingUntil.get(index) > at) continue

            val (name, provider) = providers[index]
            try {
                return provider.chat(messages, tools).also { preferred.set(index) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (busy: RateLimited) {
                restingUntil.set(index, now() + busy.retryAfter.inWholeMilliseconds)
                log.info("Model {} is busy for {}, trying the next", name, busy.retryAfter)
                lastFailure = busy
            } catch (failure: Exception) {
                log.info("Model {} failed, trying the next: {}", name, failure.message?.take(120))
                lastFailure = failure
            }
        }

        // Nothing was asked, or everything asked was busy: say how long until the first of them is back.
        val soonest = (0 until providers.size).minOf { restingUntil.get(it) } - now()
        if (lastFailure == null || lastFailure is RateLimited) {
            throw RateLimited(soonest.coerceAtLeast(0).milliseconds, "Every model is busy for now")
        }
        throw IllegalStateException("Every model failed", lastFailure)
    }
}

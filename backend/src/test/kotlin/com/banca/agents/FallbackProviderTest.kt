package com.banca.agents

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FallbackProviderTest {

    private class Fake(private val name: String, var broken: Boolean = false) : ModelProvider {
        var asked = 0
        override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
            asked++
            check(!broken) { "$name is rate limited" }
            return ModelReply(name, emptyList())
        }
    }

    private fun ask(provider: ModelProvider) = runBlocking { provider.chat(emptyList(), emptyList()).text }

    @Test
    fun `the first model answers when it can`() {
        val a = Fake("a")
        val b = Fake("b")
        val provider = FallbackProvider(listOf("a" to a, "b" to b))

        assertEquals("a", ask(provider))
        assertEquals(0, b.asked)
    }

    @Test
    fun `a failing model is skipped for the next`() {
        val a = Fake("a", broken = true)
        val b = Fake("b")

        assertEquals("b", ask(FallbackProvider(listOf("a" to a, "b" to b))))
    }

    @Test
    fun `the model that answered last is asked first next time`() {
        val a = Fake("a", broken = true)
        val b = Fake("b")
        val provider = FallbackProvider(listOf("a" to a, "b" to b))

        ask(provider)
        ask(provider)
        ask(provider)

        assertEquals(1, a.asked, "the spent model is not asked again while another works")
        assertEquals(3, b.asked)
    }

    @Test
    fun `it comes back round to an earlier model once the later one fails`() {
        val a = Fake("a", broken = true)
        val b = Fake("b")
        val provider = FallbackProvider(listOf("a" to a, "b" to b))
        ask(provider)

        a.broken = false
        b.broken = true

        assertEquals("a", ask(provider))
    }

    @Test
    fun `when every model fails the caller hears about it`() {
        val provider = FallbackProvider(listOf("a" to Fake("a", broken = true), "b" to Fake("b", broken = true)))

        assertFailsWith<IllegalStateException> { ask(provider) }
    }

    // ----------------------------------------------------------- busy models

    private class Busy(private val name: String, private val forMs: Long) : ModelProvider {
        var asked = 0
        var busy = true
        override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
            asked++
            if (busy) throw RateLimited(forMs.milliseconds, "$name is busy")
            return ModelReply(name, emptyList())
        }
    }

    @Test
    fun `a model that says it is busy is left alone for as long as it asked`() {
        var now = 1_000L
        val a = Busy("a", forMs = 7_000)
        val b = Fake("b")
        val provider = FallbackProvider(listOf("a" to a, "b" to b), now = { now })

        assertEquals("b", ask(provider))
        b.broken = true
        now += 3_000
        assertFailsWith<IllegalStateException> { ask(provider) }
        assertEquals(1, a.asked, "still resting, so not asked")

        now += 5_000
        a.busy = false
        assertEquals("a", ask(provider), "its rest over, it is asked again")
    }

    @Test
    fun `when every model is resting that is said at once, with nobody asked, and for how long`() {
        var now = 1_000L
        val a = Busy("a", forMs = 9_000)
        val b = Busy("b", forMs = 4_000)
        val provider = FallbackProvider(listOf("a" to a, "b" to b), now = { now })

        assertFailsWith<RateLimited> { ask(provider) }
        assertEquals(1 to 1, a.asked to b.asked)

        now += 1_000
        val told = assertFailsWith<RateLimited> { ask(provider) }
        assertEquals(1 to 1, a.asked to b.asked, "no request was made to be refused")
        assertEquals(3_000.milliseconds, told.retryAfter, "the first of them is back in three seconds")
    }

    @Test
    fun `how long a busy model wants is read from its header, or from what it wrote`() {
        assertEquals(7.seconds, OpenAiCompatibleProvider.retryAfter("7", ""))
        assertEquals(2500.milliseconds, OpenAiCompatibleProvider.retryAfter("2.5", "whatever it says"))
        assertEquals(7360.milliseconds, OpenAiCompatibleProvider.retryAfter(null, """{"error":{"message":"Rate limit reached. Please try again in 7.36s. Need more tokens?"}}"""))
        assertEquals(72500.milliseconds, OpenAiCompatibleProvider.retryAfter(null, "Please try again in 1m12.5s."))
        assertEquals(450.milliseconds, OpenAiCompatibleProvider.retryAfter(null, "try again in 450ms"))
        assertEquals(10.seconds, OpenAiCompatibleProvider.retryAfter(null, "no idea"), "and a guess when it does not say")
    }
}

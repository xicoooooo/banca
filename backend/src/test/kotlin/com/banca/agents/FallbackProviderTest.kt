package com.banca.agents

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}

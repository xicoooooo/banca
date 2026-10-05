package com.banca.agents

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class WarmupTest {

    @Test
    fun `rehearses for free and then plays one real turn`() {
        var realCalls = 0
        val real = object : ModelProvider {
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
                realCalls++
                return ModelReply("""submit_action {"action": "fold"}""", emptyList())
            }
        }

        runBlocking { Warmup.run(real, rehearsals = 3) }

        assertTrue(realCalls in 1..6, "the real model is asked for one turn only, was asked $realCalls times")
    }

    @Test
    fun `a model that is down does not stop the warm-up`() {
        val down = object : ModelProvider {
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply =
                error("unreachable")
        }

        runBlocking { Warmup.run(down, rehearsals = 2) }
    }
}

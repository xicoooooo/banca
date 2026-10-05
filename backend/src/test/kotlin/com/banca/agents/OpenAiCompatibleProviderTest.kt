package com.banca.agents

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiCompatibleProviderTest {

    private class Recorded(var url: String = "", var authorization: String? = null, var body: JsonObject? = null)

    private fun provider(
        recorded: Recorded = Recorded(),
        status: HttpStatusCode = HttpStatusCode.OK,
        answer: String,
    ) = OpenAiCompatibleProvider(
        baseUrl = "https://models.test/v1",
        apiKey = "test-key",
        model = "test-model",
        http = HttpClient(
            MockEngine { request ->
                recorded.url = request.url.toString()
                recorded.authorization = request.headers[HttpHeaders.Authorization]
                recorded.body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
    )

    private val tools = listOf(ToolSpec("get_pot_odds", "Pot odds", buildJsonObject { put("type", "object") }))

    @Test
    fun `a tool call in the answer is read, with its arguments unpacked from the string`() = runBlocking {
        val provider = provider(
            answer = """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"abc","type":"function","function":{"name":"submit_action","arguments":"{\"action\":\"raise\",\"amount\":300}"}}
                ]}}]}
            """.trimIndent(),
        )

        val reply = provider.chat(listOf(ChatMessage.User("go")), tools)

        assertEquals("", reply.text)
        val call = reply.toolCalls.single()
        assertEquals("submit_action", call.name)
        assertEquals("raise", call.arguments.getValue("action").jsonPrimitive.content)
        assertEquals(300, call.arguments.getValue("amount").jsonPrimitive.content.toInt())
    }

    @Test
    fun `a plain text answer has no tool calls`() = runBlocking {
        val provider = provider(answer = """{"choices":[{"message":{"role":"assistant","content":" I will call. "}}]}""")

        val reply = provider.chat(listOf(ChatMessage.User("go")), tools)

        assertEquals("I will call.", reply.text)
        assertTrue(reply.toolCalls.isEmpty())
    }

    @Test
    fun `the request carries the key, the model and the tools`() = runBlocking {
        val recorded = Recorded()
        provider(recorded, answer = """{"choices":[{"message":{"content":"ok"}}]}""")
            .chat(listOf(ChatMessage.System("be brief"), ChatMessage.User("go")), tools)

        assertEquals("https://models.test/v1/chat/completions", recorded.url)
        assertEquals("Bearer test-key", recorded.authorization)

        val body = recorded.body!!
        assertEquals("test-model", body.getValue("model").jsonPrimitive.content)
        assertEquals(
            "get_pot_odds",
            body.getValue("tools").jsonArray.single().jsonObject
                .getValue("function").jsonObject.getValue("name").jsonPrimitive.content,
        )
        assertEquals(listOf("system", "user"), body.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
    }

    @Test
    fun `each tool result is tied to the call that asked for it`() = runBlocking {
        val recorded = Recorded()
        val noArguments = JsonObject(emptyMap())

        provider(recorded, answer = """{"choices":[{"message":{"content":"ok"}}]}""").chat(
            listOf(
                ChatMessage.User("go"),
                ChatMessage.Assistant("", listOf(ToolCall("get_hand_equity", noArguments), ToolCall("get_pot_odds", noArguments))),
                ChatMessage.ToolResult("get_hand_equity", "0.6"),
                ChatMessage.ToolResult("get_pot_odds", "0.25"),
                ChatMessage.Assistant("", listOf(ToolCall("get_legal_actions", noArguments))),
                ChatMessage.ToolResult("get_legal_actions", "fold, call"),
            ),
            tools,
        )

        val messages = recorded.body!!.getValue("messages").jsonArray.map { it.jsonObject }
        val callIds = messages.filter { "tool_calls" in it }
            .flatMap { it.getValue("tool_calls").jsonArray }
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }
        val resultIds = messages.filter { it.getValue("role").jsonPrimitive.content == "tool" }
            .map { it.getValue("tool_call_id").jsonPrimitive.content }

        assertEquals(3, callIds.distinct().size, "every call has its own id")
        assertEquals(callIds, resultIds, "results answer the calls in order")

        val firstCall = messages[1].getValue("tool_calls").jsonArray.first().jsonObject.getValue("function").jsonObject
        assertEquals("{}", firstCall.getValue("arguments").jsonPrimitive.content, "arguments are sent as a string")
    }

    @Test
    fun `a refusal from the service is an error, so the agent can fall back`() {
        val limited = provider(status = HttpStatusCode.TooManyRequests, answer = """{"error":{"message":"rate limit"}}""")

        assertFailsWith<IllegalStateException> {
            runBlocking { limited.chat(listOf(ChatMessage.User("go")), tools) }
        }
    }
}

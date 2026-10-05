package com.banca.agents

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * Any hosted model that speaks the OpenAI chat completions format, which
 * includes the free tiers this project deploys on. Groq is the first.
 */
class OpenAiCompatibleProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val http: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    },
) : ModelProvider {

    private val log = LoggerFactory.getLogger(OpenAiCompatibleProvider::class.java)

    override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
        val response = http.post("$baseUrl/chat/completions") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(request(messages, tools).toString())
        }
        val body = response.bodyAsText()
        check(response.status.isSuccess()) { "The model answered ${response.status}: ${body.take(200)}" }

        val answer = Json.parseToJsonElement(body).jsonObject
        // Free tiers are metered in tokens, so what each call costs is worth seeing.
        answer["usage"]?.jsonObject?.get("total_tokens")?.let { log.info("{} used {} tokens", model, it) }

        val message = answer
            .getValue("choices").jsonArray.first().jsonObject
            .getValue("message").jsonObject

        return ModelReply(
            text = message["content"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
            toolCalls = message["tool_calls"]?.takeIf { it !is JsonNull }?.jsonArray.orEmpty().map { call ->
                val function = call.jsonObject.getValue("function").jsonObject
                ToolCall(
                    name = function.getValue("name").jsonPrimitive.content,
                    // Arguments arrive as a JSON document inside a string.
                    arguments = function["arguments"]?.jsonPrimitive?.contentOrNull
                        ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                        ?: JsonObject(emptyMap()),
                )
            },
        )
    }

    private fun request(messages: List<ChatMessage>, tools: List<ToolSpec>) = buildJsonObject {
        put("model", model)
        put("temperature", 0.4)
        put("messages", messagesJson(messages))
        put("tool_choice", "auto")
        put(
            "tools",
            buildJsonArray {
                tools.forEach { tool ->
                    add(
                        buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            }
                        },
                    )
                }
            },
        )
    }

    /**
     * This format ties every tool result to the call that asked for it by id.
     * The conversation does not carry ids, so they are handed out here in
     * order: results always follow their calls in the order they were made.
     */
    private fun messagesJson(messages: List<ChatMessage>) = buildJsonArray {
        var issued = 0
        val awaitingResult = ArrayDeque<String>()

        for (message in messages) {
            add(
                buildJsonObject {
                    when (message) {
                        is ChatMessage.System -> {
                            put("role", "system")
                            put("content", message.text)
                        }
                        is ChatMessage.User -> {
                            put("role", "user")
                            put("content", message.text)
                        }
                        is ChatMessage.Assistant -> {
                            put("role", "assistant")
                            put("content", message.text)
                            if (message.toolCalls.isNotEmpty()) {
                                put(
                                    "tool_calls",
                                    buildJsonArray {
                                        message.toolCalls.forEach { call ->
                                            val id = "call_${issued++}".also(awaitingResult::addLast)
                                            add(
                                                buildJsonObject {
                                                    put("id", id)
                                                    put("type", "function")
                                                    putJsonObject("function") {
                                                        put("name", call.name)
                                                        put("arguments", call.arguments.toString())
                                                    }
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                        is ChatMessage.ToolResult -> {
                            put("role", "tool")
                            put("tool_call_id", awaitingResult.removeFirstOrNull() ?: "call_unknown")
                            put("content", message.content)
                        }
                    }
                },
            )
        }
    }

    companion object {
        fun groq(apiKey: String, model: String) =
            OpenAiCompatibleProvider(baseUrl = "https://api.groq.com/openai/v1", apiKey = apiKey, model = model)
    }
}

package com.banca.agents

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A model served by Ollama on this machine. Free, and needs no key. */
class OllamaProvider(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "qwen2.5:7b",
    private val http: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 120_000 }
    },
) : ModelProvider {

    override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply {
        val response = http.post("$baseUrl/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(request(messages, tools).toString())
        }
        val body = response.bodyAsText()
        check(response.status.isSuccess()) { "Ollama answered ${response.status}: ${body.take(200)}" }

        val message = Json.parseToJsonElement(body).jsonObject.getValue("message").jsonObject
        return ModelReply(
            text = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
            toolCalls = message["tool_calls"]?.jsonArray.orEmpty().map { call ->
                val function = call.jsonObject.getValue("function").jsonObject
                ToolCall(
                    name = function.getValue("name").jsonPrimitive.content,
                    arguments = function["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
                )
            },
        )
    }

    private fun request(messages: List<ChatMessage>, tools: List<ToolSpec>) = buildJsonObject {
        put("model", model)
        put("stream", false)
        // A little variety between hands, without wandering off the tools.
        putJsonObject("options") { put("temperature", 0.4) }
        put("messages", buildJsonArray { messages.forEach { add(it.toJson()) } })
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

    private fun ChatMessage.toJson(): JsonObject = buildJsonObject {
        when (this@toJson) {
            is ChatMessage.System -> {
                put("role", "system")
                put("content", text)
            }
            is ChatMessage.User -> {
                put("role", "user")
                put("content", text)
            }
            is ChatMessage.Assistant -> {
                put("role", "assistant")
                put("content", text)
                put(
                    "tool_calls",
                    buildJsonArray {
                        toolCalls.forEach { call ->
                            add(
                                buildJsonObject {
                                    putJsonObject("function") {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    }
                                },
                            )
                        }
                    },
                )
            }
            is ChatMessage.ToolResult -> {
                put("role", "tool")
                put("tool_name", toolName)
                put("content", content)
            }
        }
    }
}

package com.banca.agents

import kotlinx.serialization.json.JsonObject

/**
 * A language model that can call tools. The agent only ever talks to this, so
 * which model answers is configuration: a local one in development, a hosted
 * free tier in the deployed demo, anything else later.
 */
interface ModelProvider {
    suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): ModelReply
}

data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class ToolCall(val name: String, val arguments: JsonObject)

data class ModelReply(val text: String, val toolCalls: List<ToolCall>)

sealed interface ChatMessage {
    data class System(val text: String) : ChatMessage
    data class User(val text: String) : ChatMessage
    data class Assistant(val text: String, val toolCalls: List<ToolCall>) : ChatMessage
    data class ToolResult(val toolName: String, val content: String) : ChatMessage
}

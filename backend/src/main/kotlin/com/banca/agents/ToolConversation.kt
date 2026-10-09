package com.banca.agents

import com.banca.sessions.TraceEvent
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What one job for the model consists of: the tools it is given, what it is
 * told, and the one tool that ends the job when it is called successfully.
 */
class ToolTask(
    val server: Server,
    val systemPrompt: String,
    val opening: String,
    /**
     * What the model is told in place of [systemPrompt] until it has a tool's
     * answer in front of it. Before then all it has to do is go and look, and
     * telling it only that costs a fraction of the words. Null to tell it
     * everything from the start.
     */
    val briefing: String? = null,
    /**
     * Tools that only look, and are always wanted together. Some models ask
     * for them all in one reply and some for one at a time, each at the price
     * of another round trip. So when the model reaches for any of these, the
     * rest are fetched alongside, and it has everything in front of it for
     * its next reply whichever kind of model it is.
     */
    val lookTogether: Set<String> = emptySet(),
    /** The tool that commits the model's answer. Its steps are not traced; the caller reports the outcome. */
    val finishingTool: String,
    /** Said to a model that answers in prose instead of finishing. */
    val reminder: String,
    val isFinished: () -> Boolean,
    /** What a tool call is called in the trace, as something done. */
    val labelFor: (tool: String) -> String,
    /** Reads the finishing tool's arguments from a call written in function style, if that is worth trying. */
    val readWrittenFinish: (afterName: String) -> JsonObject? = { null },
)

/** Where a conversation's time went, for the log. Hosting on little CPU makes this worth watching. */
class Timing {
    private val start = System.nanoTime()
    var setup = 0L
    var model = 0L
    var modelCalls = 0
    var tools = 0L
    var toolCalls = 0

    fun sinceStart(): Long = (System.nanoTime() - start) / 1_000_000

    inline fun <T> timed(record: (Long) -> Unit, block: () -> T): T {
        val began = System.nanoTime()
        try {
            return block()
        } finally {
            record((System.nanoTime() - began) / 1_000_000)
        }
    }

    override fun toString(): String =
        "${sinceStart()} ms: $setup ms connecting tools, $model ms in $modelCalls model calls, $tools ms in $toolCalls tool calls"
}

/**
 * Lets a language model work through a task by calling tools over MCP, until
 * it calls the one that finishes it or runs out of turns.
 *
 * This is the part every Banca agent shares, whatever the game: connecting the
 * model to the tools, feeding results back, tracing each step, and recovering
 * the tool calls small models write out as text instead of making.
 */
class ToolConversation(private val model: ModelProvider, private val maxModelCalls: Int) {

    suspend fun run(task: ToolTask, trace: suspend (TraceEvent) -> Unit, timing: Timing) {
        // The link's message pumps live on their own job so they can be stopped
        // however the conversation ends.
        val link = CoroutineScope(currentCoroutineContext() + Job())
        val client = Client(Implementation(name = "banca-agent", version = "0.1.0"))

        try {
            val listed = timing.timed({ timing.setup = it }) {
                val (serverEnd, clientEnd) = LinkedTransport.pair(link)
                task.server.createSession(serverEnd)
                client.connect(clientEnd)
                client.listTools().tools
            }

            val specs = listed.map { tool ->
                ToolSpec(
                    name = tool.name,
                    description = tool.description.orEmpty(),
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", tool.inputSchema.properties ?: buildJsonObject { })
                    },
                )
            }

            val messages = mutableListOf<ChatMessage>(
                ChatMessage.System(task.systemPrompt),
                ChatMessage.User(task.opening),
            )

            val called = mutableSetOf<String>()

            repeat(maxModelCalls) {
                // A free model is metered by the word, so each call carries only what it needs:
                // the short briefing until something has been looked at, and from then on only
                // the tools that have not been used yet, beside the one that finishes.
                val looked = called.isNotEmpty()
                val said = if (task.briefing != null && !looked) listOf(ChatMessage.System(task.briefing)) + messages.drop(1) else messages
                val offered = if (looked) specs.filter { it.name == task.finishingTool || it.name !in called } else specs

                val answer = timing.timed({ timing.model += it; timing.modelCalls++ }) { model.chat(said, offered) }
                // Small models sometimes write a tool call out as text instead
                // of making it. Reading it back costs nothing and saves the turn.
                val written = if (answer.toolCalls.isEmpty()) writtenToolCalls(answer.text, specs, task) else emptyList()
                val asked = if (written.isEmpty()) answer else ModelReply(text = "", toolCalls = written)
                // Having started to look, it is shown the rest of what there is to look at.
                val alongside = if (asked.toolCalls.any { it.name in task.lookTogether }) {
                    task.lookTogether
                        .filter { name -> name !in called && asked.toolCalls.none { it.name == name } && specs.any { it.name == name } }
                        .map { ToolCall(it, JsonObject(emptyMap())) }
                } else {
                    emptyList()
                }
                val reply = ModelReply(asked.text, asked.toolCalls + alongside)
                messages += ChatMessage.Assistant(reply.text, reply.toolCalls)

                if (reply.text.isNotBlank()) {
                    trace(TraceEvent(TraceEvent.THOUGHT, "Thought it over", reply.text))
                }
                if (reply.toolCalls.isEmpty()) {
                    messages += ChatMessage.User(task.reminder)
                }

                for (call in reply.toolCalls) {
                    val result = timing.timed({ timing.tools += it; timing.toolCalls++ }) {
                        client.callTool(
                            CallToolRequest(CallToolRequestParams(name = call.name, arguments = call.arguments)),
                        )
                    }
                    val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                    messages += ChatMessage.ToolResult(call.name, text)
                    called += call.name

                    if (call.name != task.finishingTool) {
                        trace(TraceEvent(TraceEvent.TOOL, task.labelFor(call.name), "${call.name} → $text"))
                    }
                    if (task.isFinished()) return
                }
            }
        } finally {
            link.cancel()
        }
    }

    /**
     * Finds tool calls spelled out in prose, such as
     * `submit_action {"action": "call"}`: a known tool name followed by its
     * arguments as a JSON object, or by nothing for a tool that takes none.
     */
    private fun writtenToolCalls(text: String, specs: List<ToolSpec>, task: ToolTask): List<ToolCall> =
        specs.mapNotNull { spec ->
            val at = text.indexOf(spec.name)
            if (at < 0) return@mapNotNull null

            val after = text.substring(at + spec.name.length).trimStart(' ', ':', '(', '`', '\n')
            val arguments = when {
                after.startsWith("{") -> firstJsonObject(after)
                spec.name == task.finishingTool -> task.readWrittenFinish(after)
                else -> null
            }
            // The finishing tool is meaningless without arguments; the others take none.
            if (arguments == null && spec.name == task.finishingTool) return@mapNotNull null

            at to ToolCall(spec.name, arguments ?: JsonObject(emptyMap()))
        }.sortedBy { (position, _) -> position }.map { (_, call) -> call }

    private fun firstJsonObject(text: String): JsonObject? {
        var depth = 0
        for ((index, char) in text.withIndex()) {
            when (char) {
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    return runCatching { Json.parseToJsonElement(text.substring(0, index + 1)) as? JsonObject }.getOrNull()
                }
            }
        }
        return null
    }
}

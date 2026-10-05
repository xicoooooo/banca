package com.banca.agents

import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * An MCP transport that delivers straight to a partner in the same process.
 *
 * The agent and its tools live in one JVM, so there is no socket to cross, but
 * they still speak the real protocol: the same server could be put behind
 * stdio or HTTP for an outside client without changing a tool.
 */
class LinkedTransport private constructor(private val scope: CoroutineScope) : AbstractTransport() {

    private lateinit var partner: LinkedTransport
    private val inbox = Channel<JSONRPCMessage>(Channel.UNLIMITED)

    override suspend fun start() {
        scope.launch {
            for (message in inbox) _onMessage(message)
        }
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        partner.inbox.send(message)
    }

    override suspend fun close() {
        inbox.close()
        invokeOnCloseCallback()
    }

    companion object {
        /** Two ends of one link. Messages are delivered in order on [scope]. */
        fun pair(scope: CoroutineScope): Pair<LinkedTransport, LinkedTransport> {
            val first = LinkedTransport(scope)
            val second = LinkedTransport(scope)
            first.partner = second
            second.partner = first
            return first to second
        }
    }
}

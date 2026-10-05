import { useCallback, useEffect, useRef, useState } from 'react'
import type { ClientMessage, ServerMessage, TableView, TraceEvent } from './types'

const WS_URL = import.meta.env.VITE_WS_URL ?? 'ws://localhost:8080/ws/table'

// Free hosting puts the backend to sleep when idle and takes up to a minute to
// wake it, so a first connection that fails is retried for a while.
const RETRY_EVERY_MS = 3_000
const MAX_ATTEMPTS = 30

export type Connection = 'connecting' | 'open' | 'closed'

/** What the opponent did to reach its decisions in one hand. */
export type Reasoning = {
  handNumber: number
  events: TraceEvent[]
  /** True once the server has sent the private detail, after the hand. */
  revealed: boolean
}

const NO_REASONING: Reasoning = { handNumber: 0, events: [], revealed: false }

export function useTable() {
  const [view, setView] = useState<TableView | null>(null)
  const [reasoning, setReasoning] = useState<Reasoning>(NO_REASONING)
  const [connection, setConnection] = useState<Connection>('connecting')
  const [error, setError] = useState<string | null>(null)
  const socket = useRef<WebSocket | null>(null)

  useEffect(() => {
    let disposed = false
    let attempts = 0
    let retry: ReturnType<typeof setTimeout> | undefined

    const connect = () => {
      attempts++
      let opened = false
      const ws = new WebSocket(WS_URL)
      socket.current = ws

      ws.onopen = () => {
        opened = true
        setConnection('open')
      }
      ws.onclose = () => {
        if (disposed) return
        // A table that was open and dropped is gone, since its state lived on
        // that connection. One that never opened is probably still waking.
        if (!opened && attempts < MAX_ATTEMPTS) {
          retry = setTimeout(connect, RETRY_EVERY_MS)
        } else {
          setConnection('closed')
        }
      }
      ws.onmessage = (event) => {
        const message = JSON.parse(event.data as string) as ServerMessage
        switch (message.type) {
          case 'state':
            setView(message.view)
            setError(null)
            // A new hand starts with a clean slate.
            setReasoning((current) =>
              current.handNumber === message.view.handNumber
                ? current
                : { handNumber: message.view.handNumber, events: [], revealed: false },
            )
            break
          case 'trace':
            setReasoning((current) => ({
              handNumber: message.handNumber,
              events: current.handNumber === message.handNumber ? [...current.events, message.event] : [message.event],
              revealed: false,
            }))
            break
          case 'reveal':
            setReasoning({ handNumber: message.handNumber, events: message.events, revealed: true })
            break
          case 'error':
            setError(message.message)
            break
        }
      }
    }

    connect()

    return () => {
      disposed = true
      clearTimeout(retry)
      socket.current?.close()
    }
  }, [])

  const send = useCallback((message: ClientMessage) => {
    socket.current?.send(JSON.stringify(message))
  }, [])

  return { view, reasoning, connection, error, send }
}

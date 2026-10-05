import { useCallback, useEffect, useRef, useState } from 'react'

// One address is configured, the poker table's, and the others are found
// beside it. That keeps a deployment to a single setting.
const BASE = (import.meta.env.VITE_WS_URL ?? 'ws://localhost:8080/ws/table').replace(/\/ws\/table$/, '')

// Free hosting puts the backend to sleep when idle and takes up to a minute to
// wake it, so a first connection that fails is retried for a while.
const RETRY_EVERY_MS = 3_000
const MAX_ATTEMPTS = 30

export type Connection = 'connecting' | 'open' | 'closed'

/**
 * A connection to one game's table, shared by every game. Messages from the
 * server are handed to [onMessage] as they arrive; what they mean is the
 * game's business.
 */
export function useSocket<Incoming, Outgoing>(path: string, onMessage: (message: Incoming) => void) {
  const [connection, setConnection] = useState<Connection>('connecting')
  const socket = useRef<WebSocket | null>(null)

  // The handler is redefined on every render; the socket should not be.
  const handler = useRef(onMessage)
  useEffect(() => {
    handler.current = onMessage
  })

  useEffect(() => {
    let disposed = false
    let attempts = 0
    let retry: ReturnType<typeof setTimeout> | undefined

    const connect = () => {
      attempts++
      let opened = false
      const ws = new WebSocket(BASE + path)
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
      ws.onmessage = (event) => handler.current(JSON.parse(event.data as string) as Incoming)
    }

    connect()

    return () => {
      disposed = true
      clearTimeout(retry)
      socket.current?.close()
    }
  }, [path])

  const send = useCallback((message: Outgoing) => {
    socket.current?.send(JSON.stringify(message))
  }, [])

  return { connection, send }
}

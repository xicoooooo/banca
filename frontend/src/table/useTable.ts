import { useCallback, useEffect, useRef, useState } from 'react'
import type { ClientMessage, ServerMessage, TableView } from './types'

const WS_URL = import.meta.env.VITE_WS_URL ?? 'ws://localhost:8080/ws/table'

export type Connection = 'connecting' | 'open' | 'closed'

export function useTable() {
  const [view, setView] = useState<TableView | null>(null)
  const [connection, setConnection] = useState<Connection>('connecting')
  const [error, setError] = useState<string | null>(null)
  const socket = useRef<WebSocket | null>(null)

  useEffect(() => {
    const ws = new WebSocket(WS_URL)
    socket.current = ws

    ws.onopen = () => setConnection('open')
    ws.onclose = () => {
      // Ignore the close of a socket that has already been replaced.
      if (socket.current === ws) setConnection('closed')
    }
    ws.onmessage = (event) => {
      const message = JSON.parse(event.data as string) as ServerMessage
      if (message.type === 'state') {
        setView(message.view)
        setError(null)
      } else {
        setError(message.message)
      }
    }

    return () => ws.close()
  }, [])

  const send = useCallback((message: ClientMessage) => {
    socket.current?.send(JSON.stringify(message))
  }, [])

  return { view, connection, error, send }
}

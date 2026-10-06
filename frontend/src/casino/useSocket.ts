import { useCallback, useEffect, useRef, useState } from 'react'
import { forgetPlayer, playerToken } from '../player/identity'
import { socketUrl } from './server'

// Free hosting puts the backend to sleep when idle and takes up to a minute to
// wake it, so a first connection that fails is retried for a while.
const RETRY_EVERY_MS = 3_000
const MAX_ATTEMPTS = 30

export type Connection = 'connecting' | 'open' | 'closed'

/** What the server says before the game begins: who it takes this player to be, or that it does not know them. */
type Greeting = { type: 'welcome' } | { type: 'error'; code?: string }

/**
 * A connection to one game's table, shared by every game. It says who the
 * player is before anything else, and from then on messages from the server
 * are handed to [onMessage] as they arrive; what they mean is the game's
 * business.
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

    const tryAgainOrGiveUp = () => {
      if (attempts < MAX_ATTEMPTS) retry = setTimeout(connect, RETRY_EVERY_MS)
      else setConnection('closed')
    }

    const connect = async () => {
      attempts++

      let token: string
      try {
        token = await playerToken()
      } catch {
        if (!disposed) tryAgainOrGiveUp()
        return
      }
      if (disposed) return

      let welcomed = false
      let stranger = false
      const ws = new WebSocket(socketUrl(path))
      socket.current = ws

      ws.onopen = () => ws.send(JSON.stringify({ type: 'hello', token }))

      ws.onmessage = (event) => {
        const message = JSON.parse(event.data as string) as Incoming | Greeting
        if (!welcomed) {
          const greeting = message as Greeting
          if (greeting.type === 'welcome') {
            welcomed = true
            setConnection('open')
          } else if (greeting.type === 'error' && greeting.code === 'unknown_player') {
            // The server has forgotten this guest; the next attempt asks for a new one.
            stranger = true
            forgetPlayer(token)
          }
          return
        }
        handler.current(message as Incoming)
      }

      ws.onclose = () => {
        if (disposed) return
        if (stranger && attempts < MAX_ATTEMPTS) {
          void connect()
          return
        }
        // A table that was open and dropped is gone, since its state lived on
        // that connection. One that never opened is probably still waking.
        if (welcomed) setConnection('closed')
        else tryAgainOrGiveUp()
      }
    }

    void connect()

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
